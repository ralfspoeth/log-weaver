package io.github.ralfspoeth.log.weaver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.*;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests für {@link LogWeaver}.
 *
 * <p>Statt eine Java-Klasse als Fixture zu kompilieren, erzeugt jeder Test
 * seine Eingabe direkt über die {@code java.lang.classfile}-API. So bleibt
 * der Test in sich abgeschlossen und unabhängig von log-api zur Compile-Zeit.
 */
class LogWeaverTest {

    private static final ClassDesc OBJECT_CD = ClassDesc.of("java.lang.Object");
    private static final ClassDesc STRING_CD = ClassDesc.of("java.lang.String");
    private static final ClassDesc LOG_CD = ClassDesc.of("io.github.ralfspoeth.log.api.Log");
    private static final ClassDesc LEVEL_CD = ClassDesc.of("java.lang.System$Logger$Level");

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void weavesAnnotatedMethodAndAddsSyntheticHelper(@TempDir Path tempDir) throws Exception {
        ClassDesc fooCd = ClassDesc.of("com.example.Foo");
        Annotation logAnn = Annotation.of(LOG_CD,
                AnnotationElement.ofString("value", "%s"),
                AnnotationElement.of("level", AnnotationValue.ofEnum(LEVEL_CD, "INFO")));

        Path classFile = writeFixture(tempDir, fooCd,
                "greet", MethodTypeDesc.of(CD_void, STRING_CD), logAnn);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));

        // 1) Synthetische Helfer-Methode wurde hinzugefügt
        MethodModel synthetic = findMethod(cm, "lambda$logweaver$greet");
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;",
                synthetic.methodType().stringValue(),
                "Helfer muss (String)String sein");
        assertTrue(synthetic.flags().has(AccessFlag.STATIC), "Helfer muss static sein");
        assertTrue(synthetic.flags().has(AccessFlag.PRIVATE), "Helfer muss private sein");
        assertTrue(synthetic.flags().has(AccessFlag.SYNTHETIC), "Helfer muss synthetic sein");

        // 2) Original-Methode wurde mit Logging-Prolog präfixiert
        List<String> ops = opcodes(findMethod(cm, "greet"));
        assertTrue(ops.contains("INVOKESTATIC"),
                "Prolog muss System.getLogger rufen");
        assertTrue(ops.contains("GETSTATIC"),
                "Prolog muss Level-Konstante laden");
        assertTrue(ops.contains("INVOKEDYNAMIC"),
                "Prolog muss Supplier via invokedynamic erzeugen");
        assertTrue(ops.contains("INVOKEINTERFACE"),
                "Prolog muss Logger.log aufrufen");

        // 3) Reihenfolge: indy ist Teil des Prologs, also vor dem RETURN
        int indyIdx = ops.indexOf("INVOKEDYNAMIC");
        int retIdx = ops.lastIndexOf("RETURN");
        assertTrue(indyIdx >= 0 && retIdx > indyIdx,
                "Logging-Prolog muss vor dem Originalcode liegen");
    }

    @Test
    void leavesUnannotatedClassUntouched(@TempDir Path tempDir) throws Exception {
        ClassDesc barCd = ClassDesc.of("com.example.Bar");
        Path classFile = writeFixture(tempDir, barCd,
                "doNothing", MethodTypeDesc.of(CD_void), /* keine @Log */ null);

        byte[] before = Files.readAllBytes(classFile);
        runWeaver(tempDir);
        byte[] after = Files.readAllBytes(classFile);

        assertArrayEquals(before, after,
                "Klasse ohne @Log darf bytes-identisch bleiben");
    }

    @Test
    void boxesPrimitiveParametersInSyntheticHelper(@TempDir Path tempDir) throws Exception {
        ClassDesc bazCd = ClassDesc.of("com.example.Baz");
        Annotation logAnn = Annotation.of(LOG_CD,
                AnnotationElement.ofString("value", "n=%d"),
                AnnotationElement.of("level", AnnotationValue.ofEnum(LEVEL_CD, "WARNING")));

        Path classFile = writeFixture(tempDir, bazCd,
                "count", MethodTypeDesc.of(CD_void, CD_int), logAnn);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));
        MethodModel synthetic = findMethod(cm, "lambda$logweaver$count");

        // int wurde zu Integer geboxt
        assertEquals("(Ljava/lang/Integer;)Ljava/lang/String;",
                synthetic.methodType().stringValue(),
                "Primitive Parameter müssen in der Helfer-Methode geboxt sein");

        // Aufrufer ruft Integer.valueOf(int) vor invokedynamic auf
        List<String> ops = opcodes(findMethod(cm, "count"));
        assertTrue(ops.contains("INVOKESTATIC"),
                "Boxing-Aufruf Integer.valueOf muss vorhanden sein");
        assertTrue(ops.contains("INVOKEDYNAMIC"),
                "Supplier-Erzeugung muss vorhanden sein");
    }

    @Test
    void weavesMineHelloWithMixedParameters(@TempDir Path tempDir) throws Exception {
        // class Mine { @Log("int %d String %s") void hello(int a, String b) {} }
        ClassDesc mineCd = ClassDesc.of("Mine");
        Annotation logAnn = Annotation.of(LOG_CD,
                AnnotationElement.ofString("value", "int %d String %s"));

        Path classFile = writeFixture(tempDir, mineCd,
                "hello", MethodTypeDesc.of(CD_void, CD_int, STRING_CD), logAnn);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));

        // Helfer-Methode: int wird zu Integer geboxt, String bleibt String,
        // Rückgabetyp ist die formatierte Nachricht (String).
        MethodModel synthetic = findMethod(cm, "lambda$logweaver$hello");
        assertEquals("(Ljava/lang/Integer;Ljava/lang/String;)Ljava/lang/String;",
                synthetic.methodType().stringValue(),
                "Helfer muss (Integer, String) -> String sein");
        assertTrue(synthetic.flags().has(AccessFlag.STATIC), "Helfer muss static sein");
        assertTrue(synthetic.flags().has(AccessFlag.PRIVATE), "Helfer muss private sein");
        assertTrue(synthetic.flags().has(AccessFlag.SYNTHETIC), "Helfer muss synthetic sein");

        // Prolog der Original-Methode
        List<String> ops = opcodes(findMethod(cm, "hello"));
        assertTrue(ops.contains("INVOKESTATIC"),
                "Prolog muss System.getLogger und Integer.valueOf rufen");
        assertTrue(ops.contains("GETSTATIC"),
                "Prolog muss Level-Konstante laden (Default INFO)");
        assertTrue(ops.contains("INVOKEDYNAMIC"),
                "Prolog muss Supplier via invokedynamic erzeugen");
        assertTrue(ops.contains("INVOKEINTERFACE"),
                "Prolog muss Logger.log aufrufen");

        // Zwei INVOKESTATIC-Aufrufe erwartet:
        //  - System.getLogger(String)
        //  - Integer.valueOf(int)
        long invokeStaticCount = ops.stream().filter("INVOKESTATIC"::equals).count();
        assertTrue(invokeStaticCount >= 2,
                "Erwartet mindestens System.getLogger und Integer.valueOf, war: " + invokeStaticCount);

        // Reihenfolge: getLogger -> getstatic Level -> aload(int) -> Integer.valueOf
        // -> aload(String) -> invokedynamic -> Logger.log -> RETURN
        int indyIdx = ops.indexOf("INVOKEDYNAMIC");
        int retIdx = ops.lastIndexOf("RETURN");
        assertTrue(indyIdx >= 0 && retIdx > indyIdx,
                "Logging-Prolog muss vor dem Originalcode liegen");

        try (var cl = new URLClassLoader(new URL[]{tempDir.toUri().toURL()})) {
            var mineClass = cl.loadClass("Mine");
            var helloMethod = mineClass.getMethod("hello", int.class, String.class);
            var mine = mineClass.getDeclaredConstructor().newInstance();
            helloMethod.invoke(mine, 2, "Two");
        }
    }

    // ── Hilfsfunktionen ──────────────────────────────────────────────────────

    private static Path writeFixture(Path baseDir,
                                     ClassDesc thisClass,
                                     String methodName,
                                     MethodTypeDesc methodType,
                                     Annotation logAnnotation) throws Exception {

        byte[] bytes = ClassFile.of().build(thisClass, clb -> {
            clb.withSuperclass(OBJECT_CD);

            // public <init>() { super(); }
            clb.withMethodBody("<init>", MethodTypeDesc.of(CD_void),
                    ClassFile.ACC_PUBLIC, cb -> {
                        cb.aload(0);
                        cb.invokespecial(OBJECT_CD, "<init>", MethodTypeDesc.of(CD_void));
                        cb.return_();
                    });

            // public void <methodName>(<params>) { /* ggf. mit @Log */ }
            clb.withMethod(methodName, methodType, ClassFile.ACC_PUBLIC, mb -> {
                if (logAnnotation != null) {
                    mb.with(RuntimeVisibleAnnotationsAttribute.of(logAnnotation));
                }
                mb.withCode(CodeBuilder::return_);
            });
        });

        // Pfad ableiten aus internem Klassennamen, z.B. "com/example/Foo"
        String internal = thisClass.descriptorString();
        internal = internal.substring(1, internal.length() - 1); // L...; -> ...
        Path classFile = baseDir.resolve(internal + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);
        return classFile;
    }

    private static void runWeaver(Path classesDir) throws Exception {
        LogWeaver weaver = new LogWeaver();
        weaver.setClassesDir(classesDir);
        weaver.execute();
    }

    private static MethodModel findMethod(ClassModel cm, String name) {
        return cm.methods().stream()
                .filter(m -> m.methodName().stringValue().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Methode nicht gefunden: " + name));
    }

    private static List<String> opcodes(MethodModel mm) {
        CodeAttribute code = mm.findAttribute(Attributes.code()).orElseThrow();
        List<String> ops = new ArrayList<>();
        for (CodeElement e : code) {
            if (e instanceof Instruction insn) {
                ops.add(insn.opcode().name());
            }
        }
        return ops;
    }
}

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
 * Tests for {@link LogWeaver}.
 *
 * <p>Rather than compile a Java class as a fixture, each test builds its
 * input directly through the {@code java.lang.classfile} API. The test
 * stays self-contained and compile-time-independent of log-api.
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

        // 1) The synthetic helper method was added
        MethodModel synthetic = findMethod(cm, "lambda$logweaver$greet");
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;",
                synthetic.methodType().stringValue(),
                "helper must be (String)String");
        assertTrue(synthetic.flags().has(AccessFlag.STATIC), "helper must be static");
        assertTrue(synthetic.flags().has(AccessFlag.PRIVATE), "helper must be private");
        assertTrue(synthetic.flags().has(AccessFlag.SYNTHETIC), "helper must be synthetic");

        // 2) The original method was prefixed with the logging prologue
        List<String> ops = opcodes(findMethod(cm, "greet"));
        assertTrue(ops.contains("INVOKESTATIC"),
                "prologue must call System.getLogger");
        assertTrue(ops.contains("GETSTATIC"),
                "prologue must load the level constant");
        assertTrue(ops.contains("INVOKEDYNAMIC"),
                "prologue must build the Supplier via invokedynamic");
        assertTrue(ops.contains("INVOKEINTERFACE"),
                "prologue must call Logger.log");

        // 3) Order: indy is part of the prologue, hence before RETURN
        int indyIdx = ops.indexOf("INVOKEDYNAMIC");
        int retIdx = ops.lastIndexOf("RETURN");
        assertTrue(indyIdx >= 0 && retIdx > indyIdx,
                "logging prologue must precede the original code");
    }

    @Test
    void leavesUnannotatedClassUntouched(@TempDir Path tempDir) throws Exception {
        ClassDesc barCd = ClassDesc.of("com.example.Bar");
        Path classFile = writeFixture(tempDir, barCd,
                "doNothing", MethodTypeDesc.of(CD_void), /* no @Log */ null);

        byte[] before = Files.readAllBytes(classFile);
        runWeaver(tempDir);
        byte[] after = Files.readAllBytes(classFile);

        assertArrayEquals(before, after,
                "a class without @Log must remain bytes-identical");
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

        // int got boxed to Integer
        assertEquals("(Ljava/lang/Integer;)Ljava/lang/String;",
                synthetic.methodType().stringValue(),
                "primitive parameters must be boxed in the helper method");

        // The caller invokes Integer.valueOf(int) before invokedynamic
        List<String> ops = opcodes(findMethod(cm, "count"));
        assertTrue(ops.contains("INVOKESTATIC"),
                "the Integer.valueOf boxing call must be present");
        assertTrue(ops.contains("INVOKEDYNAMIC"),
                "the Supplier creation must be present");
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

        // Helper method: int gets boxed to Integer, String stays String,
        // return type is the formatted message (String).
        MethodModel synthetic = findMethod(cm, "lambda$logweaver$hello");
        assertEquals("(Ljava/lang/Integer;Ljava/lang/String;)Ljava/lang/String;",
                synthetic.methodType().stringValue(),
                "helper must be (Integer, String) -> String");
        assertTrue(synthetic.flags().has(AccessFlag.STATIC), "helper must be static");
        assertTrue(synthetic.flags().has(AccessFlag.PRIVATE), "helper must be private");
        assertTrue(synthetic.flags().has(AccessFlag.SYNTHETIC), "helper must be synthetic");

        // Original method's prologue
        List<String> ops = opcodes(findMethod(cm, "hello"));
        assertTrue(ops.contains("INVOKESTATIC"),
                "prologue must call System.getLogger and Integer.valueOf");
        assertTrue(ops.contains("GETSTATIC"),
                "prologue must load the level constant (default INFO)");
        assertTrue(ops.contains("INVOKEDYNAMIC"),
                "prologue must build the Supplier via invokedynamic");
        assertTrue(ops.contains("INVOKEINTERFACE"),
                "prologue must call Logger.log");

        // Two INVOKESTATIC calls expected:
        //  - System.getLogger(String)
        //  - Integer.valueOf(int)
        long invokeStaticCount = ops.stream().filter("INVOKESTATIC"::equals).count();
        assertTrue(invokeStaticCount >= 2,
                "expected at least System.getLogger and Integer.valueOf, was: " + invokeStaticCount);

        // Order: getLogger -> getstatic Level -> aload(int) -> Integer.valueOf
        // -> aload(String) -> invokedynamic -> Logger.log -> RETURN
        int indyIdx = ops.indexOf("INVOKEDYNAMIC");
        int retIdx = ops.lastIndexOf("RETURN");
        assertTrue(indyIdx >= 0 && retIdx > indyIdx,
                "logging prologue must precede the original code");

        try (var cl = new URLClassLoader(new URL[]{tempDir.toUri().toURL()})) {
            var mineClass = cl.loadClass("Mine");
            var helloMethod = mineClass.getMethod("hello", int.class, String.class);
            var mine = mineClass.getDeclaredConstructor().newInstance();
            helloMethod.invoke(mine, 2, "Two");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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

            // public void <methodName>(<params>) { /* optionally with @Log */ }
            clb.withMethod(methodName, methodType, ClassFile.ACC_PUBLIC, mb -> {
                if (logAnnotation != null) {
                    mb.with(RuntimeVisibleAnnotationsAttribute.of(logAnnotation));
                }
                mb.withCode(CodeBuilder::return_);
            });
        });

        // Derive path from the internal class name, e.g. "com/example/Foo"
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
                .orElseThrow(() -> new AssertionError("method not found: " + name));
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

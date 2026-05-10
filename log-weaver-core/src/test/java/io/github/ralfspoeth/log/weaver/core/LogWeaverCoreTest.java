package io.github.ralfspoeth.log.weaver.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.*;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Modifier;
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
 * Tests for {@link LogWeaverCore}.
 *
 * <p>Each test builds its input directly through the {@code java.lang.classfile}
 * API. The suite stays self-contained and compile-time-independent of log-api.
 */
class LogWeaverCoreTest {

    private static final ClassDesc OBJECT_CD = ClassDesc.of("java.lang.Object");
    private static final ClassDesc STRING_CD = ClassDesc.of("java.lang.String");
    private static final ClassDesc LOG_CD = ClassDesc.of("io.github.ralfspoeth.log.api.Log");
    private static final ClassDesc LOG_ALL_CD = ClassDesc.of("io.github.ralfspoeth.log.api.LogAll");
    private static final ClassDesc LEVEL_CD = ClassDesc.of("java.lang.System$Logger$Level");

    // ── Existing @Log tests ──────────────────────────────────────────────────

    @Test
    void weavesAnnotatedMethodAndAddsSyntheticHelper(@TempDir Path tempDir) throws Exception {
        ClassDesc fooCd = ClassDesc.of("com.example.Foo");
        Annotation logAnn = Annotation.of(LOG_CD,
                AnnotationElement.of("level", AnnotationValue.ofEnum(LEVEL_CD, "INFO")));

        Path classFile = writeFixture(tempDir, fooCd,
                "greet", MethodTypeDesc.of(CD_void, STRING_CD), logAnn);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));

        MethodModel synthetic = findMethodByPrefix(cm, "lambda$logweaver$greet$");
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;",
                synthetic.methodType().stringValue(),
                "helper must be (String)String");
        assertTrue(synthetic.flags().has(AccessFlag.STATIC), "helper must be static");
        assertTrue(synthetic.flags().has(AccessFlag.PRIVATE), "helper must be private");
        assertTrue(synthetic.flags().has(AccessFlag.SYNTHETIC), "helper must be synthetic");

        FieldModel loggerField = findField(cm, LogWeaverCore.LOGGER_FIELD_NAME);
        assertTrue(loggerField.flags().has(AccessFlag.STATIC), "LOGGER must be static");
        assertTrue(loggerField.flags().has(AccessFlag.FINAL), "LOGGER must be final");
        assertTrue(loggerField.flags().has(AccessFlag.PRIVATE), "LOGGER must be private");

        MethodModel clinit = findMethod(cm, "<clinit>");
        List<String> clinitOps = opcodes(clinit);
        assertTrue(clinitOps.contains("INVOKESTATIC"), "<clinit> must call System.getLogger");
        assertTrue(clinitOps.contains("PUTSTATIC"), "<clinit> must store the result into LOGGER");

        List<String> ops = opcodes(findMethod(cm, "greet"));
        assertTrue(ops.contains("GETSTATIC"), "prologue must load LOGGER (and Level) via getstatic");
        assertTrue(ops.contains("INVOKEDYNAMIC"), "prologue must build the Supplier via invokedynamic");
        assertTrue(ops.contains("INVOKEINTERFACE"), "prologue must call Logger.log");

        int indyIdx = ops.indexOf("INVOKEDYNAMIC");
        int retIdx = ops.lastIndexOf("RETURN");
        assertTrue(indyIdx >= 0 && retIdx > indyIdx,
                "logging prologue must precede the original code");

        MethodModel greet = findMethod(cm, "greet");
        assertFalse(hasAnnotation(greet, LOG_CD),
                "@Log must be stripped from the method after weaving");
    }

    @Test
    void leavesUnannotatedClassUntouched(@TempDir Path tempDir) throws Exception {
        ClassDesc barCd = ClassDesc.of("com.example.Bar");
        Path classFile = writeFixture(tempDir, barCd,
                "doNothing", MethodTypeDesc.of(CD_void), /* no @Log */ null);

        byte[] before = Files.readAllBytes(classFile);
        runWeaver(tempDir);
        byte[] after = Files.readAllBytes(classFile);

        assertArrayEquals(before, after, "a class without @Log must remain bytes-identical");
    }

    @Test
    void boxesPrimitiveParametersInSyntheticHelper(@TempDir Path tempDir) throws Exception {
        ClassDesc bazCd = ClassDesc.of("com.example.Baz");
        Annotation logAnn = Annotation.of(LOG_CD,
                AnnotationElement.of("level", AnnotationValue.ofEnum(LEVEL_CD, "WARNING")));

        Path classFile = writeFixture(tempDir, bazCd,
                "count", MethodTypeDesc.of(CD_void, CD_int), logAnn);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));
        MethodModel synthetic = findMethodByPrefix(cm, "lambda$logweaver$count$");

        assertEquals("(Ljava/lang/Integer;)Ljava/lang/String;",
                synthetic.methodType().stringValue(),
                "primitive parameters must be boxed in the helper method");

        List<String> ops = opcodes(findMethod(cm, "count"));
        assertTrue(ops.contains("INVOKESTATIC"), "the Integer.valueOf boxing call must be present");
        assertTrue(ops.contains("INVOKEDYNAMIC"), "the Supplier creation must be present");
    }

    @Test
    void weavesMineHelloWithMixedParameters(@TempDir Path tempDir) throws Exception {
        ClassDesc mineCd = ClassDesc.of("Mine");
        Annotation logAnn = Annotation.of(LOG_CD);

        Path classFile = writeFixture(tempDir, mineCd,
                "hello", MethodTypeDesc.of(CD_void, CD_int, STRING_CD), logAnn);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));

        MethodModel synthetic = findMethodByPrefix(cm, "lambda$logweaver$hello$");
        assertEquals("(Ljava/lang/Integer;Ljava/lang/String;)Ljava/lang/String;",
                synthetic.methodType().stringValue(),
                "helper must be (Integer, String) -> String");

        List<String> ops = opcodes(findMethod(cm, "hello"));
        assertTrue(ops.contains("GETSTATIC"));
        assertTrue(ops.contains("INVOKEDYNAMIC"));
        assertTrue(ops.contains("INVOKEINTERFACE"));

        long invokeStaticCount = ops.stream().filter("INVOKESTATIC"::equals).count();
        assertTrue(invokeStaticCount >= 1,
                "expected at least Integer.valueOf, was: " + invokeStaticCount);

        int indyIdx = ops.indexOf("INVOKEDYNAMIC");
        int retIdx = ops.lastIndexOf("RETURN");
        assertTrue(indyIdx >= 0 && retIdx > indyIdx);

        try (var cl = new URLClassLoader(new URL[]{tempDir.toUri().toURL()})) {
            var mineClass = cl.loadClass("Mine");
            var helloMethod = mineClass.getMethod("hello", int.class, String.class);
            var mine = mineClass.getDeclaredConstructor().newInstance();
            helloMethod.invoke(mine, 2, "Two");
        }
    }

    // ── @LogAll tests ────────────────────────────────────────────────────────

    @Test
    void classLevelLogAllWeavesAllMatchingMethods(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.Multi");
        Annotation logAllAnn = logAllAnnotation(0, "INFO", ".*");

        Path classFile = writeClassWithLogAll(tempDir, cd, logAllAnn, clb -> {
            clb.withMethod("foo", MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC,
                    mb -> mb.withCode(CodeBuilder::return_));
            clb.withMethod("bar", MethodTypeDesc.of(CD_void, CD_int), ClassFile.ACC_PUBLIC,
                    mb -> mb.withCode(CodeBuilder::return_));
        });

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));

        assertNotNull(findMethodByPrefix(cm, "lambda$logweaver$foo$"), "@LogAll must weave foo");
        assertNotNull(findMethodByPrefix(cm, "lambda$logweaver$bar$"), "@LogAll must weave bar");
        assertFalse(hasMethodWithPrefix(cm, "lambda$logweaver$<init>"), "<init> must not be woven");
        assertNotNull(findField(cm, LogWeaverCore.LOGGER_FIELD_NAME));
        assertNotNull(findMethod(cm, "<clinit>"));
    }

    @Test
    void methodPatternFiltersMethods(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.Patterned");
        Annotation logAllAnn = logAllAnnotation(0, "INFO", "get.*");

        Path classFile = writeClassWithLogAll(tempDir, cd, logAllAnn, clb -> {
            clb.withMethod("getName", MethodTypeDesc.of(STRING_CD), ClassFile.ACC_PUBLIC,
                    mb -> mb.withCode(cb -> {
                        cb.aconst_null();
                        cb.areturn();
                    }));
            clb.withMethod("setName", MethodTypeDesc.of(CD_void, STRING_CD), ClassFile.ACC_PUBLIC,
                    mb -> mb.withCode(CodeBuilder::return_));
        });

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));
        assertNotNull(findMethodByPrefix(cm, "lambda$logweaver$getName$"));
        assertFalse(hasMethodWithPrefix(cm, "lambda$logweaver$setName$"));
    }

    @Test
    void modifiersMaskFiltersByVisibility(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.Visibility");
        Annotation logAllAnn = logAllAnnotation(Modifier.PUBLIC, "INFO", ".*");

        Path classFile = writeClassWithLogAll(tempDir, cd, logAllAnn, clb -> {
            clb.withMethod("publicMethod", MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC,
                    mb -> mb.withCode(CodeBuilder::return_));
            clb.withMethod("privateMethod", MethodTypeDesc.of(CD_void), ClassFile.ACC_PRIVATE,
                    mb -> mb.withCode(CodeBuilder::return_));
        });

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));
        assertNotNull(findMethodByPrefix(cm, "lambda$logweaver$publicMethod$"));
        assertFalse(hasMethodWithPrefix(cm, "lambda$logweaver$privateMethod$"));
    }

    @Test
    void methodLevelLogOverridesClassLogAll(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.Override");
        Annotation logAllAnn = logAllAnnotation(0, "INFO", ".*");
        Annotation logAnn = Annotation.of(LOG_CD,
                AnnotationElement.of("level", AnnotationValue.ofEnum(LEVEL_CD, "ERROR")));

        Path classFile = writeClassWithLogAll(tempDir, cd, logAllAnn, clb ->
                clb.withMethod("foo", MethodTypeDesc.of(CD_void, STRING_CD), ClassFile.ACC_PUBLIC,
                        mb -> {
                            mb.with(RuntimeVisibleAnnotationsAttribute.of(logAnn));
                            mb.withCode(CodeBuilder::return_);
                        }));

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));
        MethodModel foo = findMethod(cm, "foo");
        List<String> levelRefs = fieldRefNames(foo, LEVEL_CD);
        assertTrue(levelRefs.contains("ERROR"),
                "method-level @Log level (ERROR) must win over @LogAll's INFO");
        assertFalse(levelRefs.contains("INFO"),
                "the @LogAll INFO level must not be referenced in the prologue");

        MethodModel helper = findMethodByPrefix(cm, "lambda$logweaver$foo$");
        List<String> stringConstants = ldcStringConstants(helper);
        assertTrue(stringConstants.contains("Override.foo(%s)"));
    }

    @Test
    void constructorAndStaticInitNotWoven(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.NoCtor");
        Annotation logAllAnn = logAllAnnotation(0, "INFO", ".*");

        Path classFile = writeClassWithLogAll(tempDir, cd, logAllAnn, clb -> {
            clb.withMethod("<clinit>", MethodTypeDesc.of(CD_void), ClassFile.ACC_STATIC,
                    mb -> mb.withCode(CodeBuilder::return_));
            clb.withMethod("doStuff", MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC,
                    mb -> mb.withCode(CodeBuilder::return_));
        });

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));
        assertNotNull(findMethodByPrefix(cm, "lambda$logweaver$doStuff$"));
        assertFalse(hasMethodWithPrefix(cm, "lambda$logweaver$<init>"));
        assertFalse(hasMethodWithPrefix(cm, "lambda$logweaver$<clinit>"));

        List<String> clinitOps = opcodes(findMethod(cm, "<clinit>"));
        assertTrue(clinitOps.contains("INVOKESTATIC"));
        assertTrue(clinitOps.contains("PUTSTATIC"));
    }

    // ── logReturn / exceptionLevel ───────────────────────────────────────────

    @Test
    void returnLogForVoidMethodEmitsHelperWithoutResultCapture(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.RetVoid");
        Annotation logAnn = Annotation.of(LOG_CD,
                AnnotationElement.of("logReturn", AnnotationValue.ofBoolean(true)));

        Path classFile = writeFixture(tempDir, cd,
                "doIt", MethodTypeDesc.of(CD_void, STRING_CD), logAnn);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));

        MethodModel helper = findMethodByPrefix(cm, "lambda$logweaver$doIt$");
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;",
                helper.methodType().stringValue(),
                "void return helper must capture only the parameters");

        List<String> ops = opcodes(findMethod(cm, "doIt"));
        assertEquals(1, ops.stream().filter("INVOKEDYNAMIC"::equals).count(),
                "logReturn=true emits the return log only – no entry log");
        assertEquals(2, ops.stream().filter("INVOKEINTERFACE"::equals).count(),
                "return prologue + always-on Throwable handler each call Logger.log");
    }

    @Test
    void returnLogForIntMethodCapturesBoxedResult(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.RetInt");
        Annotation logAnn = Annotation.of(LOG_CD,
                AnnotationElement.of("logReturn", AnnotationValue.ofBoolean(true)));

        byte[] bytes = ClassFile.of().build(cd, clb -> {
            clb.withSuperclass(OBJECT_CD);
            clb.withMethodBody("<init>", MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(OBJECT_CD, "<init>", MethodTypeDesc.of(CD_void));
                cb.return_();
            });
            clb.withMethod("five", MethodTypeDesc.of(CD_int), ClassFile.ACC_PUBLIC, mb -> {
                mb.with(RuntimeVisibleAnnotationsAttribute.of(logAnn));
                mb.withCode(cb -> {
                    cb.bipush(5);
                    cb.ireturn();
                });
            });
        });
        Path classFile = tempDir.resolve("com/example/RetInt.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));
        MethodModel helper = findMethodByPrefix(cm, "lambda$logweaver$five$");
        assertEquals("(Ljava/lang/Integer;)Ljava/lang/String;",
                helper.methodType().stringValue(),
                "non-void return helper must capture the boxed result");

        try (var cl = new URLClassLoader(new URL[]{tempDir.toUri().toURL()})) {
            var klass = cl.loadClass("com.example.RetInt");
            var instance = klass.getDeclaredConstructor().newInstance();
            Object result = klass.getMethod("five").invoke(instance);
            assertEquals(5, result, "return-log injection must preserve the original value");
        }
    }

    @Test
    void exceptionLogAddsThrowableHandlerAndRethrows(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.Throwy");
        Annotation logAnn = Annotation.of(LOG_CD,
                AnnotationElement.of("exceptionLevel", AnnotationValue.ofEnum(LEVEL_CD, "ERROR")));

        ClassDesc rtExceptionCd = ClassDesc.of("java.lang.RuntimeException");

        byte[] bytes = ClassFile.of().build(cd, clb -> {
            clb.withSuperclass(OBJECT_CD);
            clb.withMethodBody("<init>", MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(OBJECT_CD, "<init>", MethodTypeDesc.of(CD_void));
                cb.return_();
            });
            clb.withMethod("throwIt", MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC, mb -> {
                mb.with(RuntimeVisibleAnnotationsAttribute.of(logAnn));
                mb.withCode(cb -> {
                    cb.new_(rtExceptionCd);
                    cb.dup();
                    cb.ldc("oops");
                    cb.invokespecial(rtExceptionCd, "<init>",
                            MethodTypeDesc.of(CD_void, STRING_CD));
                    cb.athrow();
                });
            });
        });
        Path classFile = tempDir.resolve("com/example/Throwy.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));

        ClassDesc throwableCd = ClassDesc.of("java.lang.Throwable");
        MethodModel throwIt = findMethod(cm, "throwIt");
        CodeAttribute code = throwIt.findAttribute(Attributes.code()).orElseThrow();
        boolean hasThrowableHandler = code.exceptionHandlers().stream()
                .anyMatch(eh -> eh.catchType()
                        .map(ce -> ce.asSymbol().equals(throwableCd))
                        .orElse(false));
        assertTrue(hasThrowableHandler);

        List<String> ops = opcodes(throwIt);
        assertTrue(ops.contains("INVOKEVIRTUAL"));
        assertTrue(ops.contains("ATHROW"));

        try (var cl = new URLClassLoader(new URL[]{tempDir.toUri().toURL()})) {
            var klass = cl.loadClass("com.example.Throwy");
            var instance = klass.getDeclaredConstructor().newInstance();
            var m = klass.getMethod("throwIt");
            var ite = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> m.invoke(instance));
            assertInstanceOf(RuntimeException.class, ite.getCause());
            assertEquals("oops", ite.getCause().getMessage());
        }
    }

    @Test
    void logReturnPlusExplicitExceptionLevel(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.Both");
        Annotation logAnn = Annotation.of(LOG_CD,
                AnnotationElement.of("logReturn", AnnotationValue.ofBoolean(true)),
                AnnotationElement.of("exceptionLevel", AnnotationValue.ofEnum(LEVEL_CD, "ERROR")));

        Path classFile = writeFixture(tempDir, cd,
                "noop", MethodTypeDesc.of(CD_void), logAnn);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));

        MethodModel helper = findMethodByPrefix(cm, "lambda$logweaver$noop$");
        assertEquals("()Ljava/lang/String;", helper.methodType().stringValue());

        ClassDesc throwableCd = ClassDesc.of("java.lang.Throwable");
        MethodModel noop = findMethod(cm, "noop");
        CodeAttribute code = noop.findAttribute(Attributes.code()).orElseThrow();
        assertTrue(code.exceptionHandlers().stream()
                .anyMatch(eh -> eh.catchType()
                        .map(ce -> ce.asSymbol().equals(throwableCd))
                        .orElse(false)));
        assertTrue(fieldRefNames(noop, LEVEL_CD).contains("ERROR"));
    }

    @Test
    void defaultsEmitEntryLogPlusExceptionHandlerAtWarning(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.PlainLog");
        Annotation logAnn = Annotation.of(LOG_CD);

        Path classFile = writeFixture(tempDir, cd,
                "greet", MethodTypeDesc.of(CD_void, STRING_CD), logAnn);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));

        MethodModel helper = findMethodByPrefix(cm, "lambda$logweaver$greet$");
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;",
                helper.methodType().stringValue());

        MethodModel greet = findMethod(cm, "greet");
        List<String> ops = opcodes(greet);
        assertEquals(1, ops.stream().filter("INVOKEDYNAMIC"::equals).count());
        assertEquals(2, ops.stream().filter("INVOKEINTERFACE"::equals).count());

        ClassDesc throwableCd = ClassDesc.of("java.lang.Throwable");
        CodeAttribute code = greet.findAttribute(Attributes.code()).orElseThrow();
        assertTrue(code.exceptionHandlers().stream()
                        .anyMatch(eh -> eh.catchType()
                                .map(ce -> ce.asSymbol().equals(throwableCd))
                                .orElse(false)),
                "default exceptionLevel (WARNING) must add a Throwable handler");

        assertTrue(fieldRefNames(greet, LEVEL_CD).contains("WARNING"));
    }

    // ── Varargs ──────────────────────────────────────────────────────────────

    @Test
    void varargsAreLoggedAsCommaSeparatedElements(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.Va");
        Annotation logAnn = Annotation.of(LOG_CD);

        ClassDesc strArr = STRING_CD.arrayType();
        byte[] bytes = ClassFile.of().build(cd, clb -> {
            clb.withSuperclass(OBJECT_CD);
            clb.withMethodBody("<init>", MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(OBJECT_CD, "<init>", MethodTypeDesc.of(CD_void));
                cb.return_();
            });
            clb.withMethod("greet", MethodTypeDesc.of(CD_void, strArr),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_VARARGS, mb -> {
                        mb.with(RuntimeVisibleAnnotationsAttribute.of(logAnn));
                        mb.withCode(CodeBuilder::return_);
                    });
        });
        Path classFile = tempDir.resolve("com/example/Va.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));

        MethodModel vaHelper = findMethod(cm, LogWeaverCore.VA_HELPER_NAME);
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;",
                vaHelper.methodType().stringValue());

        MethodModel format = findMethodByPrefix(cm, "lambda$logweaver$greet$");
        assertEquals("([Ljava/lang/String;)Ljava/lang/String;",
                format.methodType().stringValue());

        try (var cl = new URLClassLoader(new URL[]{tempDir.toUri().toURL()})) {
            var klass = cl.loadClass("com.example.Va");
            java.lang.reflect.Method m = java.util.Arrays.stream(klass.getDeclaredMethods())
                    .filter(mm -> mm.getName().startsWith("lambda$logweaver$greet$"))
                    .findFirst()
                    .orElseThrow();
            m.setAccessible(true);

            assertEquals("Va.greet(alice, bob)",
                    m.invoke(null, (Object) new String[]{"alice", "bob"}));
            assertEquals("Va.greet(alice)",
                    m.invoke(null, (Object) new String[]{"alice"}));
            assertEquals("Va.greet()",
                    m.invoke(null, (Object) new String[]{}));
        }
    }

    @Test
    void primitiveVarargsUseTheMatchingArraysToStringOverload(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.IntVa");
        Annotation logAnn = Annotation.of(LOG_CD);

        ClassDesc intArr = ClassDesc.ofDescriptor("[I");
        byte[] bytes = ClassFile.of().build(cd, clb -> {
            clb.withSuperclass(OBJECT_CD);
            clb.withMethodBody("<init>", MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(OBJECT_CD, "<init>", MethodTypeDesc.of(CD_void));
                cb.return_();
            });
            clb.withMethod("sum", MethodTypeDesc.of(CD_void, intArr),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_VARARGS, mb -> {
                        mb.with(RuntimeVisibleAnnotationsAttribute.of(logAnn));
                        mb.withCode(CodeBuilder::return_);
                    });
        });
        Path classFile = tempDir.resolve("com/example/IntVa.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);

        runWeaver(tempDir);

        try (var cl = new URLClassLoader(new URL[]{tempDir.toUri().toURL()})) {
            var klass = cl.loadClass("com.example.IntVa");
            java.lang.reflect.Method m = java.util.Arrays.stream(klass.getDeclaredMethods())
                    .filter(mm -> mm.getName().startsWith("lambda$logweaver$sum$"))
                    .findFirst()
                    .orElseThrow();
            m.setAccessible(true);

            assertEquals("IntVa.sum(1, 2, 3)",
                    m.invoke(null, (Object) new int[]{1, 2, 3}));
        }
    }

    @Test
    void varargsTrailingPositionAfterRegularParam(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.Mixed");
        Annotation logAnn = Annotation.of(LOG_CD);

        ClassDesc strArr = STRING_CD.arrayType();
        byte[] bytes = ClassFile.of().build(cd, clb -> {
            clb.withSuperclass(OBJECT_CD);
            clb.withMethodBody("<init>", MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(OBJECT_CD, "<init>", MethodTypeDesc.of(CD_void));
                cb.return_();
            });
            clb.withMethod("log", MethodTypeDesc.of(CD_void, CD_int, strArr),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_VARARGS, mb -> {
                        mb.with(RuntimeVisibleAnnotationsAttribute.of(logAnn));
                        mb.withCode(CodeBuilder::return_);
                    });
        });
        Path classFile = tempDir.resolve("com/example/Mixed.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);

        runWeaver(tempDir);

        try (var cl = new URLClassLoader(new URL[]{tempDir.toUri().toURL()})) {
            var klass = cl.loadClass("com.example.Mixed");
            java.lang.reflect.Method m = java.util.Arrays.stream(klass.getDeclaredMethods())
                    .filter(mm -> mm.getName().startsWith("lambda$logweaver$log$"))
                    .findFirst()
                    .orElseThrow();
            m.setAccessible(true);

            assertEquals("Mixed.log(7, a, b)",
                    m.invoke(null, 7, new String[]{"a", "b"}));
            // Empty varargs leaves a trailing ", " — minor cosmetic trade-off.
            assertEquals("Mixed.log(7, )",
                    m.invoke(null, 7, new String[]{}));
        }
    }

    @Test
    void nonVarargsClassesGetNoVaHelper(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.Plain");
        Annotation logAnn = Annotation.of(LOG_CD);

        Path classFile = writeFixture(tempDir, cd,
                "greet", MethodTypeDesc.of(CD_void, STRING_CD), logAnn);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));
        assertFalse(cm.methods().stream()
                .anyMatch(m -> m.methodName().stringValue().equals(LogWeaverCore.VA_HELPER_NAME)));
    }

    // ── Idempotency ──────────────────────────────────────────────────────────

    @Test
    void rerunIsIdempotent(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.Rerun");
        Annotation logAnn = Annotation.of(LOG_CD);

        Path classFile = writeFixture(tempDir, cd,
                "greet", MethodTypeDesc.of(CD_void, STRING_CD), logAnn);

        runWeaver(tempDir);
        byte[] afterFirst = Files.readAllBytes(classFile);

        runWeaver(tempDir);
        byte[] afterSecond = Files.readAllBytes(classFile);

        assertArrayEquals(afterFirst, afterSecond,
                "a second weaver pass must not change the bytecode");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Path writeFixture(Path baseDir, ClassDesc thisClass, String methodName,
                                     MethodTypeDesc methodType, Annotation logAnnotation) throws Exception {

        byte[] bytes = ClassFile.of().build(thisClass, clb -> {
            clb.withSuperclass(OBJECT_CD);
            clb.withMethodBody("<init>", MethodTypeDesc.of(CD_void),
                    ClassFile.ACC_PUBLIC, cb -> {
                        cb.aload(0);
                        cb.invokespecial(OBJECT_CD, "<init>", MethodTypeDesc.of(CD_void));
                        cb.return_();
                    });
            clb.withMethod(methodName, methodType, ClassFile.ACC_PUBLIC, mb -> {
                if (logAnnotation != null) {
                    mb.with(RuntimeVisibleAnnotationsAttribute.of(logAnnotation));
                }
                mb.withCode(CodeBuilder::return_);
            });
        });

        Path classFile = classFilePath(baseDir, thisClass);
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);
        return classFile;
    }

    private static Path writeClassWithLogAll(Path baseDir, ClassDesc thisClass,
                                             Annotation logAllAnnotation,
                                             java.util.function.Consumer<ClassBuilder> methods) throws Exception {
        byte[] bytes = ClassFile.of().build(thisClass, clb -> {
            clb.withSuperclass(OBJECT_CD);
            clb.with(RuntimeVisibleAnnotationsAttribute.of(logAllAnnotation));
            clb.withMethodBody("<init>", MethodTypeDesc.of(CD_void),
                    ClassFile.ACC_PUBLIC, cb -> {
                        cb.aload(0);
                        cb.invokespecial(OBJECT_CD, "<init>", MethodTypeDesc.of(CD_void));
                        cb.return_();
                    });
            methods.accept(clb);
        });

        Path classFile = classFilePath(baseDir, thisClass);
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);
        return classFile;
    }

    private static Annotation logAllAnnotation(int modifiers, String level, String pattern) {
        return Annotation.of(LOG_ALL_CD,
                AnnotationElement.of("modifiers", AnnotationValue.ofInt(modifiers)),
                AnnotationElement.of("level", AnnotationValue.ofEnum(LEVEL_CD, level)),
                AnnotationElement.ofString("methodPattern", pattern));
    }

    private static Path classFilePath(Path baseDir, ClassDesc thisClass) {
        String internal = thisClass.descriptorString();
        internal = internal.substring(1, internal.length() - 1);
        return baseDir.resolve(internal + ".class");
    }

    /** New entry point: drive the transformation via LogWeaverCore directly. */
    private static void runWeaver(Path classesDir) throws Exception {
        LogWeaverCore.WeaveStats stats = LogWeaverCore.weaveDirectory(classesDir);
        if (!stats.isClean()) {
            var first = stats.errors().entrySet().iterator().next();
            throw new AssertionError("weave failed for " + first.getKey(), first.getValue());
        }
    }

    private static MethodModel findMethod(ClassModel cm, String name) {
        return cm.methods().stream()
                .filter(m -> m.methodName().stringValue().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("method not found: " + name));
    }

    private static MethodModel findMethodByPrefix(ClassModel cm, String prefix) {
        return cm.methods().stream()
                .filter(m -> m.methodName().stringValue().startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no method whose name starts with: " + prefix));
    }

    private static boolean hasMethodWithPrefix(ClassModel cm, String prefix) {
        return cm.methods().stream()
                .anyMatch(m -> m.methodName().stringValue().startsWith(prefix));
    }

    private static FieldModel findField(ClassModel cm, String name) {
        return cm.fields().stream()
                .filter(f -> f.fieldName().stringValue().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("field not found: " + name));
    }

    private static boolean hasAnnotation(MethodModel mm, ClassDesc annotationType) {
        return mm.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(attr -> attr.annotations().stream()
                        .anyMatch(a -> a.classSymbol().equals(annotationType)))
                .orElse(false);
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

    private static List<String> ldcStringConstants(MethodModel mm) {
        CodeAttribute code = mm.findAttribute(Attributes.code()).orElseThrow();
        List<String> result = new ArrayList<>();
        for (CodeElement e : code) {
            if (e instanceof ConstantInstruction ci) {
                ConstantDesc cd = ci.constantValue();
                if (cd instanceof String s) result.add(s);
            }
        }
        return result;
    }

    private static List<String> fieldRefNames(MethodModel mm, ClassDesc owner) {
        CodeAttribute code = mm.findAttribute(Attributes.code()).orElseThrow();
        List<String> result = new ArrayList<>();
        for (CodeElement e : code) {
            if (e instanceof FieldInstruction fi && fi.owner().asSymbol().equals(owner)) {
                result.add(fi.name().stringValue());
            }
        }
        return result;
    }
}

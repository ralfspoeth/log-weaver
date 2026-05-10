package io.github.ralfspoeth.log.weaver;

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

        // 1) The synthetic helper method was added (name now includes a descriptor hash)
        MethodModel synthetic = findMethodByPrefix(cm, "lambda$logweaver$greet$");
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;",
                synthetic.methodType().stringValue(),
                "helper must be (String)String");
        assertTrue(synthetic.flags().has(AccessFlag.STATIC), "helper must be static");
        assertTrue(synthetic.flags().has(AccessFlag.PRIVATE), "helper must be private");
        assertTrue(synthetic.flags().has(AccessFlag.SYNTHETIC), "helper must be synthetic");

        // 2) The synthetic LOGGER field is present and is private static final
        FieldModel loggerField = findField(cm, LogWeaver.LOGGER_FIELD_NAME);
        assertTrue(loggerField.flags().has(AccessFlag.STATIC), "LOGGER must be static");
        assertTrue(loggerField.flags().has(AccessFlag.FINAL), "LOGGER must be final");
        assertTrue(loggerField.flags().has(AccessFlag.PRIVATE), "LOGGER must be private");

        // 3) <clinit> exists and initializes LOGGER via System.getLogger + putstatic
        MethodModel clinit = findMethod(cm, "<clinit>");
        List<String> clinitOps = opcodes(clinit);
        assertTrue(clinitOps.contains("INVOKESTATIC"),
                "<clinit> must call System.getLogger");
        assertTrue(clinitOps.contains("PUTSTATIC"),
                "<clinit> must store the result into LOGGER");

        // 4) The original method's prologue uses GETSTATIC + INVOKEDYNAMIC + INVOKEINTERFACE
        List<String> ops = opcodes(findMethod(cm, "greet"));
        assertTrue(ops.contains("GETSTATIC"),
                "prologue must load LOGGER (and Level) via getstatic");
        assertTrue(ops.contains("INVOKEDYNAMIC"),
                "prologue must build the Supplier via invokedynamic");
        assertTrue(ops.contains("INVOKEINTERFACE"),
                "prologue must call Logger.log");

        // 5) Order: indy is part of the prologue, hence before RETURN
        int indyIdx = ops.indexOf("INVOKEDYNAMIC");
        int retIdx = ops.lastIndexOf("RETURN");
        assertTrue(indyIdx >= 0 && retIdx > indyIdx,
                "logging prologue must precede the original code");

        // 6) The @Log annotation has been stripped from the woven method
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

        assertArrayEquals(before, after,
                "a class without @Log must remain bytes-identical");
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
        // class Mine { @Log void hello(int a, String b) {} }
        ClassDesc mineCd = ClassDesc.of("Mine");
        Annotation logAnn = Annotation.of(LOG_CD);

        Path classFile = writeFixture(tempDir, mineCd,
                "hello", MethodTypeDesc.of(CD_void, CD_int, STRING_CD), logAnn);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));

        // Helper method: int gets boxed to Integer, String stays String,
        // return type is the formatted message (String).
        MethodModel synthetic = findMethodByPrefix(cm, "lambda$logweaver$hello$");
        assertEquals("(Ljava/lang/Integer;Ljava/lang/String;)Ljava/lang/String;",
                synthetic.methodType().stringValue(),
                "helper must be (Integer, String) -> String");
        assertTrue(synthetic.flags().has(AccessFlag.STATIC), "helper must be static");
        assertTrue(synthetic.flags().has(AccessFlag.PRIVATE), "helper must be private");
        assertTrue(synthetic.flags().has(AccessFlag.SYNTHETIC), "helper must be synthetic");

        // Original method's prologue uses GETSTATIC for both LOGGER and Level
        List<String> ops = opcodes(findMethod(cm, "hello"));
        assertTrue(ops.contains("GETSTATIC"),
                "prologue must load LOGGER and Level via getstatic");
        assertTrue(ops.contains("INVOKEDYNAMIC"),
                "prologue must build the Supplier via invokedynamic");
        assertTrue(ops.contains("INVOKEINTERFACE"),
                "prologue must call Logger.log");

        // Integer.valueOf(int) is the only INVOKESTATIC in the woven method.
        long invokeStaticCount = ops.stream().filter("INVOKESTATIC"::equals).count();
        assertTrue(invokeStaticCount >= 1,
                "expected at least Integer.valueOf, was: " + invokeStaticCount);

        // Order: GETSTATIC LOGGER -> GETSTATIC Level -> iload(int) -> Integer.valueOf
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

        assertNotNull(findMethodByPrefix(cm, "lambda$logweaver$foo$"),
                "@LogAll must weave foo");
        assertNotNull(findMethodByPrefix(cm, "lambda$logweaver$bar$"),
                "@LogAll must weave bar");
        assertFalse(hasMethodWithPrefix(cm, "lambda$logweaver$<init>"),
                "<init> must not be woven");

        // The class still has its @LogAll – we don't strip class-level scope
        // annotations; idempotency is provided by the synthetic-helper guard.
        assertNotNull(findField(cm, LogWeaver.LOGGER_FIELD_NAME),
                "LOGGER field must exist");
        assertNotNull(findMethod(cm, "<clinit>"),
                "<clinit> must exist to initialize LOGGER");
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

        assertNotNull(findMethodByPrefix(cm, "lambda$logweaver$getName$"),
                "methodPattern \"get.*\" must match getName");
        assertFalse(hasMethodWithPrefix(cm, "lambda$logweaver$setName$"),
                "methodPattern \"get.*\" must not match setName");
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

        assertNotNull(findMethodByPrefix(cm, "lambda$logweaver$publicMethod$"),
                "modifiers=PUBLIC must match publicMethod");
        assertFalse(hasMethodWithPrefix(cm, "lambda$logweaver$privateMethod$"),
                "modifiers=PUBLIC must not match privateMethod");
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

        // The prologue references Level.ERROR (method-level @Log), not Level.INFO (@LogAll).
        // The entry message itself is always the synthesized form, so we only
        // assert on the level here.
        MethodModel foo = findMethod(cm, "foo");
        List<String> levelRefs = fieldRefNames(foo, LEVEL_CD);
        assertTrue(levelRefs.contains("ERROR"),
                "method-level @Log level (ERROR) must win over @LogAll's INFO");
        assertFalse(levelRefs.contains("INFO"),
                "the @LogAll INFO level must not be referenced in the prologue");

        // The synthetic helper holds the synthesized message regardless of
        // which annotation drove the weave.
        MethodModel helper = findMethodByPrefix(cm, "lambda$logweaver$foo$");
        List<String> stringConstants = ldcStringConstants(helper);
        assertTrue(stringConstants.contains("Override.foo(%s)"),
                "entry helper must use the synthesized message");
    }

    @Test
    void constructorAndStaticInitNotWoven(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.NoCtor");
        Annotation logAllAnn = logAllAnnotation(0, "INFO", ".*");

        Path classFile = writeClassWithLogAll(tempDir, cd, logAllAnn, clb -> {
            // existing <clinit> – the weaver should prepend LOGGER init,
            // but never produce a "lambda$logweaver$<clinit>" helper.
            clb.withMethod("<clinit>", MethodTypeDesc.of(CD_void), ClassFile.ACC_STATIC,
                    mb -> mb.withCode(CodeBuilder::return_));
            clb.withMethod("doStuff", MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC,
                    mb -> mb.withCode(CodeBuilder::return_));
        });

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));

        assertNotNull(findMethodByPrefix(cm, "lambda$logweaver$doStuff$"),
                "regular method must be woven");
        assertFalse(hasMethodWithPrefix(cm, "lambda$logweaver$<init>"),
                "<init> must not be woven");
        assertFalse(hasMethodWithPrefix(cm, "lambda$logweaver$<clinit>"),
                "<clinit> must not be woven");

        // The existing <clinit> now has the LOGGER initialization prepended.
        List<String> clinitOps = opcodes(findMethod(cm, "<clinit>"));
        assertTrue(clinitOps.contains("INVOKESTATIC"),
                "existing <clinit> must have System.getLogger prepended");
        assertTrue(clinitOps.contains("PUTSTATIC"),
                "existing <clinit> must store into LOGGER");
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

        // Single helper – entry vs. return is mutually exclusive. With
        // logReturn=true on a void method the helper captures only the
        // (String) parameter, no extra slot for the return value.
        MethodModel helper = findMethodByPrefix(cm, "lambda$logweaver$doIt$");
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;",
                helper.methodType().stringValue(),
                "void return helper must capture only the parameters");
        assertTrue(helper.flags().has(AccessFlag.STATIC));
        assertTrue(helper.flags().has(AccessFlag.PRIVATE));
        assertTrue(helper.flags().has(AccessFlag.SYNTHETIC));

        // Exactly ONE supplier-based log call (the return log) and exactly TWO
        // Logger.log invocations: the supplier-based return log + the always-on
        // throwable log in the catch handler.
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
            // public int five() { return 5; }
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

        // Single helper, signature (Integer)String — int boxed return value,
        // no parameters to capture for five().
        MethodModel helper = findMethodByPrefix(cm, "lambda$logweaver$five$");
        assertEquals("(Ljava/lang/Integer;)Ljava/lang/String;",
                helper.methodType().stringValue(),
                "non-void return helper must capture the boxed result");

        // Round-trip through the woven class: the original return value (5) must survive.
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
            // public void throwIt() { throw new RuntimeException("oops"); }
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

        // The exception table contains a Throwable handler covering the body.
        ClassDesc throwableCd = ClassDesc.of("java.lang.Throwable");
        MethodModel throwIt = findMethod(cm, "throwIt");
        CodeAttribute code = throwIt.findAttribute(Attributes.code()).orElseThrow();
        boolean hasThrowableHandler = code.exceptionHandlers().stream()
                .anyMatch(eh -> eh.catchType()
                        .map(ce -> ce.asSymbol().equals(throwableCd))
                        .orElse(false));
        assertTrue(hasThrowableHandler,
                "exceptionLevel must add a Throwable catch around the body");

        // Throwable.getMessage is invoked from the handler.
        List<String> ops = opcodes(throwIt);
        assertTrue(ops.contains("INVOKEVIRTUAL"),
                "handler must call Throwable.getMessage");
        assertTrue(ops.contains("ATHROW"),
                "handler must rethrow the original throwable");

        // Runtime: the original RuntimeException must propagate after logging.
        try (var cl = new URLClassLoader(new URL[]{tempDir.toUri().toURL()})) {
            var klass = cl.loadClass("com.example.Throwy");
            var instance = klass.getDeclaredConstructor().newInstance();
            var m = klass.getMethod("throwIt");
            var ite = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> m.invoke(instance));
            assertInstanceOf(RuntimeException.class, ite.getCause());
            assertEquals("oops", ite.getCause().getMessage(),
                    "original exception message must be preserved");
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

        // Single helper for the return log – entry vs. return is mutually exclusive.
        MethodModel helper = findMethodByPrefix(cm, "lambda$logweaver$noop$");
        assertEquals("()Ljava/lang/String;", helper.methodType().stringValue(),
                "logReturn=true on void noop() captures nothing");

        // Always-on Throwable handler installed at the explicit ERROR level.
        ClassDesc throwableCd = ClassDesc.of("java.lang.Throwable");
        MethodModel noop = findMethod(cm, "noop");
        CodeAttribute code = noop.findAttribute(Attributes.code()).orElseThrow();
        assertTrue(code.exceptionHandlers().stream()
                .anyMatch(eh -> eh.catchType()
                        .map(ce -> ce.asSymbol().equals(throwableCd))
                        .orElse(false)));
        assertTrue(fieldRefNames(noop, LEVEL_CD).contains("ERROR"),
                "explicit exceptionLevel must override the WARNING default");
    }

    @Test
    void defaultsEmitEntryLogPlusExceptionHandlerAtWarning(@TempDir Path tempDir) throws Exception {
        ClassDesc cd = ClassDesc.of("com.example.PlainLog");
        // log-api 0.5 contract for a bare @Log:
        //   logReturn      → false   → entry log only (no return capture)
        //   exceptionLevel → WARNING → Throwable handler installed
        Annotation logAnn = Annotation.of(LOG_CD);

        Path classFile = writeFixture(tempDir, cd,
                "greet", MethodTypeDesc.of(CD_void, STRING_CD), logAnn);

        runWeaver(tempDir);

        ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classFile));

        // The single helper has the entry-form shape: parameters only, no return capture.
        MethodModel helper = findMethodByPrefix(cm, "lambda$logweaver$greet$");
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;",
                helper.methodType().stringValue(),
                "logReturn=false (default) must produce an entry-form helper (params only)");

        // Exactly one supplier-based log call (entry) + one throwable log call (handler).
        MethodModel greet = findMethod(cm, "greet");
        List<String> ops = opcodes(greet);
        assertEquals(1, ops.stream().filter("INVOKEDYNAMIC"::equals).count(),
                "logReturn=false emits the entry log only – no return log");
        assertEquals(2, ops.stream().filter("INVOKEINTERFACE"::equals).count(),
                "entry prologue + always-on Throwable handler each call Logger.log");

        // exceptionLevel defaults to WARNING → Throwable handler is installed.
        ClassDesc throwableCd = ClassDesc.of("java.lang.Throwable");
        CodeAttribute code = greet.findAttribute(Attributes.code()).orElseThrow();
        assertTrue(code.exceptionHandlers().stream()
                        .anyMatch(eh -> eh.catchType()
                                .map(ce -> ce.asSymbol().equals(throwableCd))
                                .orElse(false)),
                "default exceptionLevel (WARNING) must add a Throwable handler");

        // …and the handler references Level.WARNING.
        assertTrue(fieldRefNames(greet, LEVEL_CD).contains("WARNING"),
                "default exceptionLevel must be WARNING");
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
            // public void greet(String... names) {}
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

        // The per-class strip helper is present.
        MethodModel vaHelper = findMethod(cm, LogWeaver.VA_HELPER_NAME);
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;",
                vaHelper.methodType().stringValue(),
                "$logweaver$va must be (String)String");

        // The format helper for greet keeps its array-typed parameter so that
        // Arrays.toString chooses the right overload.
        MethodModel format = findMethodByPrefix(cm, "lambda$logweaver$greet$");
        assertEquals("([Ljava/lang/String;)Ljava/lang/String;",
                format.methodType().stringValue(),
                "varargs format helper must capture the raw array");

        // Drive the format helper directly via reflection and assert the
        // produced log message has elements joined, no brackets, no array hash.
        try (var cl = new URLClassLoader(new URL[]{tempDir.toUri().toURL()})) {
            var klass = cl.loadClass("com.example.Va");
            java.lang.reflect.Method m = java.util.Arrays.stream(klass.getDeclaredMethods())
                    .filter(mm -> mm.getName().startsWith("lambda$logweaver$greet$"))
                    .findFirst()
                    .orElseThrow();
            m.setAccessible(true);

            assertEquals("Va.greet(alice, bob)",
                    m.invoke(null, (Object) new String[]{"alice", "bob"}),
                    "two-element varargs must render as joined elements");
            assertEquals("Va.greet(alice)",
                    m.invoke(null, (Object) new String[]{"alice"}),
                    "single-element varargs must render as the element alone");
            assertEquals("Va.greet()",
                    m.invoke(null, (Object) new String[]{}),
                    "empty varargs must render as an empty argument list");
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
            // public void sum(int... xs) {}
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
                    m.invoke(null, (Object) new int[]{1, 2, 3}),
                    "primitive varargs must use Arrays.toString(int[])");
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
            // public void log(int prefix, String... rest) {}
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
                    m.invoke(null, 7, new String[]{"a", "b"}),
                    "regular param + non-empty varargs renders both inline");
            // Empty varargs leaves a trailing ", " — accepted as a minor cosmetic
            // trade-off for the simpler single-helper design.
            assertEquals("Mixed.log(7, )",
                    m.invoke(null, 7, new String[]{}),
                    "empty varargs after a regular param leaves a trailing comma");
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
                        .anyMatch(m -> m.methodName().stringValue().equals(LogWeaver.VA_HELPER_NAME)),
                "$logweaver$va must only be added to classes that actually have varargs methods");
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

        Path classFile = classFilePath(baseDir, thisClass);
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);
        return classFile;
    }

    /** Build a class with a class-level {@code @LogAll}, a default constructor, and the methods added by {@code methods}. */
    private static Path writeClassWithLogAll(Path baseDir,
                                             ClassDesc thisClass,
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
        // Derive path from the internal class name, e.g. "com/example/Foo"
        String internal = thisClass.descriptorString();
        internal = internal.substring(1, internal.length() - 1); // L...; -> ...
        return baseDir.resolve(internal + ".class");
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

    /** Returns every string constant loaded via LDC inside the method's body. */
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

    /** Returns the field names referenced (via getstatic / putstatic / etc.) on the given owner. */
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

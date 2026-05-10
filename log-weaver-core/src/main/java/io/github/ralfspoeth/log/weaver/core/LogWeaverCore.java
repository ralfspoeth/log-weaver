package io.github.ralfspoeth.log.weaver.core;

import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.*;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.lang.constant.ConstantDescs.*;
import static java.util.function.Predicate.not;

/**
 * Bytecode transformation engine. Public entry points:
 * <ul>
 *   <li>{@link #transformClass(byte[], Scopes)} — transform one class' bytes.</li>
 *   <li>{@link #scanScopes(Path)} — pre-scan a classes directory for
 *       {@code module-info.class} / {@code package-info.class} configurations.</li>
 *   <li>{@link #readScopeConfig(byte[])} — read {@code @LogAll} from a single
 *       parsed info-class.</li>
 *   <li>{@link #weaveDirectory(Path)} — convenience for build-tool plugins:
 *       walks a directory, transforms each {@code *.class}, writes back in
 *       place, and returns a {@link WeaveStats}.</li>
 * </ul>
 *
 * <p>This class has no Maven, Gradle, or agent dependencies and can be embedded
 * in any of those wrappers.</p>
 */
public final class LogWeaverCore {

    private LogWeaverCore() {}

    /** Synthetic per-class field that holds the {@link System.Logger} instance. */
    public static final String LOGGER_FIELD_NAME = "$logweaver$LOGGER";

    /**
     * Synthetic per-class helper that strips the leading {@code '['} and
     * trailing {@code ']'} from {@code Arrays.toString(...)} output, so a
     * varargs argument shows up in the log message as comma-separated
     * elements rather than as the raw array's identity hash.
     */
    public static final String VA_HELPER_NAME = "$logweaver$va";

    // ── Constants ────────────────────────────────────────────────────────────
    // CD_String, CD_Object, CD_Boolean etc. come from ConstantDescs (static import).
    private static final ClassDesc CD_Logger = ClassDesc.of("java.lang.System$Logger");
    private static final ClassDesc CD_Level = ClassDesc.of("java.lang.System$Logger$Level");
    private static final ClassDesc CD_System = ClassDesc.of("java.lang.System");
    private static final ClassDesc CD_Supplier = ClassDesc.of("java.util.function.Supplier");
    private static final ClassDesc CD_ObjectArray = CD_Object.arrayType();
    private static final ClassDesc CD_Throwable = ClassDesc.of("java.lang.Throwable");
    private static final ClassDesc CD_Arrays = ClassDesc.of("java.util.Arrays");
    private static final ClassDesc CD_Log = ClassDesc.of("io.github.ralfspoeth.log.api.Log");
    private static final ClassDesc CD_LogAll = ClassDesc.of("io.github.ralfspoeth.log.api.LogAll");

    private static final DirectMethodHandleDesc LMF_BOOTSTRAP = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC,
            ClassDesc.of("java.lang.invoke.LambdaMetafactory"),
            "metafactory",
            MethodTypeDesc.ofDescriptor("""
                    (Ljava/lang/invoke/MethodHandles$Lookup;\
                    Ljava/lang/String;\
                    Ljava/lang/invoke/MethodType;\
                    Ljava/lang/invoke/MethodType;\
                    Ljava/lang/invoke/MethodHandle;\
                    Ljava/lang/invoke/MethodType;\
                    )Ljava/lang/invoke/CallSite;"""
            )
    );

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Transform the given class file bytes.
     *
     * @return the original array reference iff no transformation was needed
     *         (no {@code @Log} on any method, no {@code @LogAll} matching).
     *         Otherwise a fresh byte array.
     */
    public static byte[] transformClass(byte[] classBytes, Scopes scopes) {
        return tryTransform(classBytes, scopes);
    }

    /**
     * Walks the given directory and transforms each {@code *.class} file in
     * place. Errors are collected per-file and returned in {@link WeaveStats};
     * I/O errors at the directory level are thrown.
     */
    public static WeaveStats weaveDirectory(Path classesDir) throws IOException {
        if (!Files.isDirectory(classesDir)) return new WeaveStats(0, 0, Map.of());

        Scopes scopes = scanScopes(classesDir);

        int[] stats = {0, 0}; // [checked, transformed]
        Map<Path, Throwable> errors = new LinkedHashMap<>();

        try (var stream = Files.walk(classesDir)) {
            var classMatcher = classesDir.getFileSystem().getPathMatcher("glob:*.class");
            var infoMatcher = classesDir.getFileSystem().getPathMatcher("glob:*-info.class");
            stream.filter(p -> classMatcher.matches(p.getFileName()))
                    .filter(not(p -> infoMatcher.matches(p.getFileName())))
                    .forEach(p -> {
                        stats[0]++;
                        try {
                            byte[] original = Files.readAllBytes(p);
                            byte[] transformed = tryTransform(original, scopes);
                            if (transformed != original) {
                                Files.write(p, transformed);
                                stats[1]++;
                            }
                        } catch (Throwable t) {
                            errors.put(p, t);
                        }
                    });
        }

        return new WeaveStats(stats[0], stats[1], errors);
    }

    /**
     * First pass: reads {@code module-info.class} and every {@code package-info.class}
     * file in the tree and collects their respective {@code @LogAll} configurations.
     */
    public static Scopes scanScopes(Path root) throws IOException {
        List<Path> infoFiles;
        try (var stream = Files.walk(root)) {
            infoFiles = stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.equals("module-info.class") || n.equals("package-info.class");
            }).toList();
        }

        Optional<LogAllConfig> moduleConfig = Optional.empty();
        Map<String, LogAllConfig> packageConfigs = new HashMap<>();

        for (Path info : infoFiles) {
            ClassModel cm = ClassFile.of().parse(Files.readAllBytes(info));
            Optional<LogAllConfig> cfg = readLogAllConfigFromClass(cm);
            if (cfg.isEmpty()) continue;

            if (info.getFileName().toString().equals("module-info.class")) {
                moduleConfig = cfg;
            } else {
                packageConfigs.put(cm.thisClass().asSymbol().packageName(), cfg.get());
            }
        }
        return new Scopes(moduleConfig, packageConfigs);
    }

    /**
     * Reads the {@code @LogAll} configuration of a parsed
     * {@code module-info.class} or {@code package-info.class} from its raw bytes.
     */
    public static Optional<LogAllConfig> readScopeConfig(byte[] infoClassBytes) {
        ClassModel cm = ClassFile.of().parse(infoClassBytes);
        return readLogAllConfigFromClass(cm);
    }

    /** Result of {@link #weaveDirectory(Path)}: counts plus per-file errors. */
    public record WeaveStats(int checked, int transformed, Map<Path, Throwable> errors) {
        public WeaveStats {
            errors = Map.copyOf(errors);
        }
        public boolean isClean() { return errors.isEmpty(); }
    }

    // ── Transformation ───────────────────────────────────────────────────────
    private static byte[] tryTransform(byte[] original, Scopes scopes) {
        ClassFile cf = ClassFile.of();
        ClassModel cm = cf.parse(original);

        ClassDesc owner = cm.thisClass().asSymbol();

        // Resolve the effective @LogAll for this class: type > package > module.
        Optional<LogAllConfig> effectiveAll = readLogAllConfigFromClass(cm)
                .or(() -> Optional.ofNullable(scopes.byPackage().get(owner.packageName())))
                .or(scopes::module);

        // A method counts as "already woven" iff its synthetic helper is
        // present. The helper name is deterministic per (method name,
        // descriptor), so a re-run on already-woven bytecode safely skips it.
        Set<String> existingMethodNames = cm.methods().stream().map(m -> m.methodName().stringValue()).collect(Collectors.toUnmodifiableSet());
        Predicate<MethodModel> notYetWoven = mm -> !existingMethodNames.contains(syntheticName(mm));

        boolean anyLog = cm.methods().stream().filter(notYetWoven).anyMatch(LogWeaverCore::hasLogAnnotation);
        boolean anyLogAll = effectiveAll.isPresent() && cm.methods().stream().filter(notYetWoven).anyMatch(effectiveAll.get()::matches);

        if (!anyLog && !anyLogAll) return original;

        boolean hasClinit = cm.methods().stream().anyMatch(m -> m.methodName().stringValue().equals("<clinit>"));
        boolean hasLoggerField = cm.fields().stream().anyMatch(f -> f.fieldName().stringValue().equals(LOGGER_FIELD_NAME));
        boolean hasVaHelper = cm.methods().stream().anyMatch(m -> m.methodName().stringValue().equals(VA_HELPER_NAME));
        boolean anyVarargsToWeave = cm.methods().stream()
                .filter(notYetWoven)
                .filter(mm -> mm.flags().has(AccessFlag.VARARGS))
                .anyMatch(mm -> hasLogAnnotation(mm)
                        || (effectiveAll.isPresent() && effectiveAll.get().matches(mm)));
        boolean needsVaHelper = anyVarargsToWeave && !hasVaHelper;
        String loggerName = (owner.packageName().isEmpty() ? "" : owner.packageName() + ".") + owner.displayName();

        // ClassBuilder additions outside of an existing element's iteration are
        // legal at any time, but we only want to perform them once. The flag
        // ensures that – it fires on the very first element visited.
        boolean[] firstCall = {true};

        return cf.transformClass(cm, (clb, element) -> {
            if (firstCall[0]) {
                firstCall[0] = false;
                if (!hasLoggerField) {
                    clb.withField(LOGGER_FIELD_NAME, CD_Logger, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC);
                }
                if (!hasClinit) {
                    // Class has no <clinit> yet – add one that just initializes LOGGER.
                    clb.withMethod("<clinit>", MethodTypeDesc.of(CD_void), ClassFile.ACC_STATIC, mb -> mb.withCode(cb -> {
                        emitLoggerInit(cb, owner, loggerName);
                        cb.return_();
                    }));
                }
                if (needsVaHelper) {
                    emitVaHelper(clb);
                }
            }

            if (element instanceof MethodModel mm) {
                String name = mm.methodName().stringValue();
                if (name.equals("<clinit>")) {
                    // Existing <clinit>: prepend LOGGER initialization to its body.
                    clb.transformMethod(mm, (mb, mElement) -> {
                        if (mElement instanceof CodeModel code) {
                            mb.withCode(cb -> {
                                emitLoggerInit(cb, owner, loggerName);
                                code.forEach(cb);
                            });
                        } else {
                            mb.with(mElement);
                        }
                    });
                } else if (notYetWoven.test(mm)) {
                    resolveLogInfo(mm, effectiveAll).ifPresentOrElse(info -> weaveMethod(clb, mm, info, owner), () -> clb.with(mm));
                } else {
                    // Already-woven method: pass through untouched.
                    clb.with(mm);
                }
            } else {
                clb.with(element);
            }
        });
    }

    /** Emits {@code LOGGER = System.getLogger(<loggerName>);} into the given code builder. */
    private static void emitLoggerInit(CodeBuilder cb, ClassDesc owner, String loggerName) {
        cb.ldc(loggerName);
        cb.invokestatic(CD_System, "getLogger", MethodTypeDesc.of(CD_Logger, CD_String));
        cb.putstatic(owner, LOGGER_FIELD_NAME, CD_Logger);
    }

    /**
     * Determines the effective {@link LogInfo} for a method by the
     * "most-specific wins" rule: method-level {@code @Log} beats everything;
     * otherwise the effective {@code @LogAll} (type → package → module)
     * applies, if it matches; otherwise no weaving.
     */
    private static Optional<LogInfo> resolveLogInfo(MethodModel mm, Optional<LogAllConfig> effectiveAll) {
        Optional<LogInfo> methodLog = readLogAnnotation(mm);
        if (methodLog.isPresent()) return methodLog;

        if (effectiveAll.isPresent() && effectiveAll.get().matches(mm)) {
            return Optional.of(new LogInfo(effectiveAll.get().levelName(), false,
                    LOG_DEFAULTS.exceptionLevelName()));
        }
        return Optional.empty();
    }

    /**
     * Rewrites the annotated method with exactly one supplier-based log call
     * (entry-only or return-only, depending on {@code logReturn}) plus an
     * always-on {@link Throwable} catch that logs and rethrows. See the
     * project README for the contract details.
     */
    private static void weaveMethod(ClassBuilder clb, MethodModel mm, LogInfo info, ClassDesc owner) {
        boolean isStatic = mm.flags().has(AccessFlag.STATIC);
        List<ParamSlot> params = ParamSlot.of(mm, isStatic);
        List<ClassDesc> implParams = params.stream().map(p -> p.isPrimitive() ? p.boxed() : p.type()).toList();

        ClassDesc returnType = mm.methodTypeSymbol().returnType();
        boolean isVoid = returnType.equals(CD_void);
        boolean logReturn = info.logReturn();

        int varargsSlot = mm.flags().has(AccessFlag.VARARGS) && !params.isEmpty()
                ? params.size() - 1
                : -1;

        String helperName = syntheticName(mm);
        List<ClassDesc> captureTypes;
        String message;
        if (logReturn) {
            captureTypes = new ArrayList<>(implParams);
            if (!isVoid) captureTypes.add(boxOf(returnType));
            message = synthesizeReturnMessage(owner, mm, isVoid);
        } else {
            captureTypes = implParams;
            message = synthesizeMessage(owner, mm);
        }
        MethodTypeDesc helperType = MethodTypeDesc.of(CD_String, captureTypes);
        DynamicCallSiteDesc indy = supplierIndy(owner, helperName, helperType, captureTypes);

        int origMaxLocals = mm.findAttribute(Attributes.code()).map(CodeAttribute::maxLocals).orElse(0);
        int resultSlots = (logReturn && !isVoid) ? slotWidth(returnType) : 0;
        int throwableSlot = origMaxLocals + resultSlots;

        clb.transformMethod(mm, (mb, mElement) -> {
            if (mElement instanceof CodeModel code) {
                mb.withCode(cb -> {
                    Label tryStart = cb.newLabel();
                    Label tryEnd = cb.newLabel();
                    Label handler = cb.newLabel();

                    cb.labelBinding(tryStart);

                    if (!logReturn) {
                        emitParamLog(cb, owner, info.levelName(), params, indy);
                    }

                    code.forEach(element -> {
                        if (logReturn && element instanceof ReturnInstruction) {
                            emitReturnLog(cb, owner, info.levelName(), params,
                                    returnType, isVoid, origMaxLocals, indy);
                        }
                        cb.with(element);
                    });

                    cb.labelBinding(tryEnd);

                    cb.labelBinding(handler);
                    cb.astore(throwableSlot);
                    cb.getstatic(owner, LOGGER_FIELD_NAME, CD_Logger);
                    cb.getstatic(CD_Level, info.exceptionLevelName(), CD_Level);
                    cb.aload(throwableSlot);
                    cb.invokevirtual(CD_Throwable, "getMessage", MethodTypeDesc.of(CD_String));
                    cb.aload(throwableSlot);
                    cb.invokeinterface(CD_Logger, "log", MethodTypeDesc.of(CD_void, CD_Level, CD_String, CD_Throwable));
                    cb.aload(throwableSlot);
                    cb.athrow();

                    cb.exceptionCatch(tryStart, tryEnd, handler, CD_Throwable);
                });
            } else if (mElement instanceof RuntimeVisibleAnnotationsAttribute attrs) {
                List<Annotation> kept = attrs.annotations().stream().filter(a -> !a.classSymbol().equals(CD_Log)).toList();
                if (!kept.isEmpty()) {
                    mb.with(RuntimeVisibleAnnotationsAttribute.of(kept));
                }
            } else {
                mb.with(mElement);
            }
        });

        emitFormatHelper(clb, helperName, helperType, message, owner, varargsSlot);
    }

    private static void emitParamLog(CodeBuilder cb,
                                     ClassDesc owner,
                                     String levelName,
                                     List<ParamSlot> params,
                                     DynamicCallSiteDesc indy) {
        cb.getstatic(owner, LOGGER_FIELD_NAME, CD_Logger);
        cb.getstatic(CD_Level, levelName, CD_Level);
        for (ParamSlot ps : params) {
            ps.load(cb);
            if (ps.isPrimitive()) {
                cb.invokestatic(ps.boxed(), "valueOf", MethodTypeDesc.of(ps.boxed(), ps.type()));
            }
        }
        cb.invokedynamic(indy);
        cb.invokeinterface(CD_Logger, "log", MethodTypeDesc.of(CD_void, CD_Level, CD_Supplier));
    }

    private static void emitReturnLog(CodeBuilder cb,
                                      ClassDesc owner,
                                      String levelName,
                                      List<ParamSlot> params,
                                      ClassDesc returnType,
                                      boolean isVoid,
                                      int resultSlot,
                                      DynamicCallSiteDesc returnIndy) {
        if (!isVoid) {
            storeAt(cb, returnType, resultSlot);
        }

        cb.getstatic(owner, LOGGER_FIELD_NAME, CD_Logger);
        cb.getstatic(CD_Level, levelName, CD_Level);

        for (ParamSlot ps : params) {
            ps.load(cb);
            if (ps.isPrimitive()) {
                cb.invokestatic(ps.boxed(), "valueOf", MethodTypeDesc.of(ps.boxed(), ps.type()));
            }
        }
        if (!isVoid) {
            loadAt(cb, returnType, resultSlot);
            if (returnType.isPrimitive()) {
                ClassDesc box = boxOf(returnType);
                cb.invokestatic(box, "valueOf", MethodTypeDesc.of(box, returnType));
            }
        }

        cb.invokedynamic(returnIndy);
        cb.invokeinterface(CD_Logger, "log", MethodTypeDesc.of(CD_void, CD_Level, CD_Supplier));

        if (!isVoid) {
            loadAt(cb, returnType, resultSlot);
        }
    }

    private static DynamicCallSiteDesc supplierIndy(ClassDesc owner, String helperName,
                                                    MethodTypeDesc helperType, List<ClassDesc> captureTypes) {
        DirectMethodHandleDesc handle = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC, owner, helperName, helperType);
        return DynamicCallSiteDesc.of(LMF_BOOTSTRAP, "get",
                MethodTypeDesc.of(CD_Supplier, captureTypes),
                MethodTypeDesc.of(CD_Object),
                handle,
                MethodTypeDesc.of(CD_String));
    }

    private static void emitFormatHelper(ClassBuilder clb, String name, MethodTypeDesc type,
                                         String message, ClassDesc owner, int varargsSlot) {
        int paramCount = type.parameterCount();
        clb.withMethod(name, type, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC, mb -> mb.withCode(cb -> {
            cb.ldc(message);
            cb.ldc(paramCount);
            cb.anewarray(CD_Object);
            for (int i = 0; i < paramCount; i++) {
                cb.dup();
                cb.ldc(i);
                cb.aload(i);
                if (i == varargsSlot) {
                    ClassDesc arrType = type.parameterType(i);
                    ClassDesc atsParamType = arrType.componentType().isPrimitive()
                            ? arrType
                            : CD_Object.arrayType();
                    cb.invokestatic(CD_Arrays, "toString",
                            MethodTypeDesc.of(CD_String, atsParamType));
                    cb.invokestatic(owner, VA_HELPER_NAME,
                            MethodTypeDesc.of(CD_String, CD_String));
                }
                cb.aastore();
            }
            cb.invokevirtual(CD_String, "formatted", MethodTypeDesc.of(CD_String, CD_ObjectArray));
            cb.areturn();
        }));
    }

    private static void emitVaHelper(ClassBuilder clb) {
        clb.withMethod(VA_HELPER_NAME,
                MethodTypeDesc.of(CD_String, CD_String),
                ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                mb -> mb.withCode(cb -> {
                    Label returnAsIs = cb.newLabel();

                    cb.aload(0);
                    cb.invokevirtual(CD_String, "length", MethodTypeDesc.of(CD_int));
                    cb.istore(1);

                    cb.iload(1);
                    cb.iconst_2();
                    cb.if_icmplt(returnAsIs);

                    cb.aload(0);
                    cb.iconst_0();
                    cb.invokevirtual(CD_String, "charAt", MethodTypeDesc.of(CD_char, CD_int));
                    cb.bipush((byte) '[');
                    cb.if_icmpne(returnAsIs);

                    cb.aload(0);
                    cb.iconst_1();
                    cb.iload(1);
                    cb.iconst_1();
                    cb.isub();
                    cb.invokevirtual(CD_String, "substring",
                            MethodTypeDesc.of(CD_String, CD_int, CD_int));
                    cb.areturn();

                    cb.labelBinding(returnAsIs);
                    cb.aload(0);
                    cb.areturn();
                }));
    }

    private static String syntheticName(MethodModel mm) {
        return "lambda$logweaver$" + mm.methodName().stringValue() + "$" + descHash(mm);
    }

    private static String descHash(MethodModel mm) {
        return Integer.toHexString(mm.methodType().stringValue().hashCode() & 0x7fffffff);
    }

    // ── Annotation reading ───────────────────────────────────────────────────
    private static boolean hasLogAnnotation(MethodModel m) {
        return m.findAttribute(Attributes.runtimeVisibleAnnotations()).map(attr -> attr.annotations().stream().anyMatch(a -> a.classSymbol().equals(CD_Log))).orElse(false);
    }

    private static Optional<LogInfo> readLogAnnotation(MethodModel m) {
        return m.findAttribute(Attributes.runtimeVisibleAnnotations()).flatMap(attr -> attr.annotations().stream().filter(a -> a.classSymbol().equals(CD_Log)).findFirst()).map(LogWeaverCore::extractLogInfo);
    }

    private static LogInfo extractLogInfo(Annotation ann) {
        String levelName = LOG_DEFAULTS.levelName();
        boolean logReturn = LOG_DEFAULTS.logReturn();
        String exceptionLevelName = LOG_DEFAULTS.exceptionLevelName();
        for (AnnotationElement el : ann.elements()) {
            switch (el.name().stringValue()) {
                case "level" -> {
                    if (el.value() instanceof AnnotationValue.OfEnum e) levelName = e.constantName().stringValue();
                }
                case "logReturn" -> {
                    if (el.value() instanceof AnnotationValue.OfBoolean b) logReturn = b.booleanValue();
                }
                case "exceptionLevel" -> {
                    if (el.value() instanceof AnnotationValue.OfEnum e) exceptionLevelName = e.constantName().stringValue();
                }
            }
        }
        return new LogInfo(levelName, logReturn, exceptionLevelName);
    }

    private static Optional<LogAllConfig> readLogAllConfigFromClass(ClassModel cm) {
        return cm.findAttribute(Attributes.runtimeVisibleAnnotations()).flatMap(attr -> attr.annotations().stream().filter(a -> a.classSymbol().equals(CD_LogAll)).findFirst()).map(LogWeaverCore::extractLogAllConfig);
    }

    private static LogAllConfig extractLogAllConfig(Annotation ann) {
        int modifiers = LOG_ALL_DEFAULTS.modifiers();
        String levelName = LOG_ALL_DEFAULTS.levelName();
        String methodPattern = LOG_ALL_DEFAULTS.methodPattern();
        for (AnnotationElement el : ann.elements()) {
            switch (el.name().stringValue()) {
                case "modifiers" -> {
                    if (el.value() instanceof AnnotationValue.OfInt i) modifiers = i.intValue();
                }
                case "level" -> {
                    if (el.value() instanceof AnnotationValue.OfEnum e) levelName = e.constantName().stringValue();
                }
                case "methodPattern" -> {
                    if (el.value() instanceof AnnotationValue.OfString s) methodPattern = s.stringValue();
                }
            }
        }
        return new LogAllConfig(modifiers, levelName, methodPattern);
    }

    // ── Reflectively loaded annotation defaults ──────────────────────────────
    // javac does NOT store default values at the annotation use site. So that a
    // bare @Log or @LogAll picks up the same defaults the annotation declares,
    // we read them once at class load via reflection. If that fails (e.g. log-api
    // not on the classpath), conservative hardcoded fallbacks apply.

    private static final LogInfo LOG_DEFAULTS = loadLogDefaults();
    private static final LogAllConfig LOG_ALL_DEFAULTS = loadLogAllDefaults();

    private static LogInfo loadLogDefaults() {
        String levelName = "INFO";
        boolean logReturn = false;
        // Fallback only kicks in if log-api is missing at plugin load time.
        // Mirrors log-api 0.5's @Log default for exceptionLevel.
        String exceptionLevelName = "WARNING";
        try {
            for (Method m : Class.forName("io.github.ralfspoeth.log.api.Log").getDeclaredMethods()) {
                Object dv = m.getDefaultValue();
                if (dv == null) continue;
                switch (m.getName()) {
                    case "level" -> {
                        if (dv instanceof Enum<?> e) levelName = e.name();
                    }
                    case "logReturn" -> {
                        if (dv instanceof Boolean b) logReturn = b;
                    }
                    case "exceptionLevel" -> {
                        if (dv instanceof Enum<?> e) exceptionLevelName = e.name();
                    }
                }
            }
        } catch (Throwable ignore) { /* fallbacks stand */ }
        return new LogInfo(levelName, logReturn, exceptionLevelName);
    }

    private static LogAllConfig loadLogAllDefaults() {
        int modifiers = 0;
        String levelName = "INFO";
        String methodPattern = ".*";
        try {
            for (Method m : Class.forName("io.github.ralfspoeth.log.api.LogAll").getDeclaredMethods()) {
                Object dv = m.getDefaultValue();
                if (dv == null) continue;
                switch (m.getName()) {
                    case "modifiers" -> {
                        if (dv instanceof Integer i) modifiers = i;
                    }
                    case "level" -> {
                        if (dv instanceof Enum<?> e) levelName = e.name();
                    }
                    case "methodPattern" -> {
                        if (dv instanceof String s) methodPattern = s;
                    }
                }
            }
        } catch (Throwable ignore) { /* fallbacks stand */ }
        return new LogAllConfig(modifiers, levelName, methodPattern);
    }

    // ── Synthesized messages ─────────────────────────────────────────────────

    private static String synthesizeMessage(ClassDesc owner, MethodModel mm) {
        int paramCount = mm.methodTypeSymbol().parameterCount();
        String args = String.join(", ", Collections.nCopies(paramCount, "%s"));
        return owner.displayName() + "." + mm.methodName().stringValue() + "(" + args + ")";
    }

    private static String synthesizeReturnMessage(ClassDesc owner, MethodModel mm, boolean isVoid) {
        int paramCount = mm.methodTypeSymbol().parameterCount();
        String args = String.join(", ", Collections.nCopies(paramCount, "%s"));
        String tail = isVoid ? "void" : "%s";
        return owner.displayName() + "." + mm.methodName().stringValue() + "(" + args + ") -> " + tail;
    }

    // ── Type/slot helpers ────────────────────────────────────────────────────

    private static int slotWidth(ClassDesc type) {
        return (type.equals(CD_long) || type.equals(CD_double)) ? 2 : 1;
    }

    private static ClassDesc boxOf(ClassDesc type) {
        if (type.equals(CD_boolean)) return CD_Boolean;
        if (type.equals(CD_byte)) return CD_Byte;
        if (type.equals(CD_char)) return CD_Character;
        if (type.equals(CD_short)) return CD_Short;
        if (type.equals(CD_int)) return CD_Integer;
        if (type.equals(CD_long)) return CD_Long;
        if (type.equals(CD_float)) return CD_Float;
        if (type.equals(CD_double)) return CD_Double;
        return type;
    }

    private static void storeAt(CodeBuilder cb, ClassDesc type, int slot) {
        if (type.equals(CD_long)) cb.lstore(slot);
        else if (type.equals(CD_double)) cb.dstore(slot);
        else if (type.equals(CD_float)) cb.fstore(slot);
        else if (type.equals(CD_int) || type.equals(CD_boolean) || type.equals(CD_byte) || type.equals(CD_char) || type.equals(CD_short))
            cb.istore(slot);
        else cb.astore(slot);
    }

    private static void loadAt(CodeBuilder cb, ClassDesc type, int slot) {
        if (type.equals(CD_long)) cb.lload(slot);
        else if (type.equals(CD_double)) cb.dload(slot);
        else if (type.equals(CD_float)) cb.fload(slot);
        else if (type.equals(CD_int) || type.equals(CD_boolean) || type.equals(CD_byte) || type.equals(CD_char) || type.equals(CD_short))
            cb.iload(slot);
        else cb.aload(slot);
    }

    /**
     * Carries the three configurable fields of an {@code @Log} annotation:
     * the log level (used for either the entry log or the return log,
     * depending on {@code logReturn}), the {@code logReturn} toggle, and the
     * exception-handler level. Exception logging is always installed; setting
     * {@code exceptionLevel} to {@code "OFF"} makes the JDK discard the log
     * call but does not change the bytecode shape.
     */
    record LogInfo(String levelName, boolean logReturn, String exceptionLevelName) {}

    record ParamSlot(int slot, ClassDesc type) {

        static List<ParamSlot> of(MethodModel m, boolean isStatic) {
            var result = new ArrayList<ParamSlot>();
            int slot = isStatic ? 0 : 1;
            for (ClassDesc p : m.methodTypeSymbol().parameterList()) {
                result.add(new ParamSlot(slot, p));
                slot += (p.equals(CD_long) || p.equals(CD_double)) ? 2 : 1;
            }
            return result;
        }

        void load(CodeBuilder cb) {
            if (type.equals(CD_long)) cb.lload(slot);
            else if (type.equals(CD_double)) cb.dload(slot);
            else if (type.equals(CD_float)) cb.fload(slot);
            else if (type.equals(CD_int) || type.equals(CD_boolean) || type.equals(CD_byte) || type.equals(CD_char) || type.equals(CD_short))
                cb.iload(slot);
            else cb.aload(slot);
        }

        boolean isPrimitive() {return type.isPrimitive();}

        ClassDesc boxed() {
            if (type.equals(CD_boolean)) return CD_Boolean;
            if (type.equals(CD_byte)) return CD_Byte;
            if (type.equals(CD_char)) return CD_Character;
            if (type.equals(CD_short)) return CD_Short;
            if (type.equals(CD_int)) return CD_Integer;
            if (type.equals(CD_long)) return CD_Long;
            if (type.equals(CD_float)) return CD_Float;
            if (type.equals(CD_double)) return CD_Double;
            throw new IllegalStateException("not a primitive type: " + type);
        }
    }
}

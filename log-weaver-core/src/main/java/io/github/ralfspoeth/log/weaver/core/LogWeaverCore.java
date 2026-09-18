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
     * Synthetic per-class helper that turns {@code Arrays.toString(...)} output
     * into the substring that belongs in the log message. The two-argument
     * signature {@code (String arraysToStringOutput, String prefix)} lets an
     * empty varargs disappear cleanly: {@code "[]"} maps to {@code ""}, so no
     * trailing comma is left behind when a varargs parameter follows regular
     * parameters. A non-empty {@code "[a, b, c]"} maps to
     * {@code prefix + "a, b, c"}, where {@code prefix} is {@code ", "} when
     * varargs follows other parameters and {@code ""} when varargs is the
     * only parameter.
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
        List<Path> classFiles;
        try (var stream = Files.walk(root)) {
            classFiles = stream.filter(p -> p.getFileName().toString().endsWith(".class")).toList();
        }

        Optional<LogAllConfig> moduleConfig = Optional.empty();
        Map<String, LogAllConfig> packageConfigs = new HashMap<>();
        // Every sealed type names its permitted subtypes; no permitted subtype
        // names the type that permits it. So the fact has to be gathered from the
        // whole tree here, where the tree is in hand, rather than asked of a class
        // that cannot answer it.
        Set<String> sealedPermits = new HashSet<>();

        for (Path file : classFiles) {
            ClassModel cm = ClassFile.of().parse(Files.readAllBytes(file));

            cm.findAttribute(Attributes.permittedSubclasses()).ifPresent(attr ->
                    attr.permittedSubclasses().forEach(
                            entry -> sealedPermits.add(entry.asSymbol().descriptorString())));

            String name = file.getFileName().toString();
            if (!name.equals("module-info.class") && !name.equals("package-info.class")) {
                continue;
            }
            Optional<LogAllConfig> cfg = readLogAllConfigFromClass(cm);
            if (cfg.isEmpty()) continue;

            if (name.equals("module-info.class")) {
                moduleConfig = cfg;
            } else {
                packageConfigs.put(cm.thisClass().asSymbol().packageName(), cfg.get());
            }
        }
        return new Scopes(moduleConfig, packageConfigs, sealedPermits);
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
        Optional<LogAllConfig> effectiveAll = effectiveLogAll(cm, owner, scopes);

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
                    clb.withField(LOGGER_FIELD_NAME, CD_Logger, loggerFieldFlags(cm));
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

    /**
     * The {@code @LogAll} that governs this class: type, then package, then
     * module - except that a scoped one does not reach the kinds of type listed
     * in {@link #sweptUpByScope(ClassModel, Scopes)}.
     * <p>
     * The distinction is the point. {@code @LogAll} written <em>on</em> a type is
     * a decision about that type, and is obeyed whatever kind it is; one
     * inherited from a package or a module is a blanket rule its author never
     * saw the members of, and a blanket rule should not sweep up the things it
     * cannot instrument sensibly. So a record that really is to be woven says so
     * itself, and the escape hatch costs one annotation.
     */
    private static Optional<LogAllConfig> effectiveLogAll(ClassModel cm, ClassDesc owner, Scopes scopes) {
        Optional<LogAllConfig> onTheType = readLogAllConfigFromClass(cm);
        if (onTheType.isPresent()) return onTheType;
        if (sweptUpByScope(cm, scopes)) return Optional.empty();
        return Optional.ofNullable(scopes.byPackage().get(owner.packageName())).or(scopes::module);
    }

    /**
     * Three kinds of type a package- or module-wide {@code @LogAll} should leave
     * alone.
     * <ul>
     *   <li><b>Interfaces.</b> Their methods are abstract, {@code default} or
     *       {@code static}; only the last two have a body to wrap, and weaving
     *       one means putting a logger field into an interface, where JVMS 4.5
     *       allows only {@code public static final}. That mismatch is how this
     *       exclusion came to be written: a {@code @LogAll} on a module produced
     *       {@code ClassFormatError: Illegal field modifiers ... 0x101A} - a
     *       private static final synthetic field, which is right in a class and
     *       unverifiable in an interface.</li>
     *   <li><b>Records.</b> Accessors, {@code equals}, {@code hashCode},
     *       {@code toString} and the canonical constructor are all generated, so
     *       weaving them logs the compiler's work rather than the author's. A
     *       codebase built out of records would drown in it, and an accessor
     *       called on every JMX poll would drown in it fastest.</li>
     *   <li><b>Package-private permitted subtypes of a sealed type.</b> A sealed
     *       hierarchy whose cases are hidden is one where the interface answers
     *       the question and the cases are how; instrumenting a case logs an
     *       implementation detail its own package deliberately kept to itself.</li>
     * </ul>
     * Everything else the weaver would rather not touch - {@code <init>},
     * {@code <clinit>}, abstract, native, bridge and synthetic methods - is
     * already refused per method by {@link LogAllConfig#matches(MethodModel)},
     * and {@code module-info} and {@code package-info} never reach here at all.
     */
    static boolean sweptUpByScope(ClassModel cm, Scopes scopes) {
        if (cm.flags().has(AccessFlag.INTERFACE)) {
            return true;
        }
        // There is no ACC_RECORD flag; the Record attribute is what says so.
        if (cm.findAttribute(Attributes.record()).isPresent()) {
            return true;
        }
        return !cm.flags().has(AccessFlag.PUBLIC)
                && scopes.sealedPermits().contains(cm.thisClass().asSymbol().descriptorString());
    }

    /**
     * Flags for the per-class logger field.
     * <p>
     * A field of an interface must be {@code public static final} and may not be
     * private (JVMS 4.5), so the flags that are right for a class produce an
     * unverifiable interface. Scoped {@code @LogAll} no longer reaches an
     * interface at all, but {@code @Log} on a {@code default} method and
     * {@code @LogAll} written on the interface itself both still do, and either
     * would otherwise fail at class load rather than here.
     */
    private static int loggerFieldFlags(ClassModel cm) {
        int visibility = cm.flags().has(AccessFlag.INTERFACE)
                ? ClassFile.ACC_PUBLIC
                : ClassFile.ACC_PRIVATE;
        return visibility | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC;
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
                    // Prefix ", " if this varargs slot follows regular parameters;
                    // "" if it is the only parameter. The helper drops the prefix
                    // when the varargs array is empty, so no trailing comma appears.
                    cb.ldc(varargsSlot > 0 ? ", " : "");
                    cb.invokestatic(owner, VA_HELPER_NAME,
                            MethodTypeDesc.of(CD_String, CD_String, CD_String));
                }
                cb.aastore();
            }
            cb.invokevirtual(CD_String, "formatted", MethodTypeDesc.of(CD_String, CD_ObjectArray));
            cb.areturn();
        }));
    }

    private static void emitVaHelper(ClassBuilder clb) {
        clb.withMethod(VA_HELPER_NAME,
                MethodTypeDesc.of(CD_String, CD_String, CD_String),
                ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                mb -> mb.withCode(cb -> {
                    // (String s, String prefix)
                    //   if s.length() < 2 || s.charAt(0) != '['  -> return s
                    //   if s equals "[]"                         -> return ""
                    //   else                                     -> return prefix + s.substring(1, s.length()-1)
                    Label returnAsIs = cb.newLabel();
                    Label stripBrackets = cb.newLabel();

                    cb.aload(0);
                    cb.invokevirtual(CD_String, "length", MethodTypeDesc.of(CD_int));
                    cb.istore(2);                                            // len -> slot 2 (0=s, 1=prefix)

                    cb.iload(2);
                    cb.iconst_2();
                    cb.if_icmplt(returnAsIs);                                // len < 2

                    cb.aload(0);
                    cb.iconst_0();
                    cb.invokevirtual(CD_String, "charAt", MethodTypeDesc.of(CD_char, CD_int));
                    cb.bipush((byte) '[');
                    cb.if_icmpne(returnAsIs);                                // s.charAt(0) != '['

                    cb.iload(2);
                    cb.iconst_2();
                    cb.if_icmpne(stripBrackets);                             // len != 2

                    // Empty array "[]" -> return ""
                    cb.ldc("");
                    cb.areturn();

                    cb.labelBinding(stripBrackets);
                    // return prefix.concat(s.substring(1, len - 1))
                    cb.aload(1);                                             // prefix
                    cb.aload(0);                                             // s
                    cb.iconst_1();
                    cb.iload(2);
                    cb.iconst_1();
                    cb.isub();
                    cb.invokevirtual(CD_String, "substring",
                            MethodTypeDesc.of(CD_String, CD_int, CD_int));
                    cb.invokevirtual(CD_String, "concat",
                            MethodTypeDesc.of(CD_String, CD_String));
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
        return owner.displayName() + "." + mm.methodName().stringValue() + "(" + buildArgList(mm) + ")";
    }

    private static String synthesizeReturnMessage(ClassDesc owner, MethodModel mm, boolean isVoid) {
        String tail = isVoid ? "void" : "%s";
        return owner.displayName() + "." + mm.methodName().stringValue() + "(" + buildArgList(mm) + ") -> " + tail;
    }

    /**
     * Builds the parenthesised argument-list template for a message. Non-varargs
     * methods get plain {@code "%s, %s, ..."}. Varargs methods that also have
     * regular parameters omit the separator before the trailing {@code %s} so
     * that the varargs helper can emit its own leading {@code ", "} (or nothing,
     * for an empty varargs) without leaving a stray comma behind.
     */
    private static String buildArgList(MethodModel mm) {
        int paramCount = mm.methodTypeSymbol().parameterCount();
        boolean varargsAfterRegular = mm.flags().has(AccessFlag.VARARGS) && paramCount > 1;
        if (!varargsAfterRegular) {
            return String.join(", ", Collections.nCopies(paramCount, "%s"));
        }
        return String.join(", ", Collections.nCopies(paramCount - 1, "%s")) + "%s";
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

package io.github.ralfspoeth.log.weaver;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.constant.ConstantDescs.*;
import static java.util.function.Predicate.not;

@Mojo(name = "weave", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution = ResolutionScope.COMPILE)
public class LogWeaver extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private Path classesDir;

    /**
     * Test-only setter — Maven injects {@link #classesDir} directly into the field.
     */
    public void setClassesDir(Path classesDir) {
        this.classesDir = classesDir;
    }

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

    /**
     * Synthetic per-class field that holds the {@link System.Logger} instance.
     */
    static final String LOGGER_FIELD_NAME = "$logweaver$LOGGER";

    /**
     * Synthetic per-class helper that strips the leading {@code '['} and
     * trailing {@code ']'} from {@code Arrays.toString(...)} output, so a
     * varargs argument shows up in the log message as comma-separated
     * elements rather than as the raw array's identity hash.
     */
    static final String VA_HELPER_NAME = "$logweaver$va";

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

    // ── execute ──────────────────────────────────────────────────────────────
    @Override
    public void execute() throws MojoExecutionException {

        if (!Files.isDirectory(classesDir)) {
            getLog().info("LogWeaver: " + classesDir + " does not exist, skipped.");
            return;
        }

        int[] stats = {0, 0}; // [checked, transformed]
        List<Path> failed = new ArrayList<>();

        Scopes scopes;
        try {
            // First pass: collect @LogAll from module-info / package-info.
            scopes = scanScopes(classesDir);
        } catch (IOException e) {
            throw new MojoExecutionException("LogWeaver: error during pre-scan of " + classesDir, e);
        }

        try (var stream = Files.walk(classesDir)) {
            var classMatcher = classesDir.getFileSystem().getPathMatcher("glob:*.class");
            var infoMatcher = classesDir.getFileSystem().getPathMatcher("glob:*-info.class");
            stream.filter(p -> classMatcher.matches(p.getFileName())).filter(not(p -> infoMatcher.matches(p.getFileName()))).forEach(p -> {
                if (!processClass(p, stats, scopes)) failed.add(p);
            });
        } catch (IOException e) {
            throw new MojoExecutionException("LogWeaver: error walking " + classesDir, e);
        }

        if (!failed.isEmpty()) {
            failed.forEach(p -> getLog().error("LogWeaver: error in " + p));
            throw new MojoExecutionException("LogWeaver: " + failed.size() + " class(es) could not be transformed.");
        }

        getLog().info(String.format("LogWeaver: %d classes checked, %d transformed.", stats[0], stats[1]));
    }

    // ── Single .class file ───────────────────────────────────────────────────
    private boolean processClass(Path classFile, int[] stats, Scopes scopes) {
        stats[0]++;
        try {
            byte[] original = Files.readAllBytes(classFile);
            byte[] transformed = tryTransform(original, scopes);
            // tryTransform returns the original reference array iff no annotation
            // was found – otherwise always a new array.
            if (transformed != original) {
                Files.write(classFile, transformed);
                stats[1]++;
                getLog().debug("LogWeaver: transformed → " + classFile);
            }
            return true;
        } catch (Exception e) {
            getLog().error("LogWeaver: error in " + classFile + ": " + e.getMessage(), e);
            return false;
        }
    }

    // ── Transformation ───────────────────────────────────────────────────────
    private byte[] tryTransform(byte[] original, Scopes scopes) {
        ClassFile cf = ClassFile.of();
        ClassModel cm = cf.parse(original);

        ClassDesc owner = cm.thisClass().asSymbol();

        // Resolve the effective @LogAll for this class: type > package > module.
        Optional<LogAllConfig> effectiveAll = readLogAllConfig(cm).or(() -> Optional.ofNullable(scopes.byPackage().get(owner.packageName()))).or(scopes::module);

        // A method counts as "already woven" iff its synthetic helper is
        // present. The helper name is deterministic per (method name,
        // descriptor), so a re-run on already-woven bytecode safely skips it.
        Set<String> existingMethodNames = cm.methods().stream().map(m -> m.methodName().stringValue()).collect(Collectors.toUnmodifiableSet());
        Predicate<MethodModel> notYetWoven = mm -> !existingMethodNames.contains(syntheticName(mm));

        boolean anyLog = cm.methods().stream().filter(notYetWoven).anyMatch(this::hasLogAnnotation);
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

    /**
     * Emits {@code LOGGER = System.getLogger(<loggerName>);} into the given code builder.
     */
    private static void emitLoggerInit(CodeBuilder cb, ClassDesc owner, String loggerName) {
        cb.ldc(loggerName);
        cb.invokestatic(CD_System, "getLogger", MethodTypeDesc.of(CD_Logger, CD_String));
        cb.putstatic(owner, LOGGER_FIELD_NAME, CD_Logger);
    }

    /**
     * Determines the effective {@link LogInfo} for a method by the
     * "most-specific wins" rule:
     * <ol>
     *     <li>Method-level {@code @Log} beats everything.</li>
     *     <li>Otherwise the effective {@code @LogAll} (type → package → module)
     *         applies, if it matches the method.</li>
     *     <li>Otherwise no weaving.</li>
     * </ol>
     * The entry-log message is always synthesized as
     * {@code <SimpleClass>.<method>(%s, …)} – the same policy as {@code @LogAll}.
     */
    private Optional<LogInfo> resolveLogInfo(MethodModel mm, Optional<LogAllConfig> effectiveAll) {
        Optional<LogInfo> methodLog = readLogAnnotation(mm);
        if (methodLog.isPresent()) return methodLog;

        if (effectiveAll.isPresent() && effectiveAll.get().matches(mm)) {
            // @LogAll only drives the entry log; the exception handler is
            // installed at the @Log default exception level (WARNING in 0.5).
            return Optional.of(new LogInfo(effectiveAll.get().levelName(), false,
                    LOG_DEFAULTS.exceptionLevelName()));
        }
        return Optional.empty();
    }

    /**
     * Rewrites the annotated method with:
     * <ol>
     *   <li>Exactly one supplier-based log call — entry vs. return is
     *       mutually exclusive:
     *       <ul>
     *         <li>{@code logReturn() == false} (the default): a single entry
     *             log before the body, capturing the (boxed) parameters.</li>
     *         <li>{@code logReturn() == true}: a single return log emitted
     *             before each {@link ReturnInstruction}, capturing the
     *             parameters and (for non-void methods) the return value.</li>
     *       </ul>
     *       Both use the {@code level()} of the {@code @Log} annotation and
     *       a lazy {@link java.util.function.Supplier} built via
     *       {@code invokedynamic}.</li>
     *   <li>An always-on {@link Throwable} catch around the body that calls
     *       {@code logger.log(exceptionLevel, t.getMessage(), t)} and
     *       re-throws. {@code exceptionLevel} defaults to {@code WARNING}
     *       (taken from {@code @Log}'s reflective defaults).</li>
     * </ol>
     * Only one synthetic helper method is generated per woven method — its
     * shape (and the format string it carries) reflects which of entry-log or
     * return-log was emitted. The {@code @Log} annotation is stripped after
     * weaving so a second pass is a no-op.
     */
    private void weaveMethod(ClassBuilder clb, MethodModel mm, LogInfo info, ClassDesc owner) {
        boolean isStatic = mm.flags().has(AccessFlag.STATIC);
        List<ParamSlot> params = ParamSlot.of(mm, isStatic);
        List<ClassDesc> implParams = params.stream().map(p -> p.isPrimitive() ? p.boxed() : p.type()).toList();

        ClassDesc returnType = mm.methodTypeSymbol().returnType();
        boolean isVoid = returnType.equals(CD_void);
        boolean logReturn = info.logReturn();

        // Varargs methods carry ACC_VARARGS; the last formal parameter is the
        // implicit array. We make that slot show up as comma-separated elements
        // in the log message rather than as the array's identity hash. The
        // captured-arg index of the varargs slot is the same in both helper
        // shapes (entry: params; return: params + return), since the return
        // value is appended *after* the params.
        int varargsSlot = mm.flags().has(AccessFlag.VARARGS) && !params.isEmpty()
                ? params.size() - 1
                : -1;

        // Single synthetic helper. The name is deterministic (and shared with
        // the "already woven?" check), but the shape and the message string
        // depend on whether we're emitting an entry log or a return log.
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

        // Local-slot allocation for the return value (logReturn case) and the
        // throwable. Both sit *above* the method's original max_locals so they
        // cannot collide with any local already used by the body.
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

                    // ── Entry log (params only) when not capturing the return ──
                    if (!logReturn) {
                        emitParamLog(cb, owner, info.levelName(), params, indy);
                    }

                    // ── Body, optionally with each ReturnInstruction preceded
                    //    by a return-log emission ──
                    code.forEach(element -> {
                        if (logReturn && element instanceof ReturnInstruction) {
                            emitReturnLog(cb, owner, info.levelName(), params,
                                    returnType, isVoid, origMaxLocals, indy);
                        }
                        cb.with(element);
                    });

                    cb.labelBinding(tryEnd);

                    // ── Always-on Throwable handler: log + rethrow ──
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
                // else: drop the attribute entirely so re-runs find no @Log.
            } else {
                mb.with(mElement);
            }
        });

        // One helper per woven method.
        emitFormatHelper(clb, helperName, helperType, message, owner, varargsSlot);
    }

    /**
     * Emits an entry-log call: loads LOGGER + Level, pushes each (boxed)
     * parameter onto the stack, then builds the {@code Supplier<String>} via
     * {@code invokedynamic} and calls
     * {@link System.Logger#log(System.Logger.Level, java.util.function.Supplier)}.
     */
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

    /**
     * Emits the return-log call right before a {@link ReturnInstruction}. For a
     * non-void method the value to be returned is on top of the operand stack
     * on entry; this helper stores it in {@code resultSlot}, formats and logs
     * the message, then restores it so the original {@code XRETURN} instruction
     * (emitted by the caller) can consume it again.
     */
    private static void emitReturnLog(CodeBuilder cb,
                                      ClassDesc owner,
                                      String levelName,
                                      List<ParamSlot> params,
                                      ClassDesc returnType,
                                      boolean isVoid,
                                      int resultSlot,
                                      DynamicCallSiteDesc returnIndy) {
        // Save the return value off the stack so we can both log and return it.
        if (!isVoid) {
            storeAt(cb, returnType, resultSlot);
        }

        cb.getstatic(owner, LOGGER_FIELD_NAME, CD_Logger);
        cb.getstatic(CD_Level, levelName, CD_Level);

        // Captures: parameters first (boxed), then the boxed return value.
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

        // Reload the return value so the original XRETURN can consume it.
        if (!isVoid) {
            loadAt(cb, returnType, resultSlot);
        }
    }

    /**
     * Builds the {@code Supplier<String>} {@code invokedynamic} call site that
     * captures the given parameters and (on {@code .get()}) invokes the
     * named static helper to produce the formatted log message.
     */
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

    /**
     * Emits a synthetic helper method that returns
     * {@code message.formatted(arg0, arg1, ...)} — one positional argument per
     * (boxed) parameter slot.
     *
     * <p>If {@code varargsSlot >= 0}, the argument at that slot is a Java
     * varargs array: instead of pushing the array reference into the format
     * args (which would render as {@code [Ljava/lang/String;@…}), we push
     * {@code $logweaver$va(Arrays.toString(arr))} — i.e. its elements
     * comma-separated, without the surrounding {@code [} / {@code ]}.
     */
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
                cb.aload(i);   // every parameter is an object (boxed) or an array
                if (i == varargsSlot) {
                    ClassDesc arrType = type.parameterType(i);
                    // Pick the matching Arrays.toString overload:
                    //   primitive component  → exact array descriptor (e.g. ([I)Ljava/lang/String;)
                    //   reference component  → (Object[])  — String[] etc. flow in via array covariance
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

    /**
     * Emits the per-class {@link #VA_HELPER_NAME} method:
     * <pre>{@code
     * private static String $logweaver$va(String s) {
     *     int len = s.length();
     *     if (len < 2 || s.charAt(0) != '[') return s;
     *     return s.substring(1, len - 1);
     * }
     * }</pre>
     * Input is always {@link java.util.Arrays#toString} output, so the
     * {@code "[…]"} prefix/suffix is the only thing to strip; the {@code "null"}
     * literal (returned by {@code Arrays.toString} for a {@code null} array)
     * and any unexpected non-bracketed input are passed through unchanged.
     */
    private static void emitVaHelper(ClassBuilder clb) {
        clb.withMethod(VA_HELPER_NAME,
                MethodTypeDesc.of(CD_String, CD_String),
                ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                mb -> mb.withCode(cb -> {
                    Label returnAsIs = cb.newLabel();

                    // int len = s.length();           — local 1
                    cb.aload(0);
                    cb.invokevirtual(CD_String, "length", MethodTypeDesc.of(CD_int));
                    cb.istore(1);

                    // if (len < 2) return s;
                    cb.iload(1);
                    cb.iconst_2();
                    cb.if_icmplt(returnAsIs);

                    // if (s.charAt(0) != '[') return s;
                    cb.aload(0);
                    cb.iconst_0();
                    cb.invokevirtual(CD_String, "charAt", MethodTypeDesc.of(CD_char, CD_int));
                    cb.bipush((byte) '[');
                    cb.if_icmpne(returnAsIs);

                    // return s.substring(1, len - 1);
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

    /**
     * Deterministic name for the synthetic format helper. Includes a hash of
     * the method's descriptor so overloaded methods produce distinct helpers
     * and so already-woven methods can be detected on a re-run.
     */
    private static String syntheticName(MethodModel mm) {
        return "lambda$logweaver$" + mm.methodName().stringValue() + "$" + descHash(mm);
    }

    private static String descHash(MethodModel mm) {
        return Integer.toHexString(mm.methodType().stringValue().hashCode() & 0x7fffffff);
    }

    // ── Annotation reading ───────────────────────────────────────────────────
    private boolean hasLogAnnotation(MethodModel m) {
        return m.findAttribute(Attributes.runtimeVisibleAnnotations()).map(attr -> attr.annotations().stream().anyMatch(a -> a.classSymbol().equals(CD_Log))).orElse(false);
    }

    private Optional<LogInfo> readLogAnnotation(MethodModel m) {
        return m.findAttribute(Attributes.runtimeVisibleAnnotations()).flatMap(attr -> attr.annotations().stream().filter(a -> a.classSymbol().equals(CD_Log)).findFirst()).map(LogWeaver::extractLogInfo);
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

    // ── @LogAll: scope pre-scan, reading, resolution ─────────────────────────

    /**
     * First pass: reads {@code module-info.class} and every {@code package-info.class}
     * file and collects their respective {@code @LogAll} configurations.
     */
    private Scopes scanScopes(Path root) throws IOException {
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
            Optional<LogAllConfig> cfg = readLogAllConfig(cm);
            if (cfg.isEmpty()) continue;

            if (info.getFileName().toString().equals("module-info.class")) {
                moduleConfig = cfg;
            } else {
                packageConfigs.put(cm.thisClass().asSymbol().packageName(), cfg.get());
            }
        }
        return new Scopes(moduleConfig, Map.copyOf(packageConfigs));
    }

    private Optional<LogAllConfig> readLogAllConfig(ClassModel cm) {
        return cm.findAttribute(Attributes.runtimeVisibleAnnotations()).flatMap(attr -> attr.annotations().stream().filter(a -> a.classSymbol().equals(CD_LogAll)).findFirst()).map(LogWeaver::extractLogAllConfig);
    }

    private static LogAllConfig extractLogAllConfig(Annotation ann) {
        // Defaults from the annotation itself (loaded reflectively).
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
        int modifiers = 0;        // "all visibilities"
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

    /**
     * Synthetic message for the entry log of a {@code @LogAll}-woven method or
     * a {@code @Log} with empty {@code value()}:
     * {@code "<SimpleClass>.<method>(%s, %s, ...)"} with one {@code %s} per parameter.
     */
    private static String synthesizeMessage(ClassDesc owner, MethodModel mm) {
        int paramCount = mm.methodTypeSymbol().parameterCount();
        String args = String.join(", ", Collections.nCopies(paramCount, "%s"));
        return owner.displayName() + "." + mm.methodName().stringValue() + "(" + args + ")";
    }

    /**
     * Synthetic message for the return log:
     * <ul>
     *   <li>void → {@code "<SimpleClass>.<method>(%s, %s) -> void"} (params)</li>
     *   <li>non-void → {@code "<SimpleClass>.<method>(%s, %s) -> %s"}
     *       (params, then the return value)</li>
     * </ul>
     */
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
        return type; // already a reference type
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
     * call but does not change the bytecode shape (the catch is still in
     * place and the throwable is still rethrown). The displayed message is
     * always synthesized – see
     * {@link #synthesizeMessage(ClassDesc, MethodModel)} and
     * {@link #synthesizeReturnMessage(ClassDesc, MethodModel, boolean)}.
     */
    record LogInfo(String levelName, boolean logReturn, String exceptionLevelName) {}

    /**
     * Configuration of a {@code @LogAll} annotation.
     */
    record LogAllConfig(int modifiers, String levelName, String methodPattern) {

        /**
         * Does this @LogAll configuration apply to this method?
         */
        boolean matches(MethodModel mm) {
            String name = mm.methodName().stringValue();
            if (name.equals("<init>") || name.equals("<clinit>")) return false;

            int flags = mm.flags().flagsMask();
            int skip = ClassFile.ACC_ABSTRACT | ClassFile.ACC_NATIVE | ClassFile.ACC_BRIDGE | ClassFile.ACC_SYNTHETIC;
            if ((flags & skip) != 0) return false;

            // modifiers == 0 is the sentinel meaning "all visibilities, including
            // package-private". Otherwise OR semantics apply: at least one
            // requested visibility bit must be set.
            if (modifiers != 0 && (flags & modifiers) == 0) return false;

            return Pattern.matches(methodPattern, name);
        }
    }

    /**
     * Pre-collected @LogAll defaults for modules and packages.
     */
    record Scopes(Optional<LogAllConfig> module, Map<String, LogAllConfig> byPackage) {}

    record ParamSlot(int slot, ClassDesc type) {

        static List<ParamSlot> of(MethodModel m, boolean isStatic) {
            var result = new ArrayList<ParamSlot>();
            int slot = isStatic ? 0 : 1; // static: no this slot
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

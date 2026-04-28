package io.github.ralfspoeth.log.weaver;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.IOException;
import java.lang.classfile.*;
import java.lang.constant.*;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import static java.lang.constant.ConstantDescs.*;
import static java.util.function.Predicate.not;

@Mojo(name = "weave", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution = ResolutionScope.COMPILE)
public class LogWeaver extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    public void setClassesDir(Path classesDir) {
        this.classesDir = classesDir;
    }

    private Path classesDir;


    // ── Constants ────────────────────────────────────────────────────────────
    // CD_String, CD_Object, CD_Boolean etc. come from ConstantDescs (static import).
    private static final ClassDesc CD_Logger = ClassDesc.of("java.lang.System$Logger");
    private static final ClassDesc CD_Level = ClassDesc.of("java.lang.System$Logger$Level");
    private static final ClassDesc CD_System = ClassDesc.of("java.lang.System");
    private static final ClassDesc CD_Supplier = ClassDesc.of("java.util.function.Supplier");
    private static final ClassDesc CD_ObjectArray = CD_Object.arrayType();
    private static final ClassDesc CD_Log = ClassDesc.of("io.github.ralfspoeth.log.api.Log");
    private static final ClassDesc CD_LogAll = ClassDesc.of("io.github.ralfspoeth.log.api.LogAll");

    private static final DirectMethodHandleDesc LMF_BOOTSTRAP = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, ClassDesc.of("java.lang.invoke.LambdaMetafactory"), "metafactory", MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodHandles$Lookup;" + "Ljava/lang/String;" + "Ljava/lang/invoke/MethodType;" + "Ljava/lang/invoke/MethodType;" + "Ljava/lang/invoke/MethodHandle;" + "Ljava/lang/invoke/MethodType;" + ")Ljava/lang/invoke/CallSite;"));

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

        boolean anyLog = cm.methods().stream().anyMatch(this::hasLogAnnotation);
        boolean anyLogAll = effectiveAll.isPresent() && cm.methods().stream().anyMatch(effectiveAll.get()::matches);

        if (!anyLog && !anyLogAll) return original;

        // Inline ClassTransform: pass through every class element except woven
        // methods – those are rewritten AND a synthetic format method is
        // immediately emitted into the same builder.
        return cf.transformClass(cm, (clb, element) -> {
            if (element instanceof MethodModel mm) {
                resolveLogInfo(owner, mm, effectiveAll).ifPresentOrElse(info -> weaveMethod(clb, mm, info, owner), () -> clb.with(mm));
            } else {
                clb.with(element);
            }
        });
    }

    /**
     * Determines the effective {@link LogInfo} for a method by the
     * "most-specific wins" rule:
     * <ol>
     *     <li>Method-level {@code @Log} beats everything.</li>
     *     <li>Empty {@code value()} → the message is synthesized
     *         ({@code <SimpleClass>.<method>(%s, …)}).</li>
     *     <li>Otherwise the effective {@code @LogAll} (type → package → module)
     *         applies, if it matches the method; the message is synthesized as well.</li>
     *     <li>Otherwise no weaving.</li>
     * </ol>
     */
    private Optional<LogInfo> resolveLogInfo(ClassDesc owner, MethodModel mm, Optional<LogAllConfig> effectiveAll) {
        Optional<LogInfo> methodLog = readLogAnnotation(mm).map(info -> info.message().isEmpty() ? new LogInfo(synthesizeMessage(owner, mm), info.levelName()) : info);
        if (methodLog.isPresent()) return methodLog;

        if (effectiveAll.isPresent() && effectiveAll.get().matches(mm)) {
            return Optional.of(new LogInfo(synthesizeMessage(owner, mm), effectiveAll.get().levelName()));
        }
        return Optional.empty();
    }

    /**
     * Rewrites the annotated method (with logging prologue) and appends the
     * synthetic format helper method directly to the class.
     */
    private void weaveMethod(ClassBuilder clb, MethodModel mm, LogInfo info, ClassDesc owner) {
        boolean isStatic = mm.flags().has(AccessFlag.STATIC);
        List<ParamSlot> params = ParamSlot.of(mm, isStatic);
        List<ClassDesc> implParams = params.stream().map(p -> p.isPrimitive() ? p.boxed() : p.type()).toList();
        String implName = "lambda$logweaver$" + mm.methodName().stringValue();
        MethodTypeDesc implType = MethodTypeDesc.of(CD_String, implParams);

        DirectMethodHandleDesc implHandle = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, owner, implName, implType);

        // invokedynamic call site: yields a Supplier<String> that, on get(),
        // calls the static helper method with the captured arguments.
        DynamicCallSiteDesc indySupplier = DynamicCallSiteDesc.of(LMF_BOOTSTRAP, "get", MethodTypeDesc.of(CD_Supplier, implParams),   // capture type
                MethodTypeDesc.of(CD_Object),                 // SAM erasure
                implHandle,                                   // implementation
                MethodTypeDesc.of(CD_String)                  // instantiated type
        );

        String loggerName = (owner.packageName().isEmpty() ? "" : owner.packageName() + ".") + owner.displayName();

        // (a) Re-emit the original method 1:1, replacing only the Code attribute.
        //     transformMethod inherits name, descriptor, flags, and annotations
        //     automatically.
        clb.transformMethod(mm, (mb, mElement) -> {
            if (mElement instanceof CodeModel code) {
                mb.withCode(cb -> {
                    // Logger logger = System.getLogger(<class>);
                    cb.ldc(loggerName);
                    cb.invokestatic(CD_System, "getLogger", MethodTypeDesc.of(CD_Logger, CD_String));

                    // Load the level constant: Logger.Level.<NAME>
                    cb.getstatic(CD_Level, info.levelName(), CD_Level);

                    // Load parameters (boxing primitives) – capture for invokedynamic.
                    for (ParamSlot ps : params) {
                        ps.load(cb);
                        if (ps.isPrimitive()) {
                            cb.invokestatic(ps.boxed(), "valueOf", MethodTypeDesc.of(ps.boxed(), ps.type()));
                        }
                    }

                    // Supplier<String> via LambdaMetafactory.
                    cb.invokedynamic(indySupplier);

                    // logger.log(level, supplier);
                    cb.invokeinterface(CD_Logger, "log", MethodTypeDesc.of(CD_void, CD_Level, CD_Supplier));

                    // Append the method's original code.
                    code.forEach(cb);
                });
            } else {
                mb.with(mElement);
            }
        });

        // (b) Append the synthetic format method directly in the same builder.
        clb.withMethod(implName, implType, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC, mb -> mb.withCode(cb -> {
            cb.ldc(info.message());
            cb.ldc(implParams.size());
            cb.anewarray(CD_Object);
            for (int i = 0; i < implParams.size(); i++) {
                cb.dup();
                cb.ldc(i);
                cb.aload(i);   // every parameter is an object (boxed)
                cb.aastore();
            }
            cb.invokevirtual(CD_String, "formatted", MethodTypeDesc.of(CD_String, CD_ObjectArray));
            cb.areturn();
        }));
    }

    // ── Annotation reading ───────────────────────────────────────────────────
    private boolean hasLogAnnotation(MethodModel m) {
        return m.findAttribute(Attributes.runtimeVisibleAnnotations()).map(attr -> attr.annotations().stream().anyMatch(a -> a.classSymbol().equals(CD_Log))).orElse(false);
    }

    private Optional<LogInfo> readLogAnnotation(MethodModel m) {
        return m.findAttribute(Attributes.runtimeVisibleAnnotations()).flatMap(attr -> attr.annotations().stream().filter(a -> a.classSymbol().equals(CD_Log)).findFirst()).map(LogWeaver::extractLogInfo);
    }

    private static LogInfo extractLogInfo(Annotation ann) {
        String message = LOG_DEFAULTS.message();
        String levelName = LOG_DEFAULTS.levelName();
        for (AnnotationElement el : ann.elements()) {
            switch (el.name().stringValue()) {
                case "value" -> {
                    if (el.value() instanceof AnnotationValue.OfString s) message = s.stringValue();
                }
                case "level" -> {
                    if (el.value() instanceof AnnotationValue.OfEnum e) levelName = e.constantName().stringValue();
                }
            }
        }
        return new LogInfo(message, levelName);
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
        String message = "";
        String levelName = "INFO";
        try {
            for (Method m : Class.forName("io.github.ralfspoeth.log.api.Log").getDeclaredMethods()) {
                Object dv = m.getDefaultValue();
                if (dv == null) continue;
                switch (m.getName()) {
                    case "value" -> {
                        if (dv instanceof String s) message = s;
                    }
                    case "level" -> {
                        if (dv instanceof Enum<?> e) levelName = e.name();
                    }
                }
            }
        } catch (Throwable ignore) { /* fallbacks stand */ }
        return new LogInfo(message, levelName);
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
     * Synthetic message for {@code @LogAll}-woven methods:
     * {@code "<SimpleClass>.<method>(%s, %s, ...)"} with one {@code %s} per parameter.
     */
    private static String synthesizeMessage(ClassDesc owner, MethodModel mm) {
        int paramCount = mm.methodTypeSymbol().parameterCount();
        String args = String.join(", ", Collections.nCopies(paramCount, "%s"));
        return owner.displayName() + "." + mm.methodName().stringValue() + "(" + args + ")";
    }

    record LogInfo(String message, String levelName) {}

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

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static java.lang.constant.ConstantDescs.*;
import static java.util.function.Predicate.not;

@Mojo(
        name = "weave",
        defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE
)
public class LogWeaver extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private Path classesDir;

    /**
     * Vollqualifizierter Name der Log-Annotation. Konfigurierbar.
     */
    @Parameter(defaultValue = "com.example.annotations.Log")
    private String logAnnotation;

    // ── Konstanten ───────────────────────────────────────────────────────────
    private static final ClassDesc LOGGER_CD = ClassDesc.of("java.lang.System$Logger");
    private static final ClassDesc LEVEL_CD = ClassDesc.of("java.lang.System$Logger$Level");
    private static final ClassDesc STRING_CD = ClassDesc.of("java.lang.String");
    private static final ClassDesc OBJECT_CD = ClassDesc.of("java.lang.Object");
    private static final ClassDesc OBJECT_ARR = OBJECT_CD.arrayType();
    private static final ClassDesc SYSTEM_CD = ClassDesc.of("java.lang.System");
    private static final ClassDesc SUPPLIER_CD = ClassDesc.of("java.util.function.Supplier");

    private static final DirectMethodHandleDesc LMF_BOOTSTRAP =
            MethodHandleDesc.ofMethod(
                    DirectMethodHandleDesc.Kind.STATIC,
                    ClassDesc.of("java.lang.invoke.LambdaMetafactory"),
                    "metafactory",
                    MethodTypeDesc.ofDescriptor(
                            "(Ljava/lang/invoke/MethodHandles$Lookup;"
                                    + "Ljava/lang/String;"
                                    + "Ljava/lang/invoke/MethodType;"
                                    + "Ljava/lang/invoke/MethodType;"
                                    + "Ljava/lang/invoke/MethodHandle;"
                                    + "Ljava/lang/invoke/MethodType;"
                                    + ")Ljava/lang/invoke/CallSite;"
                    )
            );

    private ClassDesc logAnnotationDesc() {
        return ClassDesc.of(logAnnotation);
    }

    // ── execute ──────────────────────────────────────────────────────────────
    @Override
    public void execute() throws MojoExecutionException {

        if (!Files.isDirectory(classesDir)) {
            getLog().info("LogWeaver: " + classesDir + " existiert nicht, übersprungen.");
            return;
        }

        int[] stats = {0, 0}; // [geprüft, transformiert]
        List<Path> failed = new ArrayList<>();

        try (var stream = Files.walk(classesDir)) {
            var classMatcher = classesDir.getFileSystem().getPathMatcher("glob:*.class");
            var infoMatcher = classesDir.getFileSystem().getPathMatcher("glob:*-info.class");
            stream.filter(p -> classMatcher.matches(p.getFileName()))
                    .filter(not(p -> infoMatcher.matches(p.getFileName())))
                    .forEach(p -> {
                        if (!processClass(p, stats)) failed.add(p);
                    });
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "LogWeaver: Fehler beim Durchsuchen von " + classesDir, e);
        }

        if (!failed.isEmpty()) {
            failed.forEach(p -> getLog().error("LogWeaver: Fehler in " + p));
            throw new MojoExecutionException(
                    "LogWeaver: " + failed.size() + " Klasse(n) konnten nicht transformiert werden.");
        }

        getLog().info(String.format(
                "LogWeaver: %d Klassen geprüft, %d transformiert.", stats[0], stats[1]));
    }

    // ── Einzelne .class-Datei ────────────────────────────────────────────────
    private boolean processClass(Path classFile, int[] stats) {
        stats[0]++;
        try {
            byte[] original = Files.readAllBytes(classFile);
            byte[] transformed = tryTransform(original);
            // tryTransform liefert genau dann das Originalreferenz-Array zurück,
            // wenn keine Annotation gefunden wurde – sonst stets ein neues Array.
            if (transformed != original) {
                Files.write(classFile, transformed);
                stats[1]++;
                getLog().debug("LogWeaver: transformiert → " + classFile);
            }
            return true;
        } catch (Exception e) {
            getLog().error("LogWeaver: Fehler in " + classFile + ": " + e.getMessage(), e);
            return false;
        }
    }

    // ── Transformation ───────────────────────────────────────────────────────
    private byte[] tryTransform(byte[] original) {
        ClassFile cf = ClassFile.of();
        ClassModel cm = cf.parse(original);

        if (cm.methods().stream().noneMatch(this::hasLogAnnotation))
            return original;

        ClassDesc owner = cm.thisClass().asSymbol();
        List<Consumer<ClassBuilder>> syntheticMethods = new ArrayList<>();

        // Methoden transformieren UND in einem Rutsch synthetische Helfer anhängen.
        return cf.transformClass(cm,
                ClassTransform.transformingMethods(
                        (mb, element) -> transformMethod(mb, element, syntheticMethods, owner)
                ).andThen(ClassTransform.endHandler(clb ->
                        syntheticMethods.forEach(c -> c.accept(clb))
                ))
        );
    }

    private void transformMethod(MethodBuilder mb, MethodElement element,
                                 List<Consumer<ClassBuilder>> syntheticMethods,
                                 ClassDesc owner) {
        if (!(element instanceof CodeModel code)) {
            mb.with(element);
            return;
        }

        var logInfo = readLogAnnotation(code);
        if (logInfo.isEmpty()) {
            mb.with(element);
            return;
        }

        LogInfo info = logInfo.get();
        MethodModel mm = code.parent().orElseThrow();
        boolean isStatic = mm.flags().has(AccessFlag.STATIC);
        List<ParamSlot> params = ParamSlot.of(mm, isStatic);
        String implName = "lambda$logweaver$" + mm.methodName().stringValue();

        // Geboxte Parametertypen für die synthetische Format-Methode.
        List<ClassDesc> implParams = params.stream()
                .map(p -> p.isPrimitive() ? p.boxed() : p.type())
                .toList();
        MethodTypeDesc implType = MethodTypeDesc.of(STRING_CD, implParams);

        DirectMethodHandleDesc implHandle = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                owner, implName, implType);

        // invokedynamic-Aufrufstelle: erzeugt einen Supplier<String>,
        // der bei get() die statische Hilfsmethode mit den gefangenen
        // Argumenten ruft.
        DynamicCallSiteDesc indySupplier = DynamicCallSiteDesc.of(
                LMF_BOOTSTRAP,
                "get",
                MethodTypeDesc.of(SUPPLIER_CD, implParams),   // Capture-Typ
                MethodTypeDesc.of(OBJECT_CD),                  // SAM-Erasure
                implHandle,                                    // Implementierung
                MethodTypeDesc.of(STRING_CD)                   // instanziierter Typ
        );

        String loggerName = (owner.packageName().isEmpty() ? "" : owner.packageName() + ".")
                + owner.displayName();

        mb.withCode(cb -> {
            // Logger logger = System.getLogger(<class>);
            cb.ldc(loggerName);
            cb.invokestatic(SYSTEM_CD, "getLogger",
                    MethodTypeDesc.of(LOGGER_CD, STRING_CD));

            // Level-Konstante laden: Logger.Level.<NAME>
            cb.getstatic(LEVEL_CD, info.levelName(), LEVEL_CD);

            // Parameter laden (primitive Typen boxen) – Capture für invokedynamic.
            for (ParamSlot ps : params) {
                ps.load(cb);
                if (ps.isPrimitive()) {
                    cb.invokestatic(ps.boxed(), "valueOf",
                            MethodTypeDesc.of(ps.boxed(), ps.type()));
                }
            }

            // Supplier<String> via LambdaMetafactory.
            cb.invokedynamic(indySupplier);

            // logger.log(level, supplier);
            cb.invokeinterface(LOGGER_CD, "log",
                    MethodTypeDesc.of(CD_void, LEVEL_CD, SUPPLIER_CD));

            // Originalcode der Methode anhängen.
            code.forEach(cb);
        });

        // Synthetische Format-Methode später per endHandler in die Klasse einfügen.
        syntheticMethods.add(clb ->
                clb.withMethod(implName, implType,
                        ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                        xmb -> xmb.withCode(xcb -> {
                            xcb.ldc(info.message());
                            xcb.ldc(implParams.size());
                            xcb.anewarray(OBJECT_CD);
                            for (int i = 0; i < implParams.size(); i++) {
                                xcb.dup();
                                xcb.ldc(i);
                                xcb.aload(i);   // alle Parameter sind Objekte (geboxt)
                                xcb.aastore();
                            }
                            xcb.invokevirtual(STRING_CD, "formatted",
                                    MethodTypeDesc.of(STRING_CD, OBJECT_ARR));
                            xcb.areturn();
                        })
                )
        );
    }

    // ── Annotation lesen ─────────────────────────────────────────────────────
    private boolean hasLogAnnotation(MethodModel m) {
        ClassDesc target = logAnnotationDesc();
        return m.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(attr -> attr.annotations().stream()
                        .anyMatch(a -> a.classSymbol().equals(target)))
                .orElse(false);
    }

    private Optional<LogInfo> readLogAnnotation(CodeModel code) {
        ClassDesc target = logAnnotationDesc();
        return code.parent()
                .flatMap(m -> m.findAttribute(Attributes.runtimeVisibleAnnotations()))
                .flatMap(attr -> attr.annotations().stream()
                        .filter(a -> a.classSymbol().equals(target))
                        .findFirst())
                .map(LogWeaver::extractLogInfo);
    }

    private static LogInfo extractLogInfo(Annotation ann) {
        String message = "";
        String levelName = "INFO";
        for (AnnotationElement el : ann.elements()) {
            switch (el.name().stringValue()) {
                case "value" -> {
                    if (el.value() instanceof AnnotationValue.OfString s)
                        message = s.stringValue();
                }
                case "level" -> {
                    if (el.value() instanceof AnnotationValue.OfEnum e)
                        levelName = e.constantName().stringValue();
                }
            }
        }
        return new LogInfo(message, levelName);
    }

    record LogInfo(String message, String levelName) {}

    record ParamSlot(int slot, ClassDesc type) {

        static List<ParamSlot> of(MethodModel m, boolean isStatic) {
            var result = new ArrayList<ParamSlot>();
            int slot = isStatic ? 0 : 1; // statisch: kein this-Slot
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
            else if (type.equals(CD_int) || type.equals(CD_boolean)
                    || type.equals(CD_byte) || type.equals(CD_char)
                    || type.equals(CD_short)) cb.iload(slot);
            else cb.aload(slot);
        }

        boolean isPrimitive() {return type.isPrimitive();}

        ClassDesc boxed() {
            if (type.equals(CD_boolean)) return ClassDesc.of("java.lang.Boolean");
            if (type.equals(CD_byte)) return ClassDesc.of("java.lang.Byte");
            if (type.equals(CD_char)) return ClassDesc.of("java.lang.Character");
            if (type.equals(CD_short)) return ClassDesc.of("java.lang.Short");
            if (type.equals(CD_int)) return ClassDesc.of("java.lang.Integer");
            if (type.equals(CD_long)) return ClassDesc.of("java.lang.Long");
            if (type.equals(CD_float)) return ClassDesc.of("java.lang.Float");
            if (type.equals(CD_double)) return ClassDesc.of("java.lang.Double");
            throw new IllegalStateException("kein primitiver Typ: " + type);
        }
    }
}

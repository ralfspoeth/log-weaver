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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.lang.constant.ConstantDescs.*;

@Mojo(
        name                         = "weave",
        defaultPhase                 = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE
)
public class LogWeaver extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private Path classesDir;

    // ── Konstanten ───────────────────────────────────────────────────────────
    private static final ClassDesc LOG_ANN     = ClassDesc.of("com.example.annotations.Log");
    private static final ClassDesc LOGGER_CD   = ClassDesc.of("java.lang.System$Logger");
    private static final ClassDesc LEVEL_CD    = ClassDesc.of("java.lang.System$Logger$Level");
    private static final ClassDesc STRING_CD   = ClassDesc.of("java.lang.String");
    private static final ClassDesc OBJECT_CD   = ClassDesc.of("java.lang.Object");
    private static final ClassDesc CLASS_CD    = ClassDesc.of("java.lang.Class");
    private static final ClassDesc SYSTEM_CD   = ClassDesc.of("java.lang.System");
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

    // ── execute ──────────────────────────────────────────────────────────────
    @Override
    public void execute() throws MojoExecutionException {

        if (!Files.isDirectory(classesDir)) {
            getLog().info("LogWeaver: " + classesDir + " existiert nicht, übersprungen.");
            return;
        }

        int[] stats = {0, 0}; // [geprüft, transformiert]

        try (var stream = Files.walk(classesDir)) {
            List<Path> failed = stream
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().startsWith("package-info"))
                    .filter(p -> !p.getFileName().toString().startsWith("module-info"))
                    .filter(p -> !processClass(p, stats))
                    .toList();

            if (!failed.isEmpty()) {
                failed.forEach(p -> getLog().error("LogWeaver: Fehler in " + p));
                throw new MojoExecutionException(
                        "LogWeaver: " + failed.size() + " Klasse(n) konnten nicht transformiert werden.");
            }

        } catch (MojoExecutionException e) {
            throw e;
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "LogWeaver: Fehler beim Durchsuchen von " + classesDir, e);
        }

        getLog().info(String.format(
                "LogWeaver: %d Klassen geprüft, %d transformiert.", stats[0], stats[1]));
    }

    // ── Einzelne .class-Datei ────────────────────────────────────────────────
    private boolean processClass(Path classFile, int[] stats) {
        stats[0]++;
        try {
            byte[] original    = Files.readAllBytes(classFile);
            byte[] transformed = tryTransform(original);
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
    // tryTransform anpassen: synthetische Methoden sammeln und per ClassTransform anhängen
    private byte[] tryTransform(byte[] original) {
        ClassFile  cf = ClassFile.of();
        ClassModel cm = cf.parse(original);

        if (cm.methods().stream().noneMatch(LogWeaver::hasLogAnnotation))
            return original;

        // Synthetische Methoden werden während der Transformation gesammelt
        List<MethodModel> syntheticMethods = new ArrayList<>();

        byte[] result = cf.transformClass(cm,
                ClassTransform.transformingMethods(
                        (mb, element) -> transformMethod(mb, element, syntheticMethods)
                )
        );

        // Falls synthetische Methoden hinzugekommen sind: nochmal transformieren
        // und die neuen Methoden anhängen
        if (!syntheticMethods.isEmpty()) {
            ClassModel cm2 = cf.parse(result);
            result = cf.transformClass(cm2,
                    ClassTransform.endHandler(clb ->
                            syntheticMethods.forEach(clb::accept)
                    )
            );
        }

        return result;
    }

    private void transformMethod(MethodBuilder mb, MethodElement element,
                                 List<MethodModel> syntheticMethods) {
        if (!(element instanceof CodeModel code)) { mb.with(element); return; }

        var logInfo = readLogAnnotation(code);
        if (logInfo.isEmpty()) { mb.with(element); return; }

        LogInfo         info   = logInfo.get();
        MethodModel     mm     = code.parent().orElseThrow();
        List<ParamSlot> params = ParamSlot.of(mm);
        String          implName = "lambda$logweaver$" + mm.methodName().stringValue();

        mb.withCode(cb -> {
            // ... (unverändert bis einschließlich code.forEach(cb))
        });

        // Synthetische Impl-Methode als MethodModel bauen und in die Liste legen
        List<ClassDesc> implParams = params.stream()
                .map(p -> p.isPrimitive() ? p.boxed() : p.type())
                .toList();

        ClassFile cf = ClassFile.of();
        // Temporäre Hilfsklasse um eine einzelne Methode als bytes zu bauen,
        // die wir dann als MethodModel parsen können
        byte[] helperClass = cf.build(ClassDesc.of("$Helper$"),
                clb -> clb.withMethod(implName,
                        MethodTypeDesc.of(STRING_CD, implParams),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                        xmb -> xmb.withCode(xcb -> {
                            xcb.ldc(info.message());
                            xcb.ldc(implParams.size());
                            xcb.anewarray(OBJECT_CD);
                            for (int i = 0; i < implParams.size(); i++) {
                                xcb.dup();
                                xcb.ldc(i);
                                xcb.aload(i);
                                xcb.aastore();
                            }
                            xcb.invokevirtual(STRING_CD, "formatted",
                                    MethodTypeDesc.of(STRING_CD,
                                            ClassDesc.of("[Ljava/lang/Object;")));
                            xcb.areturn();
                        })
                )
        );

        // MethodModel aus der Hilfsklasse extrahieren und merken
        cf.parse(helperClass).methods().stream()
                .filter(m -> m.methodName().stringValue().equals(implName))
                .findFirst()
                .ifPresent(syntheticMethods::add);
    }

    // ── Annotation lesen ─────────────────────────────────────────────────────
    private static boolean hasLogAnnotation(MethodModel m) {
        return m.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(attr -> attr.annotations().stream()
                        .anyMatch(a -> a.classSymbol().equals(LOG_ANN)))
                .orElse(false);
    }

    private static Optional<LogInfo> readLogAnnotation(CodeModel code) {
        return code.parent()
                .flatMap(m -> m.findAttribute(Attributes.runtimeVisibleAnnotations()))
                .flatMap(attr -> attr.annotations().stream()
                        .filter(a -> a.classSymbol().equals(LOG_ANN))
                        .findFirst())
                .map(LogWeaver::extractLogInfo);
    }

    private static LogInfo extractLogInfo(Annotation ann) {
        String message   = "";
        String levelName = "INFO";
        for (AnnotationElement el : ann.elements()) {
            switch (el.name().stringValue()) {
                case "value" -> { if (el.value() instanceof AnnotationValue.OfString s)
                    message = s.stringValue(); }
                case "level" -> { if (el.value() instanceof AnnotationValue.OfEnum e)
                    levelName = e.constantName().stringValue(); }
            }
        }
        return new LogInfo(message, levelName);
    }

    record LogInfo(String message, String levelName) {}

    // ── Slot-Berechnung ──────────────────────────────────────────────────────
    private static int nextFreeSlot(MethodModel mm, List<ParamSlot> params) {
        return params.stream()
                .mapToInt(p -> (p.type().equals(CD_long) || p.type().equals(CD_double)) ? 2 : 1)
                .sum() + 1; // +1 für this
    }

    record ParamSlot(int slot, ClassDesc type) {

        static List<ParamSlot> of(MethodModel m) {
            var result = new ArrayList<ParamSlot>();
            int slot = 1;
            for (ClassDesc p : m.methodTypeSymbol().parameterList()) {
                result.add(new ParamSlot(slot, p));
                slot += (p.equals(CD_long) || p.equals(CD_double)) ? 2 : 1;
            }
            return result;
        }

        void load(CodeBuilder cb) {
            if      (type.equals(CD_long))                        cb.lload(slot);
            else if (type.equals(CD_double))                      cb.dload(slot);
            else if (type.equals(CD_float))                       cb.fload(slot);
            else if (type.equals(CD_int) || type.equals(CD_boolean)
                    || type.equals(CD_byte) || type.equals(CD_char)
                    || type.equals(CD_short))                       cb.iload(slot);
            else                                                   cb.aload(slot);
        }

        boolean isPrimitive() { return type.isPrimitive(); }

        ClassDesc boxed() {
            if (type.equals(CD_boolean)) return ClassDesc.of("java.lang.Boolean");
            if (type.equals(CD_byte))    return ClassDesc.of("java.lang.Byte");
            if (type.equals(CD_char))    return ClassDesc.of("java.lang.Character");
            if (type.equals(CD_short))   return ClassDesc.of("java.lang.Short");
            if (type.equals(CD_int))     return ClassDesc.of("java.lang.Integer");
            if (type.equals(CD_long))    return ClassDesc.of("java.lang.Long");
            if (type.equals(CD_float))   return ClassDesc.of("java.lang.Float");
            if (type.equals(CD_double))  return ClassDesc.of("java.lang.Double");
            throw new IllegalStateException("kein primitiver Typ: " + type);
        }
    }
}
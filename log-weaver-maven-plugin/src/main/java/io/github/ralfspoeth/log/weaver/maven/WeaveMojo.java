package io.github.ralfspoeth.log.weaver.maven;

import io.github.ralfspoeth.log.weaver.core.LogWeaverCore;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Maven plugin entry point. Delegates the actual transformation to
 * {@link LogWeaverCore#weaveDirectory(Path)} and surfaces per-class failures
 * collected in {@link LogWeaverCore.WeaveStats#errors()} as a single
 * {@link MojoExecutionException} after logging each failing path.
 */
@Mojo(name = "weave",
        defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class WeaveMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private Path classesDir;

    /** Test-only setter — Maven injects {@link #classesDir} directly into the field. */
    public void setClassesDir(Path classesDir) {
        this.classesDir = classesDir;
    }

    @Override
    public void execute() throws MojoExecutionException {
        if (classesDir == null) {
            throw new MojoExecutionException("LogWeaver: classesDir is not configured");
        }

        LogWeaverCore.WeaveStats stats;
        try {
            stats = LogWeaverCore.weaveDirectory(classesDir);
        } catch (IOException e) {
            throw new MojoExecutionException("LogWeaver: error walking " + classesDir, e);
        }

        if (!stats.isClean()) {
            for (Map.Entry<Path, Throwable> e : stats.errors().entrySet()) {
                getLog().error("LogWeaver: error in " + e.getKey() + ": " + e.getValue().getMessage(),
                        e.getValue());
            }
            throw new MojoExecutionException(
                    "LogWeaver: " + stats.errors().size() + " class(es) could not be transformed.");
        }

        getLog().info(String.format("LogWeaver: %d classes checked, %d transformed.",
                stats.checked(), stats.transformed()));
    }
}

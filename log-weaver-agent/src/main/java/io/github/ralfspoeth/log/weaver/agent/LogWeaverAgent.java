package io.github.ralfspoeth.log.weaver.agent;

import io.github.ralfspoeth.log.weaver.core.LogAllConfig;
import io.github.ralfspoeth.log.weaver.core.LogWeaverCore;
import io.github.ralfspoeth.log.weaver.core.Scopes;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Java agent entry point. The agent installs a {@link ClassFileTransformer}
 * that runs {@link LogWeaverCore#transformClass(byte[], Scopes)} per loaded
 * class.
 *
 * <p>Use it by passing {@code -javaagent:/path/to/log-weaver-agent.jar} to
 * the JVM. The packaged jar shades {@code log-weaver-core}, so no additional
 * classpath entry is needed for the agent itself; {@code log-api} only needs
 * to be on the application classpath (for the annotations, plus the
 * reflective default-loading the core performs at startup).</p>
 *
 * <h2>Scope resolution</h2>
 * The Maven plugin pre-scans the whole classes output directory for
 * {@code module-info.class} / {@code package-info.class}. The agent can't —
 * classes are streamed in one at a time. Instead, when transforming class
 * {@code p.Q}, the agent asks the class's classloader for the resource
 * {@code p/package-info.class} and (for a named module) {@code module-info.class},
 * caches the resulting {@link LogAllConfig}s per {@code (loader, name)} pair,
 * and constructs a per-call {@link Scopes} with just those two entries.
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>JDK / system / agent classes are skipped unconditionally.</li>
 *   <li>{@code -javaagent} can't transform classes loaded before the agent
 *       was attached (typically a non-issue for application code).</li>
 *   <li>Exceptions thrown during a single class's transformation are logged
 *       to {@code System.err} and the class is then loaded unchanged.</li>
 * </ul>
 */
public final class LogWeaverAgent {

    private LogWeaverAgent() {}

    /** Called by the JVM when the agent is attached via {@code -javaagent}. */
    public static void premain(String args, Instrumentation inst) {
        attach(inst);
    }

    /** Called by the JVM for dynamic agent attach (jcmd / Attach API). */
    public static void agentmain(String args, Instrumentation inst) {
        attach(inst);
    }

    private static void attach(Instrumentation inst) {
        inst.addTransformer(new WeavingTransformer(), /* canRetransform */ false);
    }

    // ── Transformer ──────────────────────────────────────────────────────────

    private static final class WeavingTransformer implements ClassFileTransformer {

        // Per-(loader, name) caches; Optional.empty() = looked it up, nothing found.
        private final ConcurrentMap<CacheKey, Optional<LogAllConfig>> packageCache = new ConcurrentHashMap<>();
        private final ConcurrentMap<CacheKey, Optional<LogAllConfig>> moduleCache = new ConcurrentHashMap<>();

        @Override
        public byte[] transform(Module module,
                                ClassLoader loader,
                                String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            if (className == null) return null;
            if (isSystemOrSelf(className)) return null;

            try {
                Scopes scopes = resolveScopes(module, loader, className);
                byte[] result = LogWeaverCore.transformClass(classfileBuffer, scopes);
                // Reference-equality contract: same array back means "no change".
                return result == classfileBuffer ? null : result;
            } catch (Throwable t) {
                // Never let a transformer error abort classloading — log and pass through.
                System.err.println("LogWeaverAgent: error transforming " + className + ": " + t);
                return null;
            }
        }

        private Scopes resolveScopes(Module module, ClassLoader loader, String internalName) {
            String pkg = packageOf(internalName);

            Optional<LogAllConfig> mod = (module != null && module.isNamed())
                    ? moduleCache.computeIfAbsent(
                            new CacheKey(loaderId(loader), module.getName()),
                            k -> readResource(module, loader, "module-info.class"))
                    : Optional.empty();

            Optional<LogAllConfig> pkgCfg = pkg.isEmpty()
                    ? Optional.empty()
                    : packageCache.computeIfAbsent(
                            new CacheKey(loaderId(loader), pkg),
                            k -> readResource(module, loader,
                                    pkg.replace('.', '/') + "/package-info.class"));

            Map<String, LogAllConfig> byPackage = pkgCfg
                    .map(c -> Map.of(pkg, c))
                    .orElse(Map.of());
            return new Scopes(mod, byPackage);
        }

        /**
         * Read a resource by name. Prefers the named module's own resource
         * stream when available (works for strict module encapsulation);
         * falls back to the classloader otherwise.
         */
        private static Optional<LogAllConfig> readResource(Module module, ClassLoader loader, String resource) {
            byte[] bytes = null;
            try {
                if (module != null && module.isNamed()) {
                    try (var in = module.getResourceAsStream(resource)) {
                        if (in != null) bytes = in.readAllBytes();
                    }
                }
                if (bytes == null) {
                    ClassLoader cl = loader != null ? loader : ClassLoader.getSystemClassLoader();
                    try (var in = cl.getResourceAsStream(resource)) {
                        if (in != null) bytes = in.readAllBytes();
                    }
                }
            } catch (IOException ignore) {
                return Optional.empty();
            }
            if (bytes == null) return Optional.empty();
            try {
                return LogWeaverCore.readScopeConfig(bytes);
            } catch (Throwable t) {
                return Optional.empty();
            }
        }

        private static boolean isSystemOrSelf(String internalName) {
            return internalName.startsWith("java/")
                    || internalName.startsWith("jdk/")
                    || internalName.startsWith("sun/")
                    || internalName.startsWith("com/sun/")
                    || internalName.startsWith("io/github/ralfspoeth/log/");
        }

        private static String packageOf(String internalName) {
            int slash = internalName.lastIndexOf('/');
            return slash < 0 ? "" : internalName.substring(0, slash).replace('/', '.');
        }

        /**
         * A stable hash code for the loader is needed to key the cache — but the
         * loader itself is referenced by identity to avoid leaking classloaders
         * across redeploys; the cache lifetime is the agent's, so this is fine.
         */
        private static Object loaderId(ClassLoader loader) {
            return loader == null ? "bootstrap" : loader;
        }
    }

    private record CacheKey(Object loader, String name) {
        CacheKey {
            Objects.requireNonNull(loader);
            Objects.requireNonNull(name);
        }
    }
}

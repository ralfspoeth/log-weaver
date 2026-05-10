package io.github.ralfspoeth.log.weaver.core;

import java.util.Map;
import java.util.Optional;

/**
 * Pre-collected {@code @LogAll} configurations available to a single
 * transformation. The module entry, if present, applies to every class in
 * that module; {@link #byPackage()} maps a package name to its
 * {@code package-info.class} configuration, if any.
 *
 * <p>The Maven plugin populates this once per build, by walking the classes
 * output directory. The agent constructs a per-class instance on demand,
 * resolving the two relevant lookups via {@link ClassLoader#getResourceAsStream}.</p>
 */
public record Scopes(Optional<LogAllConfig> module, Map<String, LogAllConfig> byPackage) {

    public Scopes {
        byPackage = Map.copyOf(byPackage);
    }

    /** A {@code Scopes} with no module-level and no package-level configuration. */
    public static Scopes empty() {
        return EMPTY;
    }

    private static final Scopes EMPTY = new Scopes(Optional.empty(), Map.of());
}

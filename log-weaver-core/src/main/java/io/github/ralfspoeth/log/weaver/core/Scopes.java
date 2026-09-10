package io.github.ralfspoeth.log.weaver.core;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pre-collected {@code @LogAll} configurations available to a single
 * transformation. The module entry, if present, applies to every class in
 * that module; {@link #byPackage()} maps a package name to its
 * {@code package-info.class} configuration, if any.
 *
 * <p>The Maven plugin populates this once per build, by walking the classes
 * output directory. The agent constructs a per-class instance on demand,
 * resolving the two relevant lookups via {@link ClassLoader#getResourceAsStream}.</p>
 *
 * @param sealedPermits descriptor strings ({@code "Lcom/example/Foo;"}) of every
 *                      class named in some sealed type's {@code PermittedSubclasses}
 *                      attribute anywhere in the scanned tree. A class cannot tell
 *                      from its own bytes that it is a permitted subtype - the fact
 *                      lives in its supertype - so it is collected in the same pass
 *                      that reads the {@code @LogAll} scopes and consulted when one
 *                      of those would otherwise sweep it up. Empty where the caller
 *                      sees one class at a time, as the agent does, in which case
 *                      that exclusion simply does not fire.
 */
public record Scopes(Optional<LogAllConfig> module,
                     Map<String, LogAllConfig> byPackage,
                     Set<String> sealedPermits) {

    public Scopes {
        byPackage = Map.copyOf(byPackage);
        sealedPermits = Set.copyOf(sealedPermits);
    }

    /** A {@code Scopes} with no module-level and no package-level configuration. */
    public static Scopes empty() {
        return EMPTY;
    }

    private static final Scopes EMPTY = new Scopes(Optional.empty(), Map.of(), Set.of());
}

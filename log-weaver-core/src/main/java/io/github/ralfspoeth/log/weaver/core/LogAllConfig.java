package io.github.ralfspoeth.log.weaver.core;

import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.util.regex.Pattern;

/**
 * Resolved form of a {@code @LogAll} annotation: which methods it applies to
 * and at what level. Constructed from a parsed {@code module-info.class},
 * {@code package-info.class}, or class-level annotation; see
 * {@link LogWeaverCore#readScopeConfig(byte[])}.
 *
 * @param modifiers     {@code 0} means "any visibility" (the {@code @LogAll}
 *                      default); otherwise OR-semantics — at least one of
 *                      the access-flag bits must be set.
 * @param levelName     name of the {@code java.lang.System.Logger.Level} enum
 *                      constant to use for the entry log.
 * @param methodPattern {@code java.util.regex} pattern (default {@code ".*"}).
 */
public record LogAllConfig(int modifiers, String levelName, String methodPattern) {

    /**
     * Does this {@code @LogAll} configuration apply to the given method?
     * Excludes {@code <init>} / {@code <clinit>}, abstract / native / bridge /
     * synthetic methods, and methods that don't satisfy {@code modifiers} or
     * {@code methodPattern}.
     */
    public boolean matches(MethodModel mm) {
        String name = mm.methodName().stringValue();
        if (name.equals("<init>") || name.equals("<clinit>")) return false;

        int flags = mm.flags().flagsMask();
        int skip = ClassFile.ACC_ABSTRACT | ClassFile.ACC_NATIVE | ClassFile.ACC_BRIDGE | ClassFile.ACC_SYNTHETIC;
        if ((flags & skip) != 0) return false;

        if (modifiers != 0 && (flags & modifiers) == 0) return false;

        return Pattern.matches(methodPattern, name);
    }
}

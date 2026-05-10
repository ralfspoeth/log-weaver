package io.github.ralfspoeth.log.api;

import java.lang.System.Logger.Level;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Scope-wide weaving directive. Placed on a type, a {@code package-info}, or
 * a {@code module-info}, it tells the weaver to emit an entry log at
 * {@link #level()} for every method in scope that matches
 * {@link #modifiers()} and {@link #methodPattern()}. Resolution is
 * most-specific-wins: type → package → module.
 *
 * <p>{@code @LogAll} only drives the entry log; the always-on exception
 * handler is installed at the {@link Log#exceptionLevel()} default
 * ({@link Level#WARNING}). To turn return-logging on or to override the
 * exception level for a specific method, place an explicit {@link Log}
 * annotation on it.</p>
 */
@Target({ElementType.TYPE, ElementType.PACKAGE, ElementType.MODULE})
@Retention(RetentionPolicy.RUNTIME)
public @interface LogAll {

    /**
     * Access-flag mask the method must satisfy.
     * <p>{@code 0} (the default) means "any visibility, including package-private".
     * Any non-zero value applies OR-semantics: at least one of the requested
     * bits must be set on the method.</p>
     */
    int modifiers() default 0;

    /** Level used for the entry log on every matched method. */
    Level level() default Level.INFO;

    /** {@code java.util.regex} pattern the method name must match. */
    String methodPattern() default ".*";
}

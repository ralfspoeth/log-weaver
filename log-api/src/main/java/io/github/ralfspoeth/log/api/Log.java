package io.github.ralfspoeth.log.api;

import java.lang.System.Logger.Level;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for weaving. The weaver emits exactly one parameter-aware
 * log call — either before the body (when {@link #logReturn()} is {@code false},
 * the default) or before each {@code XRETURN} (when {@link #logReturn()} is
 * {@code true}) — and an always-on {@code Throwable} catch that logs the
 * exception at {@link #exceptionLevel()} and rethrows.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Log {

    /**
     * Level used for the entry log (when {@link #logReturn()} is {@code false})
     * or the return log (when {@code true}).
     */
    Level level() default Level.INFO;

    /**
     * If {@code true}, emit a return log capturing parameters plus the return
     * value, instead of an entry log capturing just the parameters.
     */
    boolean logReturn() default true;

    /**
     * Level used by the always-on {@code Throwable} catch. {@link Level#OFF}
     * keeps the catch in place but lets the JDK discard the log record.
     */
    Level exceptionLevel() default Level.WARNING;
}

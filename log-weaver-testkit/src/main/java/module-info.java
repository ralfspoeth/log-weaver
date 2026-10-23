import org.jspecify.annotations.NullMarked;

/**
 * Test support for log-weaver: a recording {@link java.lang.System.LoggerFinder}
 * that captures what woven code logs.
 *
 * <p>The {@code provides} clause lives here rather than in the consuming
 * project, because a service provider is declared by the module that supplies
 * it. A consumer therefore only needs the test-scoped dependency and a
 * {@code requires} on this module &mdash; no {@code provides} of its own.
 */
@NullMarked
module io.github.ralfspoeth.log.weaver.testkit {
    requires static org.jspecify;
    exports io.github.ralfspoeth.log.weaver.testkit;
    provides java.lang.System.LoggerFinder
            with io.github.ralfspoeth.log.weaver.testkit.RecordingLoggerFinder;
}

package io.github.ralfspoeth.log.weaver.testkit;

import java.util.List;
import java.util.Queue;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A {@link System.LoggerFinder} that records every log call, so a test can
 * assert on what the weaver emitted without going through a logging backend.
 *
 * <p>Registered as a service by this module, so a consumer adds the test-scoped
 * dependency, puts {@code requires io.github.ralfspoeth.log.weaver.testkit} in
 * its test {@code module-info}, and is done. On the class path instead of the
 * module path, the usual
 * {@code META-INF/services/java.lang.System$LoggerFinder} file applies.
 *
 * <h2>Why the state is static</h2>
 * The JDK resolves a {@code LoggerFinder} <em>once</em> per JVM and caches it,
 * and build tools commonly reuse one JVM across test classes. There is
 * therefore exactly one instance for a whole run, and no way to swap it between
 * tests. State is static as a consequence, and a test declares what it wants by
 * calling {@link #reset(System.Logger.Level)} first &mdash; typically from a
 * per-test setup method.
 *
 * <p>Two further consequences worth knowing:
 * <ul>
 * <li>This replaces the logging backend for the <em>entire</em> test JVM, so
 * records from other libraries land here too. Filter by
 * {@link Entry#logger()} when that matters.</li>
 * <li>A second {@code LoggerFinder} provider on the module path makes the JDK
 * raise a {@link java.util.ServiceConfigurationError}. There can be only one.</li>
 * </ul>
 *
 * <h2>What gets captured</h2>
 * {@link System.Logger} declares just two abstract {@code log} methods, and
 * every convenience overload defaults onto them &mdash; including the two the
 * weaver emits: {@code log(Level, Supplier)} for the parameter-aware message
 * and {@code log(Level, String, Throwable)} for the always-on exception catch.
 * Capturing those two therefore captures everything.
 *
 * <p>Note that the lazy message {@code Supplier} is only evaluated once
 * {@link System.Logger#isLoggable} passes, which is handled by those default
 * methods. A level below the current threshold produces no entry and never
 * builds its message.
 * {@snippet :
 * @BeforeEach
 * void quiet() {
 *     RecordingLoggerFinder.reset(System.Logger.Level.DEBUG);
 * }
 *
 * @Test
 * void theWovenCallIsLogged() {
 *     new Subject().greet("world");
 *     assertTrue(RecordingLoggerFinder.messagesAt(System.Logger.Level.DEBUG)
 *             .stream()
 *             .anyMatch(m -> m.contains("greet") && m.contains("world")));
 * }
 *}
 */
public final class RecordingLoggerFinder extends System.LoggerFinder {

    /**
     * One captured log call, exactly as the logger received it and before any
     * formatting.
     *
     * @param logger  the logger's name, which for woven code is the name of the
     *                class that was woven
     * @param level   the level the call was made at
     * @param message the message; for the weaver's exception catch this is the
     *                throwable's own {@code getMessage()}
     * @param thrown  the throwable passed alongside, or {@code null}
     */
    public record Entry(String logger, System.Logger.Level level, String message, Throwable thrown) {

        /** {@code true} when this entry carries a {@code thrown} of the given type. */
        public boolean threw(Class<? extends Throwable> type) {
            return type.isInstance(thrown);
        }
    }

    // Append-only and possibly hot: woven code may log from many threads, and a
    // consumer's concurrency test can produce a lot of entries. A queue appends
    // in O(1); a copy-on-write list would copy the whole backing array per entry
    // and cost O(n^2) overall, to buy a point-in-time snapshot that assertions
    // running after the work has finished do not need.
    private static final Queue<Entry> ENTRIES = new ConcurrentLinkedQueue<>();

    // volatile for the same reason.
    private static volatile System.Logger.Level threshold = System.Logger.Level.ALL;

    /**
     * Discard everything recorded so far and record at {@code level} and above.
     * {@link System.Logger.Level#ALL} records everything,
     * {@link System.Logger.Level#OFF} nothing.
     */
    public static void reset(System.Logger.Level level) {
        ENTRIES.clear();
        threshold = level;
    }

    /** Everything recorded since the last {@link #reset}, in order. */
    public static List<Entry> entries() {
        return List.copyOf(ENTRIES);
    }

    /** Entries recorded at exactly {@code level}. */
    public static List<Entry> at(System.Logger.Level level) {
        return ENTRIES.stream().filter(e -> e.level() == level).toList();
    }

    /** Messages recorded at exactly {@code level}. */
    public static List<String> messagesAt(System.Logger.Level level) {
        return at(level).stream().map(Entry::message).toList();
    }

    /** Entries recorded by the logger of the given class. */
    public static List<Entry> from(Class<?> wovenClass) {
        return ENTRIES.stream().filter(e -> e.logger().equals(wovenClass.getName())).toList();
    }

    @Override
    public System.Logger getLogger(String name, Module module) {
        return new RecordingLogger(name);
    }

    private record RecordingLogger(String name) implements System.Logger {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isLoggable(Level level) {
            // ALL sits below every real level and OFF above all of them, so a
            // plain severity comparison yields "record everything" and "record
            // nothing" without special cases.
            return level.getSeverity() >= threshold.getSeverity();
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            record(level, msg, thrown);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            record(level, format, null);
        }

        private void record(Level level, String msg, Throwable thrown) {
            if (isLoggable(level)) {
                ENTRIES.add(new Entry(name, level, msg, thrown));
            }
        }
    }
}

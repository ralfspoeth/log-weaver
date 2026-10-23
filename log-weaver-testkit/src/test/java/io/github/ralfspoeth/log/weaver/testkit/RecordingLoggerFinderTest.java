package io.github.ralfspoeth.log.weaver.testkit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.System.Logger.Level;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The recorder is itself the thing tests depend on, so it gets its own tests.
 *
 * <p>The finder is installed JVM-wide, which means records from anything else
 * running in this JVM land in the same store. Every assertion here therefore
 * filters by a logger name of its own rather than counting the store as a whole.
 */
class RecordingLoggerFinderTest {

    @BeforeEach
    void recordEverything() {
        RecordingLoggerFinder.reset(Level.ALL);
    }

    private static List<RecordingLoggerFinder.Entry> from(String logger) {
        return RecordingLoggerFinder.entries().stream()
                .filter(e -> e.logger().equals(logger))
                .toList();
    }

    // ---- registration ----------------------------------------------------

    @Test
    @DisplayName("The module's provides clause installs this finder")
    void theServiceIsRegistered() {
        // The whole ergonomic claim of this module - add the dependency and a
        // requires, nothing else - rests on the provides clause, so assert it
        // rather than assuming it.
        assertAll(
                () -> assertInstanceOf(RecordingLoggerFinder.class,
                        System.LoggerFinder.getLoggerFinder()),
                () -> assertInstanceOf(System.Logger.class,
                        System.getLogger("registered.check"),
                        "System.getLogger goes through the installed finder")
        );
    }

    @Test
    @DisplayName("A logger reports the name it was asked for")
    void loggerKeepsItsName() {
        assertEquals("some.logger", System.getLogger("some.logger").getName());
    }

    // ---- what gets captured ----------------------------------------------

    @Test
    @DisplayName("The throwable overload records message and throwable")
    void recordsMessageAndThrowable() {
        var boom = new IllegalStateException("boom");
        System.getLogger("throwing").log(Level.WARNING, "it failed", boom);

        var entries = from("throwing");
        assertAll(
                () -> assertEquals(1, entries.size()),
                () -> assertEquals(Level.WARNING, entries.getFirst().level()),
                () -> assertEquals("it failed", entries.getFirst().message()),
                () -> assertSame(boom, entries.getFirst().thrown()),
                () -> assertTrue(entries.getFirst().threw(IllegalStateException.class)),
                () -> assertTrue(entries.getFirst().threw(RuntimeException.class),
                        "threw() accepts a supertype"),
                () -> assertFalse(entries.getFirst().threw(IllegalArgumentException.class))
        );
    }

    @Test
    @DisplayName("The lazy overload records the built message and no throwable")
    void recordsTheLazyMessage() {
        System.getLogger("lazy").log(Level.DEBUG, () -> "built lazily");

        var entries = from("lazy");
        assertAll(
                () -> assertEquals(1, entries.size()),
                () -> assertEquals(Level.DEBUG, entries.getFirst().level()),
                () -> assertEquals("built lazily", entries.getFirst().message()),
                () -> assertNull(entries.getFirst().thrown()),
                () -> assertFalse(entries.getFirst().threw(Throwable.class),
                        "threw() is false when nothing was thrown")
        );
    }

    @Test
    @DisplayName("Entries keep the order they were logged in")
    void entriesKeepInsertionOrder() {
        var logger = System.getLogger("ordered");
        logger.log(Level.INFO, () -> "first");
        logger.log(Level.INFO, () -> "second");
        logger.log(Level.INFO, () -> "third");

        assertEquals(List.of("first", "second", "third"),
                from("ordered").stream().map(RecordingLoggerFinder.Entry::message).toList());
    }

    // ---- the level gate --------------------------------------------------

    @Test
    @DisplayName("A level below the threshold is not recorded")
    void aLevelBelowTheThresholdIsDropped() {
        RecordingLoggerFinder.reset(Level.WARNING);
        var logger = System.getLogger("gated");
        logger.log(Level.DEBUG, () -> "debug");
        logger.log(Level.INFO, () -> "info");
        logger.log(Level.WARNING, () -> "warning");
        logger.log(Level.ERROR, () -> "error");

        assertEquals(List.of("warning", "error"),
                from("gated").stream().map(RecordingLoggerFinder.Entry::message).toList());
    }

    @Test
    @DisplayName("A suppressed level never builds its message")
    void aSuppressedLevelNeverBuildsItsMessage() {
        RecordingLoggerFinder.reset(Level.WARNING);
        var built = new AtomicBoolean();

        System.getLogger("never-built").log(Level.DEBUG, () -> {
            built.set(true);
            return "expensive";
        });

        // System.Logger's default log(Level, Supplier) consults isLoggable
        // before calling the supplier. That is what makes the weaver's
        // invokedynamic message building free when a level is off, so it is
        // worth pinning here rather than trusting it.
        assertAll(
                () -> assertFalse(built.get(), "the Supplier must not be invoked"),
                () -> assertTrue(from("never-built").isEmpty())
        );
    }

    @Test
    @DisplayName("OFF records nothing, ALL records everything")
    void offAndAllAreTheExtremes() {
        RecordingLoggerFinder.reset(Level.OFF);
        var logger = System.getLogger("extremes");
        logger.log(Level.ERROR, "even an error", new RuntimeException("x"));
        assertTrue(from("extremes").isEmpty(), "OFF discards even ERROR");

        RecordingLoggerFinder.reset(Level.ALL);
        logger.log(Level.TRACE, () -> "even a trace");
        assertEquals(1, from("extremes").size(), "ALL keeps even TRACE");
    }

    @Test
    @DisplayName("isLoggable agrees with what is recorded")
    void isLoggableAgreesWithRecording() {
        RecordingLoggerFinder.reset(Level.INFO);
        var logger = System.getLogger("loggable");
        assertAll(
                () -> assertFalse(logger.isLoggable(Level.TRACE)),
                () -> assertFalse(logger.isLoggable(Level.DEBUG)),
                () -> assertTrue(logger.isLoggable(Level.INFO)),
                () -> assertTrue(logger.isLoggable(Level.WARNING)),
                () -> assertTrue(logger.isLoggable(Level.ERROR))
        );
    }

    // ---- the query helpers -----------------------------------------------

    @Test
    @DisplayName("at() filters by exact level, messagesAt() maps to messages")
    void atFiltersByExactLevel() {
        var logger = System.getLogger("filtered");
        logger.log(Level.DEBUG, () -> "d");
        logger.log(Level.WARNING, () -> "w");

        assertAll(
                () -> assertTrue(RecordingLoggerFinder.at(Level.DEBUG).stream()
                        .anyMatch(e -> "d".equals(e.message()))),
                () -> assertTrue(RecordingLoggerFinder.at(Level.DEBUG).stream()
                                .noneMatch(e -> "w".equals(e.message())),
                        "at(DEBUG) is exact, not a threshold"),
                () -> assertTrue(RecordingLoggerFinder.messagesAt(Level.WARNING).contains("w"))
        );
    }

    @Test
    @DisplayName("from() filters to one woven class")
    void fromFiltersByClass() {
        System.getLogger(RecordingLoggerFinderTest.class.getName()).log(Level.INFO, () -> "mine");
        System.getLogger("somebody.else").log(Level.INFO, () -> "theirs");

        var mine = RecordingLoggerFinder.from(RecordingLoggerFinderTest.class);
        assertAll(
                () -> assertTrue(mine.stream().anyMatch(e -> "mine".equals(e.message()))),
                () -> assertTrue(mine.stream().noneMatch(e -> "theirs".equals(e.message())))
        );
    }

    @Test
    @DisplayName("reset() discards what came before")
    void resetDiscardsEarlierEntries() {
        System.getLogger("discarded").log(Level.INFO, () -> "before");
        assertFalse(from("discarded").isEmpty(), "sanity: it was recorded");

        RecordingLoggerFinder.reset(Level.ALL);
        assertTrue(from("discarded").isEmpty(), "reset clears the store");
    }

    @Test
    @DisplayName("entries() hands out a copy, not the live store")
    void entriesIsACopy() {
        System.getLogger("copied").log(Level.INFO, () -> "one");
        var snapshot = RecordingLoggerFinder.entries();
        System.getLogger("copied").log(Level.INFO, () -> "two");

        assertAll(
                () -> assertEquals(1, snapshot.stream()
                        .filter(e -> e.logger().equals("copied")).count()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> snapshot.add(null),
                        "the returned list is immutable")
        );
    }
}

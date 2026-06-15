package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the position-keyed in-flight tracker. The two internal structures
 * (pending map + generation-position set) must stay in sync on every removal
 * path, because generationCount() directly drives the per-type concurrency
 * gates in LodRequestManager.drainQueue.
 */
class InFlightTrackerTest {

    private static final long GEN_POS = PositionUtil.packPosition(1, 1);
    private static final long SYNC_POS = PositionUtil.packPosition(2, 2);
    private static final long FAR_GEN_POS = PositionUtil.packPosition(100, 0);

    /** A threshold no test waits out: entries stamped "now" never expire against it. */
    private static final long ONE_HOUR_NANOS = TimeUnit.HOURS.toNanos(1);

    private InFlightTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new InFlightTracker();
    }

    @Test
    void markPendingTracksGenerationAndSyncSeparately() {
        tracker.markPending(GEN_POS, System.nanoTime(), true);
        tracker.markPending(SYNC_POS, System.nanoTime(), false);

        assertEquals(2, tracker.size());
        assertEquals(1, tracker.generationCount());
        assertTrue(tracker.isInFlight(GEN_POS));
        assertTrue(tracker.isInFlight(SYNC_POS));
    }

    @Test
    void removeByPositionReleasesBothStructures() {
        tracker.markPending(GEN_POS, System.nanoTime(), true);
        tracker.markPending(SYNC_POS, System.nanoTime(), false);

        tracker.removeByPosition(GEN_POS);
        assertEquals(1, tracker.size());
        assertEquals(0, tracker.generationCount(), "generation slot must be released");
        assertFalse(tracker.isInFlight(GEN_POS));

        tracker.removeByPosition(SYNC_POS);
        assertEquals(0, tracker.size());
    }

    @Test
    void removeByPositionOnUntrackedPositionIsNoOp() {
        tracker.markPending(GEN_POS, System.nanoTime(), true);

        tracker.removeByPosition(SYNC_POS);

        assertEquals(1, tracker.size());
        assertEquals(1, tracker.generationCount());
    }

    @Test
    void timeoutSweepRemovesExpiredFromBothStructures() {
        // Sent two thresholds ago -> deterministically expired in the sweep.
        tracker.markPending(GEN_POS, System.nanoTime() - 2 * ONE_HOUR_NANOS, true);
        tracker.markPending(SYNC_POS, System.nanoTime() - 2 * ONE_HOUR_NANOS, false);
        long freshGen = PositionUtil.packPosition(3, 3);
        tracker.markPending(freshGen, System.nanoTime(), true);

        var evicted = new java.util.HashSet<Long>();
        tracker.timeoutSweep(ONE_HOUR_NANOS, evicted::add);

        assertEquals(1, tracker.size());
        assertEquals(1, tracker.generationCount(), "expired generation slot must be released");
        assertFalse(tracker.isInFlight(GEN_POS));
        assertFalse(tracker.isInFlight(SYNC_POS));
        assertTrue(tracker.isInFlight(freshGen));
        // Every eviction must be reported (the manager marks them for retry — an in-flight
        // position counts as scan-satisfied, so a silent eviction inside a confirmed ring
        // would never be rescanned: the bandwidth-throttle orphan bug).
        assertEquals(java.util.Set.of(GEN_POS, SYNC_POS), evicted);
    }

    @Test
    void pruneOutOfRangeRemovesFromBothStructures() {
        long now = System.nanoTime();
        tracker.markPending(GEN_POS, now, true);     // (1, 1)   - in range
        tracker.markPending(SYNC_POS, now, false);   // (2, 2)   - in range
        tracker.markPending(FAR_GEN_POS, now, true); // (100, 0) - out of range

        tracker.pruneOutOfRange(0, 0, 10);

        assertEquals(2, tracker.size());
        assertEquals(1, tracker.generationCount(), "pruned generation slot must be released");
        assertFalse(tracker.isInFlight(FAR_GEN_POS));
        assertTrue(tracker.isInFlight(GEN_POS));
        assertTrue(tracker.isInFlight(SYNC_POS));
    }

    @Test
    void pruneKeepsPositionsExactlyAtTheBoundary() {
        long boundary = PositionUtil.packPosition(10, -10); // Chebyshev distance exactly 10
        tracker.markPending(boundary, System.nanoTime(), false);

        tracker.pruneOutOfRange(0, 0, 10);

        assertTrue(tracker.isInFlight(boundary), "distance == pruneDistance is in range");
    }

    @Test
    void clearEmptiesBothStructures() {
        tracker.markPending(GEN_POS, System.nanoTime(), true);
        tracker.markPending(SYNC_POS, System.nanoTime(), false);

        tracker.clear();

        assertEquals(0, tracker.size());
        assertEquals(0, tracker.generationCount());
        assertFalse(tracker.isInFlight(GEN_POS));
    }

    /**
     * CL-024 boundary pin. The sweep contract is strictly-greater (age &gt; threshold), but
     * the sweep reads System.nanoTime() internally, so exact age==threshold cannot be staged
     * (the clock advances between markPending and the comparison) — &gt; vs &gt;= is genuinely
     * indistinguishable from outside without a clock seam (see handoff-PKG-A.md). The
     * boundary is therefore pinned from both sides at the tightest stageable offsets:
     * 1 ms past the threshold MUST evict (no hidden slack or grace margin on top of the
     * passed threshold), and an entry well inside the threshold — far from "fresh" at half
     * the threshold's age — MUST survive (the threshold parameter, not a built-in constant,
     * bounds eviction from below).
     */
    @Test
    void timeoutBoundaryEvictsJustPastThresholdAndKeepsEntriesInsideIt() {
        long threshold = ONE_HOUR_NANOS;
        long now = System.nanoTime();
        long justPast = PositionUtil.packPosition(4, 4);
        long wellInside = PositionUtil.packPosition(5, 5);
        tracker.markPending(justPast, now - threshold - TimeUnit.MILLISECONDS.toNanos(1), false);
        tracker.markPending(wellInside, now - threshold + TimeUnit.MINUTES.toNanos(30), true);

        var evicted = new java.util.HashSet<Long>();
        tracker.timeoutSweep(threshold, evicted::add);

        assertEquals(java.util.Set.of(justPast), evicted,
                "exactly the entry 1 ms past the threshold is evicted — no extra slack, no early eviction");
        assertFalse(tracker.isInFlight(justPast));
        assertTrue(tracker.isInFlight(wellInside),
                "a 30-minute-old entry under a 1-hour threshold must never time out");
        assertEquals(1, tracker.generationCount(), "the surviving generation entry keeps its slot");
    }
}

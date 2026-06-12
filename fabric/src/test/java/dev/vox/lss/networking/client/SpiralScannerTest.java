package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct coverage of the scan policy rewritten this release: Chebyshev exclusion (matching
 * vanilla's loaded-chunk square), budgeted ring walking, gen-cap skips, and — regression for
 * a release-review finding — contiguous-prefix ring confirmation: a satisfied OUTER ring
 * must never confirm past an unsatisfied INNER ring, or a stationary player gets a
 * permanent LOD hole.
 */
class SpiralScannerTest {

    private static final int CX = 0;
    private static final int CZ = 0;

    private static SpiralScanner scanner(int lodDistance, int syncLimit, int genLimit) {
        var s = new SpiralScanner();
        s.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                lodDistance, syncLimit, genLimit, true));
        return s;
    }

    /** Drive maybeScan until the 20-tick cadence fires; returns the queued count. */
    private static int fireScan(SpiralScanner s, int viewDistance, ColumnStateMap columns,
                                RequestQueue queue) {
        return fireScan(s, viewDistance, 0, 1000, 0, columns, queue);
    }

    /** fireScan with explicit budget-scale inputs (column queue fill, missing vanilla chunks). */
    private static int fireScan(SpiralScanner s, int viewDistance, int columnQueueSize,
                                int columnQueueHaltThreshold, int missingVanilla,
                                ColumnStateMap columns, RequestQueue queue) {
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND + 1; i++) {
            int n = s.maybeScan(CX, CZ, viewDistance, columnQueueSize, columnQueueHaltThreshold,
                    () -> missingVanilla, columns, pos -> false, queue);
            if (n >= 0) return n;
        }
        throw new AssertionError("scan cadence never fired");
    }

    private static List<Long> drain(RequestQueue queue) {
        var out = new ArrayList<Long>();
        while (queue.hasNext()) {
            out.add(queue.peekPosition());
            queue.skip();
        }
        return out;
    }

    @Test
    void exclusionIsChebyshevMatchingVanillasLoadedSquare() {
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        int queued = fireScan(s, 2, new ColumnStateMap(), queue);

        assertTrue(queued > 0);
        for (long packed : drain(queue)) {
            int cheb = Math.max(Math.abs(PositionUtil.unpackX(packed) - CX),
                    Math.abs(PositionUtil.unpackZ(packed) - CZ));
            assertTrue(cheb > 2 && cheb <= 4,
                    "position at Chebyshev " + cheb + " violates exclusion(2)/lod(4)");
        }
        // Full annulus: rings 3 and 4 = 8*3 + 8*4 positions
        assertEquals(8 * 3 + 8 * 4, queued);
    }

    @Test
    void satisfiedOuterRingMustNotConfirmPastUnsatisfiedInnerRing() {
        // genCap = genLimit * 4 = 4: ring 3 (24 not-generated positions) can only queue 4
        // per scan — unsatisfied; rings 4-5 are fully satisfied. Confirmation must hold at
        // ring 3 rather than jumping to 6 and orphaning ring 3's remaining 20 positions.
        var columns = new ColumnStateMap();
        int[] c = new int[2];
        for (int r = 3; r <= 5; r++) {
            for (int i = 0; i < 8 * r; i++) {
                SpiralScanner.ringIndexToCoord(r, i, CX, CZ, c);
                long packed = PositionUtil.packPosition(c[0], c[1]);
                if (r == 3) {
                    columns.onNotGenerated(packed);          // ts == 0 → wants generation
                } else {
                    columns.onReceived(packed, 1000L);       // satisfied
                    columns.markSent(packed);
                    columns.onUpToDate(packed);
                }
            }
        }

        var s = scanner(5, 100, 1);
        var queue = new RequestQueue();
        int queued = fireScan(s, 2, columns, queue);
        assertEquals(4, queued, "gen cap (1*4) bounds the queued generation retries");
        assertTrue(s.getConfirmedRing() <= 3,
                "confirmed ring " + s.getConfirmedRing()
                        + " jumped past unsatisfied ring 3 — permanent LOD hole");
    }

    @Test
    void fullySatisfiedDiscConfirmsToLodDistancePlusOne() {
        var columns = new ColumnStateMap();
        int[] c = new int[2];
        for (int r = 3; r <= 4; r++) {
            for (int i = 0; i < 8 * r; i++) {
                SpiralScanner.ringIndexToCoord(r, i, CX, CZ, c);
                long packed = PositionUtil.packPosition(c[0], c[1]);
                columns.onReceived(packed, 1000L);
                columns.markSent(packed);
                columns.onUpToDate(packed);
            }
        }
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(5, s.getConfirmedRing(), "fully satisfied disc confirms past lodDistance");
    }

    @Test
    void rateLimitBackoffSkipsExactlyOneScan() {
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        s.noteRateLimited();
        assertEquals(0, fireScan(s, 2, new ColumnStateMap(), queue), "backoff scan queues nothing");
        assertTrue(fireScan(s, 2, new ColumnStateMap(), queue) > 0, "next scan proceeds normally");
    }

    @Test
    void budgetBoundsQueuedPositions() {
        // budget = syncLimit * 4 = 8 with a huge annulus
        var s = scanner(16, 2, 100);
        var queue = new RequestQueue();
        assertEquals(8, fireScan(s, 2, new ColumnStateMap(), queue));
    }

    @Test
    void retryMarkInsideConfirmedDiscForcesRescanFromRingZero() {
        var columns = new ColumnStateMap();
        int[] c = new int[2];
        for (int r = 3; r <= 4; r++) {
            for (int i = 0; i < 8 * r; i++) {
                SpiralScanner.ringIndexToCoord(r, i, CX, CZ, c);
                long packed = PositionUtil.packPosition(c[0], c[1]);
                columns.onReceived(packed, 1000L);
                columns.markSent(packed);
                columns.onUpToDate(packed);
            }
        }
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        assertEquals(0, fireScan(s, 2, columns, queue));
        assertEquals(5, s.getConfirmedRing(), "precondition: disc confirmed past lodDistance");

        // A rate-limited bounce marks a ring-3 position for retry. The disc is already
        // confirmed past it, so the next scan must restart from ring 0 or the retry would
        // sit inside the skipped prefix and never be re-sent.
        SpiralScanner.ringIndexToCoord(3, 0, CX, CZ, c);
        long retried = PositionUtil.packPosition(c[0], c[1]);
        columns.markRetry(retried);

        assertEquals(1, fireScan(s, 2, columns, queue),
                "scan after a retry mark must re-walk the confirmed disc and queue the retry");
        assertEquals(List.of(retried), drain(queue));
        assertEquals(3, s.getConfirmedRing(), "confirmation holds at the unsatisfied retry ring");
    }

    @Test
    void queuePressureShrinksBudgetLinearlyWithFloorOne() {
        // base budget = 25 * 4 = 100; rings 3..16 hold 1064 candidates, far above any budget
        var s = scanner(16, 25, 100);
        var queue = new RequestQueue();
        assertEquals(75, fireScan(s, 2, 250, 1000, 0, new ColumnStateMap(), queue),
                "column queue at 25% of halt threshold scales the budget linearly to 75");

        // At the halt threshold the linear scale reaches 0 but the budget floors at 1
        s = scanner(16, 25, 100);
        queue = new RequestQueue();
        assertEquals(1, fireScan(s, 2, 1000, 1000, 0, new ColumnStateMap(), queue),
                "queue pressure floors the budget at 1, never 0");
    }

    @Test
    void missingVanillaShrinksBudgetQuadraticallyToZero() {
        // base = 100; viewDistance 2 → exclusion area 25; 15 missing → fraction 0.6,
        // quadratic scale 1 - 0.36 = 0.64 (a linear scale would give 40)
        var s = scanner(16, 25, 100);
        var queue = new RequestQueue();
        assertEquals(64, fireScan(s, 2, 0, 1000, 15, new ColumnStateMap(), queue),
                "vanilla-load scale is quadratic in the missing fraction");

        // All 25 exclusion chunks missing → scale 0 → no scan at all (no floor on this path)
        s = scanner(16, 25, 100);
        queue = new RequestQueue();
        assertEquals(0, fireScan(s, 2, 0, 1000, 25, new ColumnStateMap(), queue),
                "fully missing vanilla disc zeroes the budget");
    }

    @Test
    void backoffSurvivesMovementResetButNotDimensionChangeClear() {
        // Movement and dirty-broadcast paths call resetScanCounter() alone; it must
        // preserve a pending rate-limit backoff.
        var s = scanner(4, 100, 100);
        var queue = new RequestQueue();
        s.noteRateLimited();
        s.resetScanCounter();
        assertEquals(0, fireScan(s, 2, new ColumnStateMap(), queue),
                "backoff still pending after a movement-path reset");
        assertTrue(fireScan(s, 2, new ColumnStateMap(), queue) > 0, "next scan proceeds normally");

        // The dimension-change path additionally calls clearSkipNextScan() — the backoff
        // belonged to the old dimension's load and must be discarded.
        s = scanner(4, 100, 100);
        queue = new RequestQueue();
        s.noteRateLimited();
        s.resetScanCounter();
        s.clearSkipNextScan();
        assertTrue(fireScan(s, 2, new ColumnStateMap(), queue) > 0,
                "dimension change discards the pending backoff");
    }

    @Test
    void effectiveLodDistanceIsMinOfServerAndClientOverride() {
        int saved = LSSClientConfig.CONFIG.lodDistanceChunks;
        try {
            var s = scanner(10, 100, 100);
            LSSClientConfig.CONFIG.lodDistanceChunks = 0; // 0 = override disabled, server wins
            assertEquals(10, s.getEffectiveLodDistance());
            LSSClientConfig.CONFIG.lodDistanceChunks = 6; // client below server clamps down
            assertEquals(6, s.getEffectiveLodDistance());
            LSSClientConfig.CONFIG.lodDistanceChunks = 15; // client above server has no effect
            assertEquals(10, s.getEffectiveLodDistance());
        } finally {
            LSSClientConfig.CONFIG.lodDistanceChunks = saved;
        }
    }

    @Test
    void pruneDistanceBuffersTheEffectiveLodDistance() {
        int saved = LSSClientConfig.CONFIG.lodDistanceChunks;
        try {
            LSSClientConfig.CONFIG.lodDistanceChunks = 6;
            var s = scanner(10, 100, 100);
            // Buffer applies to the client-clamped effective distance (6), not the server's 10
            assertEquals(6 + LSSConstants.LOD_DISTANCE_BUFFER, s.getPruneDistance());
        } finally {
            LSSClientConfig.CONFIG.lodDistanceChunks = saved;
        }
    }

    @Test
    void ringIndexToCoordCoversEachRingExactlyOnce() {
        int[] c = new int[2];
        for (int r = 1; r <= 5; r++) {
            var seen = new java.util.HashSet<Long>();
            for (int i = 0; i < 8 * r; i++) {
                SpiralScanner.ringIndexToCoord(r, i, 7, -3, c);
                assertEquals(r, Math.max(Math.abs(c[0] - 7), Math.abs(c[1] + 3)),
                        "ring " + r + " index " + i + " not at Chebyshev distance r");
                assertTrue(seen.add(PositionUtil.packPosition(c[0], c[1])),
                        "duplicate position in ring " + r);
            }
            assertEquals(8 * r, seen.size());
        }
    }
}

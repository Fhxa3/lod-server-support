package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
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
        for (int i = 0; i < LSSConstants.TICKS_PER_SECOND + 1; i++) {
            int n = s.maybeScan(CX, CZ, viewDistance, 0, 1000, () -> 0,
                    columns, pos -> false, queue);
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

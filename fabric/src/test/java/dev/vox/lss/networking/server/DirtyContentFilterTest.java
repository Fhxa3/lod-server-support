package dev.vox.lss.networking.server;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hash-store behavior of {@link DirtyContentFilter}: all-air null-byte tolerance (a server-tick
 * crash found by the dimension-trip soak — the End void serializes columns to NULL section bytes)
 * and the overflow eviction that bounds the per-dimension map.
 */
class DirtyContentFilterTest {

    @Test
    void seedToleratesAllAirNullBytes() {
        var filter = new DirtyContentFilter();
        assertDoesNotThrow(() -> {
            filter.seed("minecraft:the_end", 10, 0, null);
            filter.seed("minecraft:the_end", 10, 0, null);
            filter.seed("minecraft:the_end", 10, 0, new byte[0]);
            filter.seed("minecraft:the_end", 11, 0, new byte[]{1, 2, 3});
        });
    }

    /**
     * Overflow eviction self-heals: clearing the map forgets baselines, so the next save of
     * unchanged content re-marks dirty exactly once (one spurious re-send) and then filtering
     * resumes — rather than the map leaking forever or post-clear saves staying silent.
     */
    @Test
    void overflowEvictionTreatsNextSaveAsFirstObservation() {
        var filter = new DirtyContentFilter();
        var dim = "minecraft:overworld";
        var otherDim = "minecraft:the_end";
        long pos = PositionUtil.packPosition(7, -3);
        long hash = 0x1234_5678_9ABC_DEF0L;

        assertTrue(filter.storeHash(dim, pos, hash), "first observation of a position is a change");
        assertFalse(filter.storeHash(dim, pos, hash), "identical re-save is filtered");
        assertTrue(filter.storeHash(otherDim, pos, hash), "same position in another dimension is independent");

        // Fill the dimension to one below the cap (pos already occupies one entry); z=1_000_000
        // keeps fillers distinct from pos.
        for (int i = 0; i < DirtyContentFilter.MAX_ENTRIES_PER_DIMENSION - 2; i++) {
            filter.storeHash(dim, PositionUtil.packPosition(i, 1_000_000), i + 1L);
        }
        assertFalse(filter.storeHash(dim, pos, hash), "still filtering just below the cap (no early eviction)");

        // Reach the cap; the next store clears the dimension wholesale before storing.
        filter.storeHash(dim, PositionUtil.packPosition(-1, 1_000_000), 99L);
        assertTrue(filter.storeHash(dim, pos, hash),
                "post-eviction save of identical content is treated as a first observation");
        assertFalse(filter.storeHash(dim, pos, hash), "filtering resumes after the single self-heal re-mark");

        assertFalse(filter.storeHash(otherDim, pos, hash), "eviction is per-dimension; other baselines survive");
    }
}

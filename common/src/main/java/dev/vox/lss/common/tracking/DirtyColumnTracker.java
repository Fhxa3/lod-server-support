package dev.vox.lss.common.tracking;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks chunk columns confirmed as dirty (content hash changed).
 * Platform-agnostic — uses String dimension keys, no MC types.
 * Thread-safe via synchronized blocks.
 */
public class DirtyColumnTracker {
    private final Map<String, LongOpenHashSet> dirtyColumns = new HashMap<>();
    private long totalDrained;

    public synchronized void markDirty(String dimension, int cx, int cz) {
        long packed = PositionUtil.packPosition(cx, cz);
        dirtyColumns.computeIfAbsent(dimension, k -> new LongOpenHashSet()).add(packed);
    }

    public synchronized long[] drainDirty(String dimension) {
        var set = dirtyColumns.get(dimension);
        if (set == null || set.isEmpty()) return null;
        long[] result = set.toLongArray();
        set.clear();
        totalDrained += result.length;
        return result;
    }

    /** Dirty positions accumulated and not yet drained, across all dimensions. */
    public synchronized int pendingCount() {
        int total = 0;
        for (var set : dirtyColumns.values()) total += set.size();
        return total;
    }

    /** Cumulative count of positions handed to broadcasters across the tracker's lifetime. */
    public synchronized long getTotalDrained() { return totalDrained; }
}

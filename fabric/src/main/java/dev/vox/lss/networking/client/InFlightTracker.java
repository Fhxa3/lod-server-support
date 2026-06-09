package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Tracks in-flight chunk requests by packed position (the protocol's request key):
 * position → send timestamp, plus which positions are generation requests
 * (clientTimestamp == 0) so callers don't need a parallel data structure.
 *
 * <p><b>Thread safety:</b> Not thread-safe. All methods must be called from
 * the main client thread (render/tick loop).
 */
class InFlightTracker {

    // Key: packed position, Value: send time (System.nanoTime())
    // defaultReturnValue(0L) -> 0 means "not in map"
    private final Long2LongOpenHashMap pendingRequests = new Long2LongOpenHashMap();
    {
        pendingRequests.defaultReturnValue(0L);
    }

    // Positions that are generation requests (clientTimestamp == 0)
    private final LongOpenHashSet generationPositions = new LongOpenHashSet();

    /**
     * Record that a position is now pending and whether it is a generation request.
     */
    void markPending(long position, long sendTimeNanos, boolean isGeneration) {
        this.pendingRequests.put(position, sendTimeNanos);
        if (isGeneration) {
            this.generationPositions.add(position);
        }
    }

    /** Complete an in-flight request. No-op if the position was not tracked. */
    void removeByPosition(long position) {
        this.pendingRequests.remove(position);
        this.generationPositions.remove(position);
    }

    boolean isInFlight(long position) {
        return this.pendingRequests.containsKey(position);
    }

    int size() {
        return this.pendingRequests.size();
    }

    int generationCount() {
        return this.generationPositions.size();
    }

    /**
     * Sweep all timed-out requests, removing them from tracking.
     */
    void timeoutSweep(long thresholdNanos) {
        long now = System.nanoTime();
        var iter = this.pendingRequests.long2LongEntrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            if (now - entry.getLongValue() > thresholdNanos) {
                this.generationPositions.remove(entry.getLongKey());
                iter.remove();
            }
        }
    }

    /**
     * Remove all pending requests outside the given Chebyshev distance from the player.
     * The server resolves the abandoned requests on its own; the client simply stops
     * tracking them (any late response for an untracked position is still applied).
     */
    void pruneOutOfRange(int playerCx, int playerCz, int pruneDistance) {
        var iter = this.pendingRequests.long2LongEntrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            long pos = entry.getLongKey();
            if (PositionUtil.isOutOfRange(pos, playerCx, playerCz, pruneDistance)) {
                this.generationPositions.remove(pos);
                iter.remove();
            }
        }
    }

    void clear() {
        this.pendingRequests.clear();
        this.generationPositions.clear();
    }
}

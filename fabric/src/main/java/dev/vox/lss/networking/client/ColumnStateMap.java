package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * The single owner of per-column client state: known column timestamps plus the dirty /
 * rate-limit-retry / validated-this-session marks, with derived received/empty counts.
 * {@link #classify} is the one request-need ladder consulted by both the scanner and the
 * queue drain.
 *
 * <p>Timestamp semantics: absent (-1) = never seen; 0 = server said not-generated;
 * &gt;0 = received/validated at that epoch-second.
 *
 * <p><b>Thread safety:</b> Not thread-safe. Main client thread only.
 */
class ColumnStateMap {

    /** Sentinel returned by {@link #classify} when the position needs no request. */
    static final long SATISFIED = Long.MIN_VALUE;

    private final Long2LongOpenHashMap timestamps = new Long2LongOpenHashMap();
    {
        timestamps.defaultReturnValue(-1L);
    }

    // Positions flagged by the server's dirty broadcast that need re-requesting.
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    // Positions bounced with rate-limited that need retry on a later scan.
    private final LongOpenHashSet retry = new LongOpenHashSet();
    // Positions confirmed current (data or up-to-date) in this session; cleared on
    // reconnect/dimension change so cached-but-stale positions get revalidated.
    private final LongOpenHashSet validated = new LongOpenHashSet();

    // Derived counts, maintained on every timestamp transition.
    private int receivedCount;
    private int emptyCount;

    /**
     * Decide whether a position needs a request and which clientTimestamp to send.
     * Priority: unknown &gt; generation retry &gt; dirty &gt; rate-limit retry &gt; revalidation.
     *
     * @return the timestamp to send (-1 unknown, 0 generation, &gt;0 resync),
     *         or {@link #SATISFIED} when nothing should be sent
     */
    long classify(long packed, boolean generationEnabled) {
        long stored = this.timestamps.get(packed);
        if (stored == -1L) return -1L; // Unknown — sync-on-load first; server generates only on explicit retry
        if (stored == 0L && generationEnabled) return 0L; // Not generated — generation retry
        if (this.dirty.contains(packed)) return stored; // Server-pushed dirty
        if (this.retry.contains(packed)) return stored; // Rate-limit retry
        if (stored > 0 && !this.validated.contains(packed)) return stored; // Cached but not validated this session
        return SATISFIED;
    }

    private void put(long packed, long timestamp) {
        long old = this.timestamps.put(packed, timestamp);
        if (old >= 0) {
            if (old > 0) this.receivedCount--;
            else this.emptyCount--;
        }
        if (timestamp > 0) this.receivedCount++;
        else if (timestamp == 0) this.emptyCount++;
    }

    /** Mark dirty if the column is known (&gt;0). Returns true if marked. */
    boolean markDirtyIfKnown(long packed) {
        if (this.timestamps.get(packed) > 0) {
            this.dirty.add(packed);
            return true;
        }
        return false;
    }

    void markRetry(long packed) {
        this.retry.add(packed);
    }

    /** A request for this position is going on the wire — its pending marks are consumed. */
    void markSent(long packed) {
        this.retry.remove(packed);
        this.dirty.remove(packed);
    }

    /** Column data arrived (authoritative for the position, even if no longer tracked). */
    void onReceived(long packed, long columnTimestamp) {
        this.dirty.remove(packed);
        put(packed, columnTimestamp);
        this.validated.add(packed);
    }

    /** Server confirmed the column is current. */
    void onUpToDate(long packed) {
        this.validated.add(packed);
        // Up-to-date is the server affirming this position is current, so it must satisfy
        // BOTH unsatisfied states: -1 (all-air columns never get a VoxelColumn response)
        // and 0 (a not-generated stamp whose chunk has since resolved as all-air on the
        // server). Leaving 0 in place re-classifies the position as generation-needed
        // every scan — in the End this looped ~50 req/s forever AND starved the scan
        // budget on the nearest rings so the outer disc was never requested.
        long stored = this.timestamps.get(packed);
        if (stored == -1L || stored == 0L) {
            put(packed, LSSConstants.epochSeconds());
        }
    }

    /** Server cannot serve the column (not generated / not servable). */
    void onNotGenerated(long packed) {
        put(packed, 0L);
    }

    void pruneOutOfRange(int playerCx, int playerCz, int pruneDistance) {
        var iter = this.timestamps.long2LongEntrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            if (PositionUtil.isOutOfRange(entry.getLongKey(), playerCx, playerCz, pruneDistance)) {
                long ts = entry.getLongValue();
                if (ts > 0) this.receivedCount--;
                else if (ts == 0) this.emptyCount--;
                iter.remove();
            }
        }
        pruneSet(this.dirty, playerCx, playerCz, pruneDistance);
        pruneSet(this.retry, playerCx, playerCz, pruneDistance);
        pruneSet(this.validated, playerCx, playerCz, pruneDistance);
    }

    private static void pruneSet(LongOpenHashSet set, int playerCx, int playerCz, int pruneDistance) {
        var iter = set.iterator();
        while (iter.hasNext()) {
            if (PositionUtil.isOutOfRange(iter.nextLong(), playerCx, playerCz, pruneDistance)) {
                iter.remove();
            }
        }
    }

    void clear() {
        this.timestamps.clear();
        this.dirty.clear();
        this.retry.clear();
        this.validated.clear();
        this.receivedCount = 0;
        this.emptyCount = 0;
    }

    /** Bulk-load cached timestamps (resync across sessions). */
    void loadFrom(Long2LongOpenHashMap loaded) {
        for (var entry : loaded.long2LongEntrySet()) {
            put(entry.getLongKey(), entry.getLongValue());
        }
    }

    /** The raw timestamp map, for {@link ColumnCacheStore} persistence (v3 format). */
    Long2LongOpenHashMap mapForSave() {
        return this.timestamps;
    }

    boolean isEmptyMap() { return this.timestamps.isEmpty(); }
    boolean hasRetries() { return !this.retry.isEmpty(); }
    int receivedCount() { return this.receivedCount; }
    int emptyCount() { return this.emptyCount; }
    int dirtyCount() { return this.dirty.size(); }
}

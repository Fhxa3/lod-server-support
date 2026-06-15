package dev.vox.lss.networking.client;

import java.util.Arrays;

/**
 * Single-pass buffer of chunk positions to request, written in place by the scanner and
 * consumed by {@link LodRequestManager}. A scan that accepts nothing performs no writes
 * and no commit, so an undrained remainder from the previous scan keeps draining.
 *
 * <p><b>Thread safety:</b> Not thread-safe. All methods must be called from
 * the main client thread (render/tick loop).
 */
class RequestQueue {

    private static final long[] EMPTY = new long[0];

    private long[] positions = EMPTY;
    private long[] timestamps = EMPTY;
    private int size = 0;
    private int readIndex = 0;

    /**
     * Grow the backing arrays to at least the given capacity, preserving existing
     * contents (an undrained remainder must survive a capacity increase).
     */
    void ensureCapacity(int capacity) {
        if (this.positions.length < capacity) {
            this.positions = Arrays.copyOf(this.positions, capacity);
            this.timestamps = Arrays.copyOf(this.timestamps, capacity);
        }
    }

    /** Write one entry. The write is invisible to the consumer until {@link #commit}. */
    void put(int index, long position, long timestamp) {
        this.positions[index] = position;
        this.timestamps[index] = timestamp;
    }

    /** Publish the first {@code count} slots as the new queue contents. */
    void commit(int count) {
        this.size = count;
        this.readIndex = 0;
    }

    boolean hasNext() {
        return this.readIndex < this.size;
    }

    long peekPosition() {
        return this.positions[this.readIndex];
    }

    long peekTimestamp() {
        return this.timestamps[this.readIndex];
    }

    /**
     * Advance past the current entry (skip without consuming).
     */
    void skip() {
        if (this.readIndex < this.size) this.readIndex++;
    }

    int remaining() {
        return Math.max(0, this.size - this.readIndex);
    }

    void clear() {
        this.positions = EMPTY;
        this.timestamps = EMPTY;
        this.size = 0;
        this.readIndex = 0;
    }
}

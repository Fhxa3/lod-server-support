package dev.vox.lss.common.processing;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;

import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base class for per-player request state, generic on the queued payload type.
 * Contains all shared fields and logic; platform subclasses provide the
 * {@code QueuedPayload} type and any MC-dependent behavior.
 *
 * <p>Admission is derived: a request occupies its {@link SlotType} slot exactly while its
 * {@link PendingRequest} entry exists in the pending map, so the slot counters cannot drift
 * from the map (the structural fix for the permit-leak bugs of earlier review rounds).
 *
 * @param <T> the platform payload type (Fabric: CustomPacketPayload, Paper: byte[])
 */
public abstract class AbstractPlayerRequestState<T> {

    private final UUID playerUuid;
    private volatile boolean hasHandshake = false;
    private volatile int capabilities = 0;

    // Bound on the per-player incoming queue: a flooding client must not grow it without
    // limit (heap OOM / main-thread DoS). Dropped entries are harmless — the client re-requests
    // un-acked positions on its next scan. Counts are approximate (best-effort under races).
    private static final int MAX_INCOMING_QUEUE = 16384;

    // Network handler → processing thread (thread-safe intermediaries)
    private final ConcurrentLinkedQueue<IncomingRequest> incomingRequests = new ConcurrentLinkedQueue<>();
    private final AtomicInteger incomingRequestCount = new AtomicInteger();
    // Processing thread → main thread (thread-safe output)
    private final ConcurrentLinkedQueue<QueuedPayload<T>> readyPayloads = new ConcurrentLinkedQueue<>();

    // Owned by processing thread (single-threaded access)
    private final Long2ObjectOpenHashMap<PendingRequest> pendingByPosition = new Long2ObjectOpenHashMap<>();
    // Owned by main thread (drained from readyPayloads, flushed to the wire)
    private final PriorityQueue<QueuedPayload<T>> sendQueue = new PriorityQueue<>();
    private final LongOpenHashSet diskReadDone = new LongOpenHashSet();
    private final PlayerBandwidthTracker bandwidth = new PlayerBandwidthTracker();
    private final AtomicLong totalRequestsReceived = new AtomicLong();
    // Single-writer (main thread) — volatile for cross-thread visibility to processing thread
    private volatile int sendQueueSizeSnapshot = 0;

    // Admission slots: caps are immutable; held counts are derived from the pending map
    // (single-writer: processing thread; volatile for /lsslod command reads).
    private final int syncSlotCap;
    private final int genSlotCap;
    private volatile int heldSyncSlots = 0;
    private volatile int heldGenSlots = 0;

    protected AbstractPlayerRequestState(UUID playerUuid, int syncConcurrency, int genConcurrency) {
        this.playerUuid = playerUuid;
        this.syncSlotCap = syncConcurrency;
        this.genSlotCap = genConcurrency;
    }

    // ---- Handshake / Capability ----

    public void setCapabilities(int capabilities) {
        this.capabilities = capabilities;
    }

    public int getCapabilities() {
        return this.capabilities;
    }

    public boolean supportsVoxelColumns() {
        return (this.capabilities & LSSConstants.CAPABILITY_VOXEL_COLUMNS) != 0;
    }

    public void markHandshakeComplete() {
        this.hasHandshake = true;
    }

    public boolean hasCompletedHandshake() {
        return this.hasHandshake;
    }

    // ---- Incoming request helpers (subclasses call these from addRequest) ----

    protected void enqueueIncomingRequest(IncomingRequest request) {
        if (this.incomingRequestCount.get() >= MAX_INCOMING_QUEUE) {
            return;
        }
        this.incomingRequests.add(request);
        this.incomingRequestCount.incrementAndGet();
        this.totalRequestsReceived.incrementAndGet();
    }

    // ---- Queue management ----

    /** Platform hook used by the flush error path and shared diagnostics. */
    public abstract String getPlayerName();

    /** Platform hook that puts one payload on the wire (may throw on a broken connection). */
    @FunctionalInterface
    public interface PayloadSender<T> {
        void send(T payload) throws Exception;
    }

    /**
     * Drain ready payloads from the processing thread into the send queue, then flush as
     * many as the bandwidth allocation allows through the platform sender. A send failure
     * drops the remaining queue (the client re-requests on its next scan).
     * Called by the main thread each tick.
     */
    public void flushSendQueue(long allocationBytes, SharedBandwidthLimiter globalLimiter,
                                TickDiagnostics diag, PayloadSender<T> sender) {
        QueuedPayload<T> ready;
        while ((ready = this.readyPayloads.poll()) != null) {
            this.sendQueue.add(ready);
        }
        this.sendQueueSizeSnapshot = this.sendQueue.size();

        while (!this.sendQueue.isEmpty()) {
            if (!this.bandwidth.canSend(allocationBytes)) break;

            var queued = this.sendQueue.peek();
            try {
                sender.send(queued.payload());
                this.sendQueue.poll();
                this.bandwidth.recordSend(queued.estimatedBytes());
                globalLimiter.recordSend(queued.estimatedBytes());
                diag.recordSectionSent(queued.estimatedBytes());
            } catch (Exception e) {
                LSSLogger.error("Failed to send queued payload to " + getPlayerName()
                        + ", dropping remaining queue (" + this.sendQueue.size() + " entries)", e);
                this.sendQueue.clear();
                break;
            }
        }
        this.sendQueueSizeSnapshot = this.sendQueue.size();
    }

    // ---- Processing-thread-facing per-request API ----

    public IncomingRequest pollIncomingRequest() {
        var r = this.incomingRequests.poll();
        if (r != null) this.incomingRequestCount.decrementAndGet();
        return r;
    }

    public boolean tryAdmit(PendingRequest pending) {
        int cap = pending.heldSlot() == SlotType.SYNC_ON_LOAD ? this.syncSlotCap : this.genSlotCap;
        int held = pending.heldSlot() == SlotType.SYNC_ON_LOAD ? this.heldSyncSlots : this.heldGenSlots;
        if (held >= cap) return false;
        addPendingRequest(pending);
        return true;
    }

    private void addPendingRequest(PendingRequest pending) {
        long packed = PositionUtil.packPosition(pending.cx(), pending.cz());
        var replaced = this.pendingByPosition.put(packed, pending);
        if (replaced != null) {
            adjustSlot(replaced.heldSlot(), -1);
        }
        adjustSlot(pending.heldSlot(), +1);
    }

    public PendingRequest removePendingByPosition(int cx, int cz) {
        long packed = PositionUtil.packPosition(cx, cz);
        var pending = this.pendingByPosition.remove(packed);
        if (pending != null) {
            adjustSlot(pending.heldSlot(), -1);
        }
        return pending;
    }

    public boolean hasPendingRequest(int cx, int cz) {
        return this.pendingByPosition.containsKey(PositionUtil.packPosition(cx, cz));
    }

    private void adjustSlot(SlotType slot, int delta) {
        if (slot == SlotType.SYNC_ON_LOAD) this.heldSyncSlots += delta;
        else this.heldGenSlots += delta;
    }

    public boolean hasDiskReadDone(int cx, int cz) {
        return this.diskReadDone.contains(PositionUtil.packPosition(cx, cz));
    }

    public void markDiskReadDone(int cx, int cz) {
        this.diskReadDone.add(PositionUtil.packPosition(cx, cz));
    }

    /** Clear dirty positions from diskReadDone (processing thread, from dirty-clear events). */
    public void clearDiskReadDone(long[] positions) {
        for (long pos : positions) {
            this.diskReadDone.remove(pos);
        }
    }

    // ---- Accessors for concurrent queues (used by sibling classes) ----

    public Iterable<IncomingRequest> getIncomingRequests() {
        return this.incomingRequests;
    }

    public void addReadyPayload(QueuedPayload<T> payload) {
        this.readyPayloads.add(payload);
    }

    // ---- Getters ----

    public UUID getPlayerUUID() { return this.playerUuid; }
    /** Returns a volatile snapshot of the send queue size, safe for cross-thread reads. */
    public int getSendQueueSize() { return this.sendQueueSizeSnapshot; }
    public int getHeldSyncSlots() { return this.heldSyncSlots; }
    public int getHeldGenSlots() { return this.heldGenSlots; }
    public int getSyncSlotCap() { return this.syncSlotCap; }
    public int getGenSlotCap() { return this.genSlotCap; }
    public long getTotalSectionsSent() { return this.bandwidth.getTotalSectionsSent(); }
    public long getTotalBytesSent() { return this.bandwidth.getTotalBytesSent(); }
    public long getTotalRequestsReceived() { return this.totalRequestsReceived.get(); }
}

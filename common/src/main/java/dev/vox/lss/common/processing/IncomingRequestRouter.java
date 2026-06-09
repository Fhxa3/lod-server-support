package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.voxel.ColumnTimestampCache;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;

import java.util.Map;
import java.util.UUID;

/**
 * Routes incoming chunk requests through the resolution pipeline for all players:
 * duplicate check → queue-full check → timestamp check → loaded-probe check →
 * concurrency acquire (or rate-limit bounce) → disk/generation submit.
 *
 * @param <PS> the platform-specific player state type
 */
class IncomingRequestRouter<PS extends PlayerStateAccess> {

    @FunctionalInterface
    interface DiskReadSubmitter {
        /** @return false if the read could not be submitted, so the caller can unwind. */
        boolean submit(UUID playerUuid, String dimension,
                       int cx, int cz, long submissionOrder);
    }

    @FunctionalInterface
    interface LoadedColumnSerializer<PS> {
        boolean serializeAndEnqueue(PS state, LoadedColumnData column,
                                   long columnTimestamp, long submissionOrder, String dimension);
    }

    private final ColumnTimestampCache timestampCache;
    private final DedupTracker dedupTracker;
    private final boolean diskReadingAvailable;
    private final boolean generationAvailable;
    private final ProcessingContext ctx;

    IncomingRequestRouter(ColumnTimestampCache timestampCache,
                          DedupTracker dedupTracker,
                          boolean diskReadingAvailable, boolean generationAvailable,
                          ProcessingContext ctx) {
        this.timestampCache = timestampCache;
        this.dedupTracker = dedupTracker;
        this.diskReadingAvailable = diskReadingAvailable;
        this.generationAvailable = generationAvailable;
        this.ctx = ctx;
    }

    void routeAll(TickSnapshot snapshot, Map<UUID, PS> players,
                  DiskReadSubmitter diskReadSubmitter,
                  LoadedColumnSerializer<PS> loadedSerializer,
                  long cycleNow) {
        for (var entry : snapshot.players().entrySet()) {
            if (entry.getValue().dimensionChanged()) continue;
            var state = players.get(entry.getKey());
            if (state == null) continue;
            if (!state.supportsVoxelColumns()) continue;

            processIncomingRequests(state, entry.getKey(), entry.getValue(), snapshot,
                    diskReadSubmitter, loadedSerializer, cycleNow);
        }
    }

    private void processIncomingRequests(PS state, UUID playerUuid,
                                          TickSnapshot.PlayerTickData playerData,
                                          TickSnapshot snapshot,
                                          DiskReadSubmitter diskReadSubmitter,
                                          LoadedColumnSerializer<PS> loadedSerializer,
                                          long cycleNow) {
        String dimension = playerData.dimension();
        var loadedProbes = snapshot.loadedChunkProbes().getOrDefault(playerUuid, Long2ObjectMaps.emptyMap());

        IncomingRequest req;
        while ((req = state.pollIncomingRequest()) != null) {
            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            if (resolvedAsDuplicate(state, playerUuid, req, packed)) continue;
            if (sendQueueFull(state, snapshot)) break;

            if (resolvedFromTimestamp(state, playerUuid, req, packed, dimension)) continue;

            // Check loaded probes before acquiring concurrency — in-memory hits don't need a disk/gen slot
            if (resolvedFromLoadedProbe(state, playerUuid, req, packed, loadedProbes, dimension, loadedSerializer, cycleNow)) continue;

            RequestType type = req.clientTimestamp() == 0 ? RequestType.GENERATION : RequestType.SYNC;
            var limiter = tryAcquireOrRateLimit(state, playerUuid, req, packed, type, dimension);
            if (limiter == null) continue;

            submitToDiskOrGeneration(state, playerUuid, req, packed, dimension, type, limiter, diskReadSubmitter);
        }
    }

    /** Returns true if the request is a known duplicate (already served or in-flight). */
    private boolean resolvedAsDuplicate(PS state, UUID playerUuid, IncomingRequest req, long packed) {
        if (state.hasDiskReadDone(req.cx(), req.cz())) {
            this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, packed));
            return true;
        }
        if (state.hasPendingRequest(req.cx(), req.cz())) {
            this.ctx.diagnostics().incrementSkippedDuplicate();
            return true;
        }
        return false;
    }

    /** Returns true if the send queue is full — caller should break the loop. */
    private boolean sendQueueFull(PS state, TickSnapshot snapshot) {
        if (snapshot.maxSendQueueSize() > 0
                && state.getSendQueueSize() >= snapshot.maxSendQueueSize()) {
            this.ctx.diagnostics().incrementQueueFull();
            return true;
        }
        return false;
    }

    /**
     * Try to acquire a concurrency slot. Returns the limiter on success; on a full limiter,
     * sends RateLimited (the client retries on its next scan) and returns null.
     */
    private ConcurrencyLimiter tryAcquireOrRateLimit(PS state, UUID playerUuid, IncomingRequest req,
                                                      long packed, RequestType type, String dimension) {
        var limiter = state.getRateLimiters().forRequest(type, this.diskReadingAvailable);
        if (limiter.tryAcquire()) {
            return limiter;
        }

        this.ctx.sendActions().add(new SendAction.RateLimited(playerUuid, packed));
        this.ctx.diagnostics().incrementRateLimited(type);
        if (LSSLogger.isDebugEnabled()) {
            LSSLogger.debug("Rate-limited " + playerUuid + " (" + type + "): concurrency full"
                    + " for chunk [" + req.cx() + ", " + req.cz() + "] in " + dimension);
        }
        return null;
    }

    /** Returns true if resolved from an in-memory loaded chunk probe. */
    private boolean resolvedFromLoadedProbe(PS state, UUID playerUuid, IncomingRequest req, long packed,
                                             Long2ObjectMap<LoadedColumnData> probes, String dimension,
                                             LoadedColumnSerializer<PS> loadedSerializer,
                                             long cycleNow) {
        var probe = probes.get(packed);
        if (probe == null) return false;

        boolean sent = loadedSerializer.serializeAndEnqueue(state, probe, cycleNow,
                this.ctx.sequence().next(), dimension);
        if (!sent) {
            this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, packed));
        }
        state.markDiskReadDone(req.cx(), req.cz());
        this.ctx.diagnostics().incrementInMemory();
        return true;
    }

    /**
     * Returns true if the column is up-to-date based on timestamp cache.
     * Only sends ColumnUpToDate — no data is served from this cache.
     */
    private boolean resolvedFromTimestamp(PS state, UUID playerUuid, IncomingRequest req,
                                           long packed, String dimension) {
        if (req.clientTimestamp() <= 0) return false;

        long cachedTs = this.timestampCache.get(dimension, packed);
        if (cachedTs > 0 && cachedTs <= req.clientTimestamp()) {
            state.markDiskReadDone(req.cx(), req.cz());
            this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, packed));
            this.ctx.diagnostics().incrementUpToDate();
            return true;
        }

        return false;
    }

    /** Submit to disk reader (disk-first for both SYNC and GENERATION when disk available) or generation service. */
    private void submitToDiskOrGeneration(PS state, UUID playerUuid, IncomingRequest req,
                                           long packed, String dimension, RequestType type,
                                           ConcurrencyLimiter limiter,
                                           DiskReadSubmitter diskReadSubmitter) {
        long order = this.ctx.sequence().next();

        if (type == RequestType.SYNC || this.diskReadingAvailable) {
            // Route through disk reader (with cross-player dedup)
            state.addPendingRequest(new PendingRequest(req.cx(), req.cz(), type));
            boolean attached = this.dedupTracker.tryAttachOrCreate(packed, dimension, playerUuid, order);
            if (!attached && !diskReadSubmitter.submit(playerUuid, dimension, req.cx(), req.cz(), order)) {
                // Submit was a no-op (e.g. the dimension's level isn't registered yet). Unwind the
                // pending entry, dedup group, and permit we just took so they aren't leaked, and
                // tell the client we couldn't serve this position so it re-requests later.
                this.dedupTracker.removeGroup(packed, dimension);
                state.removePendingByPosition(req.cx(), req.cz());
                limiter.release();
                this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, packed));
                return;
            }
            this.ctx.diagnostics().incrementDiskQueued();
        } else if (type == RequestType.GENERATION && this.generationAvailable) {
            // No disk reader — direct generation
            state.addPendingRequest(new PendingRequest(req.cx(), req.cz(), type));
            this.ctx.generationTicketRequests().add(
                    new OffThreadProcessor.GenerationTicketRequest(playerUuid, req.cx(), req.cz(), order));
        } else {
            // No disk reader AND no generation — can't serve
            this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, packed));
            limiter.release();
        }
    }
}

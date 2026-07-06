package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.compression.ZstdColumnCompressor;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.QueuedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper-specific off-thread processor. Produces encoded byte[] payloads
 * that will be sent via Plugin Messaging on the main thread.
 */
public class PaperOffThreadProcessor extends OffThreadProcessor<PaperPlayerRequestState> {
    private final PaperChunkDiskReader diskReader;

    // Maps a dimension id to its live ServerLevel for disk-read submission. Refreshed every
    // tick (put, not putIfAbsent): a Paper world unloaded and recreated under the same name
    // (Multiverse/arena resets) reuses the dimension id, so a stale putIfAbsent entry would
    // aim every disk read at the dead world's closed ChunkMap (mass not-found). Cleared on
    // shutdown. (Residual: a permanently-unloaded, never-recreated world's level stays until
    // shutdown — bounded, and vanilla dimensions never unload.)
    private final ConcurrentHashMap<String, ServerLevel> dimensionLevelMap = new ConcurrentHashMap<>();

    public PaperOffThreadProcessor(Map<UUID, PaperPlayerRequestState> players,
                                    PaperChunkDiskReader diskReader,
                                    boolean generationAvailable,
                                    Path dataDir, int perDimensionTimestampCacheSizeMB) {
        super(players,
                diskReader, generationAvailable, dataDir, perDimensionTimestampCacheSizeMB);
        this.diskReader = diskReader;
    }

    public void updateDimensionContext(String dimension, ServerLevel level) {
        this.dimensionLevelMap.put(dimension, level);
    }

    @Override
    protected boolean submitDiskRead(UUID playerUuid, String dimension,
                                    int cx, int cz,
                                    long submissionOrder) {
        if (this.diskReader == null) return false;
        var level = this.dimensionLevelMap.get(dimension);
        if (level == null) {
            LSSLogger.debug("No dimension context for " + dimension + ", skipping disk read for " + cx + "," + cz);
            return false;
        }
        this.diskReader.submitReadDirect(playerUuid, dimension, level,
                cx, cz, submissionOrder);
        return true;
    }

    /**
     * A queued column payload with its target channel identifier.
     * Bundles the channel and encoded data so the main-thread sender can route correctly.
     */
    record PaperQueuedPayload(Identifier channelId, byte[] data) {}

    @Override
    protected boolean buildAndEnqueueColumnPayload(PaperPlayerRequestState state, int cx, int cz,
                                                    String dimension,
                                                    long columnTimestamp, long submissionOrder,
                                                    byte[] sectionBytes, int estimatedBytes) {
        if (sectionBytes.length > LSSConstants.MAX_SEND_SECTIONS_SIZE) {
            LSSLogger.warn("Dropping oversized column [" + cx + ", " + cz + "] in " + dimension
                    + ": " + sectionBytes.length + " bytes exceeds send limit "
                    + LSSConstants.MAX_SEND_SECTIONS_SIZE + " (netty frame cap would kill the connection)");
            return false;
        }
        if (dimension.length() > LSSConstants.MAX_DIMENSION_STRING_LENGTH) {
            LSSLogger.warn("Dropping column [" + cx + ", " + cz + "] with oversized dimension id ("
                    + dimension.length() + " chars > " + LSSConstants.MAX_DIMENSION_STRING_LENGTH + ")");
            return false;
        }

        boolean zstdCapable = (state.getCapabilities() & LSSConstants.CAPABILITY_ZSTD_COMPRESSION) != 0;

        if (zstdCapable) {
            byte[] compressed = ZstdColumnCompressor.compress(sectionBytes);
            if (compressed != null) {
                byte[] encoded = PaperPayloadHandler.encodeVoxelColumnZstdPreEncoded(
                        cx, cz, dimension, columnTimestamp, sectionBytes.length, compressed);
                int compressedEstimate = encoded.length;
                var payload = new PaperQueuedPayload(PaperPayloadHandler.ID_VOXEL_COLUMN_ZSTD, encoded);
                state.addReadyPayload(new QueuedPayload<>(payload, compressedEstimate, submissionOrder,
                        PositionUtil.packPosition(cx, cz)));
                return true;
            }
            // Compression didn't help — fall through to uncompressed path
        }

        byte[] encoded = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                cx, cz, dimension, columnTimestamp, sectionBytes);
        var payload = new PaperQueuedPayload(PaperPayloadHandler.ID_VOXEL_COLUMN, encoded);
        state.addReadyPayload(new QueuedPayload<>(payload, estimatedBytes, submissionOrder,
                PositionUtil.packPosition(cx, cz)));
        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        this.dimensionLevelMap.clear();
    }
}

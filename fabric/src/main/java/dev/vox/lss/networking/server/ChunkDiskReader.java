package dev.vox.lss.networking.server;

import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * Async region file reader for Fabric. The shared base owns the executor, result queues,
 * and error triage; this class only captures the MC handles and serializes via
 * {@link NbtSectionSerializer}.
 */
public class ChunkDiskReader extends AbstractChunkDiskReader {

    public ChunkDiskReader(int threadCount) {
        super(threadCount);
    }

    public void submitReadDirect(UUID playerUuid, String dimension, ServerLevel level,
                                  int chunkX, int chunkZ, long submissionOrder) {
        var registryAccess = level.registryAccess();
        var chunkMap = ((dev.vox.lss.mixin.AccessorServerChunkCache) level.getChunkSource()).getChunkMap();
        submitRead(playerUuid, chunkX, chunkZ, dimension, submissionOrder,
                () -> NbtSectionSerializer.readAndSerializeSections(chunkMap, registryAccess, chunkX, chunkZ));
    }
}

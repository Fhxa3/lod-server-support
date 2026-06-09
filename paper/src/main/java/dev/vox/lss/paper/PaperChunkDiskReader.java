package dev.vox.lss.paper;

import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * Async region file reader for Paper. The shared base owns the executor, result queues,
 * and error triage; this class only captures the NMS handles (Paper uses Mojang mappings,
 * so no mixin accessor needed) and serializes via {@link PaperNbtSectionSerializer}.
 */
public class PaperChunkDiskReader extends AbstractChunkDiskReader {

    public PaperChunkDiskReader(int threadCount) {
        super(threadCount);
    }

    public void submitReadDirect(UUID playerUuid, String dimension, ServerLevel level,
                                  int chunkX, int chunkZ, long submissionOrder) {
        var registryAccess = level.registryAccess();
        var chunkMap = level.getChunkSource().chunkMap;
        submitRead(playerUuid, chunkX, chunkZ, dimension, submissionOrder,
                () -> PaperNbtSectionSerializer.readAndSerializeSections(chunkMap, registryAccess, chunkX, chunkZ));
    }
}

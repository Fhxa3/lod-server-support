package dev.vox.lss.paper;

import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;

/**
 * Async region file reader for Paper. The shared base owns the executor, result queues,
 * and error triage; this class only captures the NMS handles (Paper uses Mojang mappings,
 * so no mixin accessor needed) and serializes via {@link PaperNbtSectionSerializer}.
 */
public class PaperChunkDiskReader extends AbstractChunkDiskReader {

    // Test seam: when set, replaces the chunkMap.read NMS call (always null in production).
    // Volatile: set on the test thread, read on the submitting thread.
    private volatile PaperNbtSectionSerializer.ChunkNbtRead readOverride;

    public PaperChunkDiskReader(int threadCount) {
        super(threadCount);
    }

    void setReadOverride(PaperNbtSectionSerializer.ChunkNbtRead read) {
        this.readOverride = read;
    }

    public void submitReadDirect(UUID playerUuid, String dimension, ServerLevel level,
                                  int chunkX, int chunkZ, long submissionOrder) {
        var registryAccess = level.registryAccess();
        var override = this.readOverride;
        PaperNbtSectionSerializer.ChunkNbtRead read;
        if (override != null) {
            read = override;
        } else {
            var chunkMap = level.getChunkSource().chunkMap;
            read = (cx, cz) -> chunkMap.read(new ChunkPos(cx, cz));
        }
        submitRead(playerUuid, chunkX, chunkZ, dimension, submissionOrder,
                () -> PaperNbtSectionSerializer.readAndSerializeSections(read, registryAccess, chunkX, chunkZ));
    }
}

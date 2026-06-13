package dev.vox.lss.test;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.server.RequestProcessingService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FP-027: real corrupt-region-file tolerance through the live disk-read pipeline. A region
 * file with a VALID header but garbage zlib chunk data makes vanilla's
 * {@code RegionFileStorage.read} throw (the IO worker rethrows — header-level truncation
 * would instead resolve as a silent null/not-found), which the reader must triage as an
 * ERROR result delivered as not-found to the pipeline: the SYNC request resolves
 * NOT_GENERATED, the slot frees, nothing leaks, and the same reader pool then serves a
 * valid disk read — the error is contained per-read, never thread-fatal.
 *
 * <p>The corrupt chunk sits ~1900 chunks away in a region no IO worker has ever opened
 * (region file handles are cached per region; writing under an open handle would race).
 * Requests are seeded via {@code state.addRequest} — the distance guard is not under test.
 */
public class RegionFaultGameTests {

    private static final int VALID_CHUNK_OFFSET = 220;

    /** Deprecated upstream without a replacement; it is the only factory that places a real
     *  ServerPlayer (player list entry + embedded-channel connection) inside a gametest. */
    @SuppressWarnings("removal")
    private static net.minecraft.server.level.ServerPlayer placeMockServerPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void corruptRegionChunkResolvesAsContainedErrorAndReaderSurvives(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        var chunkSource = level.getChunkSource();

        // Region-aligned corrupt chunk (local index 0 in its region), far west/south of
        // anything any test or the spawn ever touches.
        int corruptCx = ((pcx >> 5) - 60) << 5;
        int corruptCz = ((pcz >> 5) + 60) << 5;
        long corruptPacked = PositionUtil.packPosition(corruptCx, corruptCz);
        try {
            var regionDir = server.getWorldPath(LevelResource.ROOT).resolve("region");
            Files.createDirectories(regionDir);
            var regionFile = regionDir.resolve(
                    "r." + (corruptCx >> 5) + "." + (corruptCz >> 5) + ".mca");
            // 8 KiB header (entry 0: offset sector 2, 1 sector; timestamps zero) + one data
            // sector: declared length 255, compression 2 (zlib), garbage bytes — the header
            // parses, the inflate throws.
            var bytes = ByteBuffer.allocate(3 * 4096);
            bytes.putInt(0, (2 << 8) | 1);
            bytes.position(2 * 4096);
            bytes.putInt(255);
            bytes.put((byte) 2);
            for (int i = 0; i < 254; i++) {
                bytes.put((byte) 0xDE);
            }
            Files.write(regionFile, bytes.array());
        } catch (Exception e) {
            helper.fail("could not stage the corrupt region file: " + e);
            return;
        }

        // Valid disk target: generated now, then unloaded + saved so its serve hits disk
        // through the same reader pool the corrupt read errors on.
        var validPos = new ChunkPos(pcx - VALID_CHUNK_OFFSET, pcz + 2);
        long validPacked = PositionUtil.packPosition(validPos.x(), validPos.z());
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, validPos, 0);
        level.getChunk(validPos.x(), validPos.z());
        helper.runAfterDelay(4, () ->
                chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, validPos, 0));

        var service = new RequestProcessingService(server);
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var step = new AtomicInteger();

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() >= 6, "waiting for the ticket release");
            if (step.get() == 0) {
                helper.assertTrue(chunkSource.getChunkNow(validPos.x(), validPos.z()) == null,
                        "waiting for the valid chunk to unload");
                level.save(null, true, false);
                state.addRequest(corruptPacked, -1L);
                state.addRequest(validPacked, -1L);
                step.set(1);
                helper.assertTrue(false, "requests queued, awaiting both disk resolutions");
            }
            service.tick();
            var diskDiag = service.getDiskReader().getDiag();
            helper.assertTrue(diskDiag.getErrorCount() == 1 && diskDiag.getSuccessfulReadCount() == 1
                            && state.getTotalSectionsSent() == 1,
                    "waiting for the corrupt read to error and the valid read to serve "
                            + "(errors=" + diskDiag.getErrorCount() + " success="
                            + diskDiag.getSuccessfulReadCount() + " sent="
                            + state.getTotalSectionsSent() + ")");
            helper.assertTrue(diskDiag.getNotFoundCount() == 0,
                    "garbage zlib data behind a valid header must triage as an ERROR, not "
                            + "silently as not-found, got not_found=" + diskDiag.getNotFoundCount());
            helper.assertTrue(diskDiag.getSubmittedCount() == 2
                            && diskDiag.getCompletedCount() == 2,
                    "both reads must complete through the same pool (submitted="
                            + diskDiag.getSubmittedCount() + " completed="
                            + diskDiag.getCompletedCount() + ")");
            helper.assertTrue(!state.hasDiskReadDone(corruptCx, corruptCz),
                    "an errored read must not mark the position done — the client's "
                            + "NOT_GENERATED retry path must stay open");
            helper.assertTrue(state.getHeldSyncSlots() == 0 && state.getHeldGenSlots() == 0,
                    "the errored read must free its slot (no pending leak)");
            helper.assertTrue(service.getDiskReader().getPendingResultCount() == 0,
                    "no undrained results may linger after both resolutions");
            service.shutdown();
            playerList.remove(mock);
        });
    }
}

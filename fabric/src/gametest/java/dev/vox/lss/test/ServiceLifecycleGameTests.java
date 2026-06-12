package dev.vox.lss.test;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.networking.server.RequestProcessingService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link RequestProcessingService} surface driven through real (mock) {@code ServerPlayer}s on a
 * dedicated server:
 *
 * <ul>
 *   <li><b>Batch-request distance guard</b> — boundary acceptance at exactly
 *       {@code lodDistanceChunks + LOD_DISTANCE_BUFFER}, rejection one chunk beyond on both axes
 *       and in the negative direction, exact coordinate/timestamp round-trip for
 *       negative-quadrant positions (a sign or off-by-one bug here makes LSS appear dead in
 *       negative-coordinate quadrants), and the unregistered-player no-op.</li>
 *   <li><b>Player removal / lifecycle</b> — {@code removePlayer} cleans every per-player
 *       structure (players map, disk-reader result queue, in-flight generation entry), the
 *       {@code computeIfAbsent} re-registration contract (same state while present, fresh state
 *       after removal, capability update in place), and the tick lifecycle's auto-remove
 *       polarity: a discarded-but-still-listed player (the death/respawn shape) must KEEP its
 *       session, only a delisted player is auto-removed.</li>
 *   <li><b>Probe serve + no-filter-seed</b> — a loaded chunk is served from memory via the probe
 *       path, and that serve must NOT seed the {@code DirtyContentFilter}: a probe can land
 *       between another player's edit and the chunk's cooldown save, and a seed would make that
 *       save hash equal — silencing the dirty broadcast every other client needs.</li>
 * </ul>
 *
 * <p>Each test constructs its OWN {@code RequestProcessingService} instead of using the live
 * singleton: tests in the same batch run concurrently, and {@code LSSGameTests} asserts the live
 * service's players map is empty and its global bandwidth total is zero — registering mock
 * players or flushing columns through the live instance would break them. An own instance also
 * makes ticking manual, so every lifecycle transition is asserted after exactly one
 * deterministic {@code tick()}. The mock players themselves are real: vanilla's
 * {@code makeMockServerPlayerInLevel} places them in the server's player list with an
 * embedded-channel connection, so {@code ServerPlayNetworking.send} genuinely delivers.
 */
public class ServiceLifecycleGameTests {

    /** Probe chunk sits in the negative quadrant relative to the mock player's spawn — far from
     *  the positive-offset chunks SerializerParityGameTests edits and from spawn-loaded chunks. */
    private static final int PROBE_CHUNK_OFFSET = 80;
    private static final int GEN_CHUNK_OFFSET = 120;

    /** Deprecated upstream without a replacement; it is the only factory that places a real
     *  ServerPlayer (player list entry + embedded-channel connection) inside a gametest. */
    @SuppressWarnings("removal")
    private static net.minecraft.server.level.ServerPlayer placeMockServerPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void batchRequestDistanceGuardBoundaryAndNegativeCoords(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var stranger = placeMockServerPlayer(helper);
        var service = new RequestProcessingService(server);
        try {
            var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            int pcx = mock.getBlockX() >> 4;
            int pcz = mock.getBlockZ() >> 4;
            int maxDist = LSSServerConfig.CONFIG.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER;
            helper.assertTrue(pcx - maxDist < 0 && pcz - maxDist < 0,
                    "premise: spawn-relative far positions must reach the negative quadrant");

            long boundary = PositionUtil.packPosition(pcx + maxDist, pcz);
            long beyondX = PositionUtil.packPosition(pcx + maxDist + 1, pcz);
            long negBoundary = PositionUtil.packPosition(pcx - maxDist, pcz - maxDist);
            long negBeyond = PositionUtil.packPosition(pcx - maxDist - 1, pcz);
            long beyondZ = PositionUtil.packPosition(pcx, pcz + maxDist + 1);

            service.handleBatchRequest(mock, new BatchChunkRequestC2SPayload(
                    new long[]{beyondX, boundary, negBeyond, negBoundary, beyondZ},
                    new long[]{11L, -1L, 11L, 12345L, 11L}, 5));

            // The service is never ticked, so the incoming queue is exactly what the guard let through.
            helper.assertTrue(state.getTotalRequestsReceived() == 2,
                    "only the two boundary positions must pass the distance guard, got "
                            + state.getTotalRequestsReceived());
            var it = state.getIncomingRequests().iterator();
            helper.assertTrue(it.hasNext(), "boundary request must be queued");
            var first = it.next();
            helper.assertTrue(first.cx() == pcx + maxDist && first.cz() == pcz,
                    "request at exactly lodDistance+buffer must be accepted, got ["
                            + first.cx() + ", " + first.cz() + "]");
            helper.assertTrue(first.clientTimestamp() == -1L,
                    "client timestamp must survive intact, got " + first.clientTimestamp());
            helper.assertTrue(it.hasNext(), "negative-quadrant boundary request must be queued");
            var second = it.next();
            helper.assertTrue(second.cx() == pcx - maxDist && second.cz() == pcz - maxDist,
                    "negative-quadrant boundary coords must round-trip exactly (sign bug in "
                            + "packing or distance), got [" + second.cx() + ", " + second.cz() + "]");
            helper.assertTrue(second.clientTimestamp() == 12345L,
                    "negative-quadrant timestamp must survive intact, got " + second.clientTimestamp());
            helper.assertTrue(!it.hasNext(), "beyond-distance requests must be dropped, not queued");

            // Unregistered player: silent no-op — no state created, nothing queued anywhere.
            service.handleBatchRequest(stranger, new BatchChunkRequestC2SPayload(
                    new long[]{boundary}, new long[]{-1L}, 1));
            helper.assertTrue(!service.getPlayers().containsKey(stranger.getUUID()),
                    "a batch request from an unregistered player must not create state");
            helper.assertTrue(state.getTotalRequestsReceived() == 2,
                    "an unregistered player's request must not leak into another player's queue");
        } finally {
            service.shutdown();
            playerList.remove(mock);
            playerList.remove(stranger);
        }
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void removePlayerCleansAllStateAndLifecycleAutoRemovesOnlyDelistedPlayers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var service = new RequestProcessingService(server);
        try {
            // Registration creates every per-player structure.
            var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            helper.assertTrue(service.getPlayers().containsKey(uuid),
                    "registered player must appear in the players map");
            helper.assertTrue(state.hasCompletedHandshake(),
                    "registerPlayer must complete the handshake");
            helper.assertTrue(state.getCapabilities() == LSSConstants.CAPABILITY_VOXEL_COLUMNS,
                    "capabilities from the handshake must be stored");
            helper.assertTrue(service.getDiskReader().getPlayerQueue(uuid) != null,
                    "registration must create the disk-reader result queue");

            // computeIfAbsent contract: re-registering an online UUID updates the existing
            // state in place (capability change on re-handshake) and never replaces it.
            var reRegistered = service.registerPlayer(mock, 0);
            helper.assertTrue(reRegistered == state,
                    "re-registering an online player must return the SAME state, not wipe it");
            helper.assertTrue(state.getCapabilities() == 0,
                    "re-registration must apply the new capabilities to the existing state");
            helper.assertTrue(state.hasCompletedHandshake(),
                    "re-registration must keep the handshake complete");

            // Seed an in-flight generation, then removePlayer must clean every structure.
            // No tick() runs between submit and remove, so the entry cannot complete first.
            var gen = service.getGenerationService();
            helper.assertTrue(gen != null,
                    "generation service expected (gametest config has enableChunkGeneration=true)");
            int pcx = mock.getBlockX() >> 4;
            int pcz = mock.getBlockZ() >> 4;
            helper.assertTrue(gen.submitGeneration(uuid, level, pcx - GEN_CHUNK_OFFSET, pcz + GEN_CHUNK_OFFSET, 1L),
                    "a fresh generation service must accept a submission");
            helper.assertTrue(gen.getActiveCount() == 1, "submission must be tracked as active");

            service.removePlayer(uuid);
            helper.assertTrue(!service.getPlayers().containsKey(uuid),
                    "removePlayer must drop the players-map entry");
            helper.assertTrue(service.getDiskReader().getPlayerQueue(uuid) == null,
                    "removePlayer must remove the disk-reader result queue");
            helper.assertTrue(gen.getActiveCount() == 0,
                    "removePlayer must release the player's in-flight generation entry");
            helper.assertTrue(gen.getTotalRemovedInFlight() == 1,
                    "the released in-flight generation must be booked as removed (or the "
                            + "submitted/completed accounting never re-balances after a kick)");

            // After removal the same UUID re-registers with a FRESH state.
            var fresh = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            helper.assertTrue(fresh != state,
                    "a removed UUID must re-register with a fresh state object");

            // Lifecycle polarity: discarded but still in the player list is the death/respawn
            // shape — the session must survive. Removing on isRemoved() alone would wipe every
            // player's LOD session on every death.
            mock.discard();
            helper.assertTrue(mock.isRemoved(), "premise: discard marks the entity removed");
            helper.assertTrue(playerList.getPlayer(uuid) != null,
                    "premise: discard must not delist the player");
            service.tick();
            service.tick();
            helper.assertTrue(service.getPlayers().containsKey(uuid),
                    "a discarded-but-listed player must keep its session (death/respawn must not "
                            + "wipe LOD state)");

            // Only a delisted player auto-removes — and without any disconnect event, since a
            // direct player-list removal never fires one.
            playerList.remove(mock);
            helper.assertTrue(playerList.getPlayer(uuid) == null, "premise: player delisted");
            service.tick();
            helper.assertTrue(!service.getPlayers().containsKey(uuid),
                    "one tick must auto-remove a delisted player (disconnect-event-less cleanup)");
            helper.assertTrue(service.getDiskReader().getPlayerQueue(uuid) == null,
                    "lifecycle auto-remove must run the same per-player cleanup as removePlayer");
        } finally {
            service.shutdown();
            if (playerList.getPlayer(uuid) != null) {
                playerList.remove(mock);
            }
        }
        helper.succeed();
    }

    /**
     * The confirmed-MAJOR no-seed rule, reproduced at its exact danger window: baseline save →
     * another player edits → probe serves the loaded chunk from memory → the chunk's save runs.
     * The save's content check must still see the edit; if the probe serve had seeded the filter
     * with the post-edit bytes, the save would hash equal and the dirty broadcast every other
     * client needs would be swallowed. (Generation serves DO seed — freshly generated content
     * cannot be stale-held by anyone; probe serves must not.)
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
    public void probeServesLoadedChunkFromMemoryWithoutSeedingDirtyFilter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var liveService = LSSServerNetworking.getRequestService();
        helper.assertTrue(liveService != null,
                "live RequestProcessingService must be active (save-hook leg depends on it)");

        var mock = placeMockServerPlayer(helper);
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        int cx = pcx - PROBE_CHUNK_OFFSET;
        int cz = pcz - PROBE_CHUNK_OFFSET;
        int maxDist = LSSServerConfig.CONFIG.lodDistanceChunks + LSSConstants.LOD_DISTANCE_BUFFER;
        helper.assertTrue(PositionUtil.chebyshevDistance(cx, cz, pcx, pcz) <= maxDist,
                "premise: the probe chunk must be inside the request distance guard");
        var chunkPos = new ChunkPos(cx, cz);
        var chunkSource = level.getChunkSource();
        var dim = LSSConstants.DIM_STR_OVERWORLD;
        long packed = PositionUtil.packPosition(cx, cz);
        // Grass surface block of the default superflat preset.
        var editPos = new BlockPos(cx * 16 + 4, -61, cz * 16 + 4);

        // Keep the chunk loaded for the whole test so the serve must come from the probe path.
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(cx, cz);

        var service = new RequestProcessingService(server);
        var filter = service.getDirtyContentFilter();
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);

        // Tick 2 (generation light settled): baseline the filter like an earlier save would,
        // then edit, then request — the probe will serve the post-edit bytes.
        helper.runAfterDelay(2, () -> {
            var chunk = level.getChunk(cx, cz);
            helper.assertTrue(filter.contentChanged(level, chunk, dim),
                    "first observation must baseline the virgin filter");
            helper.assertTrue(!filter.contentChanged(level, chunk, dim),
                    "identical content must stay quiet once baselined");
            // Toggle so the edit is a real content change even if a previous run (the gametest
            // world persists) already left stone at this position.
            var edit = level.getBlockState(editPos).is(Blocks.STONE) ? Blocks.COBBLESTONE : Blocks.STONE;
            level.setBlock(editPos, edit.defaultBlockState(), 3);
            service.handleBatchRequest(mock, new BatchChunkRequestC2SPayload(
                    new long[]{packed}, new long[]{-1L}, 1));
            helper.assertTrue(state.getTotalRequestsReceived() == 1,
                    "the in-range request must be accepted");
        });

        var step = new AtomicInteger();
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() >= 4, "waiting for the baseline+edit setup");
            switch (step.get()) {
                case 0 -> {
                    // Manual tick: main thread probes the loaded chunk, processing thread
                    // serves it, the next manual tick's flush sends it to the mock player.
                    service.tick();
                    helper.assertTrue(state.getTotalSectionsSent() >= 1,
                            "waiting for the probe serve to flush");
                    helper.assertTrue(
                            service.getOffThreadProcessor().getDiagnostics().getTotalInMemory() == 1,
                            "the serve must come from the in-memory probe, not disk");
                    var chunk = level.getChunk(cx, cz);
                    helper.assertTrue(filter.contentChanged(level, chunk, dim),
                            "a probe serve must NOT seed the dirty filter: the save after the "
                                    + "edit no longer sees a change, swallowing the dirty "
                                    + "broadcast other clients need");
                    helper.assertTrue(!filter.contentChanged(level, chunk, dim),
                            "the check above must have stored the new hash (filter is live)");
                    step.set(1);
                    helper.assertTrue(false, "no-seed verified, running the live save-hook leg");
                }
                case 1 -> {
                    // Live end-to-end: edit → real save → the position must surface in the live
                    // dirty tracker (ChunkMapSaveHook → DirtyContentFilter → DirtyColumnTracker).
                    // Drain, save, and re-drain in one callback: saves and the broadcaster only
                    // run on the main thread, so nothing can interleave and steal the mark.
                    var edit = level.getBlockState(editPos).is(Blocks.STONE)
                            ? Blocks.COBBLESTONE : Blocks.STONE;
                    level.setBlock(editPos, edit.defaultBlockState(), 3);
                    liveService.getDirtyTracker().drainDirty(dim);
                    level.save(null, true, false);
                    long[] dirty = liveService.getDirtyTracker().drainDirty(dim);
                    helper.assertTrue(containsPosition(dirty, packed),
                            "a save after a real edit must mark the column dirty end-to-end "
                                    + "(save hook -> content filter -> dirty tracker)");
                    chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
                    service.shutdown();
                    server.getPlayerList().remove(mock);
                }
                default -> helper.fail("unexpected probe test step " + step.get());
            }
        });
    }

    private static boolean containsPosition(long[] positions, long packed) {
        if (positions == null) return false;
        for (long p : positions) {
            if (p == packed) return true;
        }
        return false;
    }
}

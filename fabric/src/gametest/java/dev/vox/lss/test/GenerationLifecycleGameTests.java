package dev.vox.lss.test;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.TickSnapshot;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.server.ChunkGenerationService;
import dev.vox.lss.networking.server.RequestProcessingService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.TicketStorage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ChunkGenerationService} books and the dimension-change re-registration protocol on a
 * real dedicated server:
 *
 * <ul>
 *   <li><b>Piggyback single-ticket lifecycle</b> — a second request for an in-flight chunk must
 *       attach to the existing entry (one {@code LSS_GEN_TICKET} in the level's real
 *       {@link TicketStorage}, {@code totalSubmitted} booked once) and completion must emit one
 *       outcome per callback, release the ticket (the force-load leak), and free the per-player
 *       concurrency counts.</li>
 *   <li><b>Cap boundaries</b> — exact per-player and global rejection boundaries, rejected
 *       submissions leaking neither books nor tickets, and piggybacks bypassing both caps
 *       (they consume no new entry).</li>
 *   <li><b>removePlayer determinism</b> — removing one piggybacked player must keep the shared
 *       entry and its ticket alive for the remaining player; only orphaned entries release their
 *       ticket and book {@code totalRemovedInFlight} (the term that re-balances soak law A4
 *       after a kick or dimension change).</li>
 *   <li><b>Timeout boundary</b> — an unloadable chunk fails every piggybacked callback exactly
 *       on the first tick past {@code generationTimeoutSeconds} and releases the ticket.</li>
 *   <li><b>Dimension change</b> — the lifecycle pass must replace the player's state (fresh
 *       object, capabilities preserved, handshake complete), drop stale work (queued requests,
 *       in-flight generation entry + ticket, queued generation ticket requests via the drain's
 *       dimension guard), and do all of it exactly once.</li>
 * </ul>
 *
 * <p>Dimension changes are triggered with {@code ServerPlayer.setServerLevel} instead of the full
 * {@code teleportTo} machinery: gametest mock players have no client to answer the position
 * confirmation a real cross-dimension teleport awaits, and the unit under test
 * ({@code PlayerRequestState.checkDimensionChange}) reads only {@code player.level()} — the
 * end-to-end teleport path is exercised by the dimension-trip soak scenario. The player's level
 * is restored before the mock leaves the player list, so the half-state never outlives a test.
 *
 * <p>Like {@code ServiceLifecycleGameTests}, service-level tests construct their OWN
 * {@code RequestProcessingService} (the live singleton must stay player-free for
 * {@code LSSGameTests}); generation-book tests construct a {@code ChunkGenerationService}
 * directly from a hand-built config so cap and timeout boundaries are exact regardless of the
 * run-dir config. Chunk offsets are unique per test (160..240 band, disjoint from the 64..120
 * band other gametest classes use) because tests in a batch run concurrently and the gametest
 * world persists across runs.
 */
public class GenerationLifecycleGameTests {

    private static final int PIGGYBACK_CHUNK_OFFSET = 160;
    private static final int CAP_CHUNK_OFFSET = 176;
    private static final int TIMEOUT_CHUNK_OFFSET = 192;
    private static final int DIMENSION_CHUNK_OFFSET = 208;
    private static final int STALE_TICKET_CHUNK_OFFSET = 224;
    private static final int REMOVAL_CHUNK_OFFSET = 240;

    /** Deprecated upstream without a replacement; it is the only factory that places a real
     *  ServerPlayer (player list entry + embedded-channel connection) inside a gametest. */
    @SuppressWarnings("removal")
    private static net.minecraft.server.level.ServerPlayer placeMockServerPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    private static ChunkGenerationService newGenService(int globalCap, int perPlayerCap, int timeoutSeconds) {
        var config = new LSSServerConfig();
        config.generationConcurrencyLimitGlobal = globalCap;
        config.generationConcurrencyLimitPerPlayer = perPlayerCap;
        config.generationTimeoutSeconds = timeoutSeconds;
        return new ChunkGenerationService(config);
    }

    private static TicketStorage ticketStorage(ServerLevel level) {
        // Same instance the chunk source uses: SavedDataStorage caches per SavedDataType.
        return level.getDataStorage().computeIfAbsent(TicketStorage.TYPE);
    }

    /**
     * Count LSS-shaped tickets (load-capable, no timeout) at a chunk position. The timeout
     * filter excludes transient vanilla chunk-system tickets (e.g. "unknown", timeout 1) that
     * can appear while a chunk loads; at the far-away chunks these tests use, no other
     * no-timeout load ticket source exists (player_loading needs a nearby player, forced needs
     * /forceload), so this count is exactly the LSS generation ticket count.
     */
    private static int lssTicketCount(TicketStorage tickets, int cx, int cz) {
        int count = 0;
        for (var ticket : tickets.getTickets(ChunkPos.pack(cx, cz))) {
            if (ticket.getType().doesLoad() && !ticket.getType().hasTimeout()) count++;
        }
        return count;
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 600)
    public void piggybackedGenerationSharesOneTicketAndCompletesEveryCallback(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x() + PIGGYBACK_CHUNK_OFFSET;
        int cz = origin.z() + 3;
        var tickets = ticketStorage(level);
        var gen = newGenService(3, 2, 60);
        var playerA = UUID.randomUUID();
        var playerB = UUID.randomUUID();

        helper.assertTrue(lssTicketCount(tickets, cx, cz) == 0,
                "premise: no leftover generation ticket at the test chunk");

        helper.assertTrue(gen.submitGeneration(playerA, level, cx, cz, 11L),
                "a fresh generation service must accept the first submission");
        helper.assertTrue(gen.getTotalSubmitted() == 1, "first submission must book submitted=1");
        helper.assertTrue(gen.getActiveCount() == 1, "first submission must create one active entry");
        helper.assertTrue(lssTicketCount(tickets, cx, cz) == 1,
                "first submission must add exactly one load ticket");

        helper.assertTrue(gen.submitGeneration(playerB, level, cx, cz, 22L),
                "a second submission for the same in-flight chunk must piggyback (accepted)");
        helper.assertTrue(gen.getTotalSubmitted() == 1,
                "piggyback must not double-book submitted, got " + gen.getTotalSubmitted());
        helper.assertTrue(gen.getActiveCount() == 1, "piggyback must reuse the single active entry");
        helper.assertTrue(lssTicketCount(tickets, cx, cz) == 1,
                "piggyback must NOT add a second ticket for the same chunk");

        var outcomes = new AtomicReference<List<TickSnapshot.GenerationReadyData>>();
        var slotReuse = new AtomicReference<boolean[]>();
        helper.succeedWhen(() -> {
            if (outcomes.get() == null) {
                var ready = gen.tick();
                helper.assertTrue(!ready.isEmpty(), "waiting for the ticketed chunk to load/generate");
                outcomes.set(List.copyOf(ready));
            }
            var ready = outcomes.get();
            helper.assertTrue(ready.size() == 2,
                    "completion must emit one outcome per piggybacked callback, got " + ready.size());
            var byPlayer = new HashMap<UUID, TickSnapshot.GenerationReadyData>();
            for (var outcome : ready) byPlayer.put(outcome.playerUuid(), outcome);
            var forA = byPlayer.get(playerA);
            var forB = byPlayer.get(playerB);
            helper.assertTrue(forA != null && forB != null,
                    "both piggybacked players must receive an outcome (a lost callback strands "
                            + "that client's pending slot forever)");
            helper.assertTrue(forA.submissionOrder() == 11L && forB.submissionOrder() == 22L,
                    "each outcome must carry its own callback's submissionOrder, got A="
                            + forA.submissionOrder() + " B=" + forB.submissionOrder());
            for (var outcome : ready) {
                helper.assertTrue(outcome.cx() == cx && outcome.cz() == cz,
                        "outcome coords must match the request, got [" + outcome.cx() + ", " + outcome.cz() + "]");
                helper.assertTrue(LSSConstants.DIM_STR_OVERWORLD.equals(outcome.dimension()),
                        "outcome dimension must be the submitting level's, got " + outcome.dimension());
                helper.assertTrue(outcome.columnData() != null,
                        "a completion outcome must carry column data (null means failure)");
                helper.assertTrue(outcome.columnData().serializedSections() != null,
                        "a generated superflat column must serialize non-air sections");
                helper.assertTrue(outcome.columnTimestamp() > 0,
                        "completion must carry a real timestamp for the up-to-date economy");
            }
            helper.assertTrue(gen.getTotalCompleted() == 1,
                    "the shared entry completes ONCE, not once per callback, got " + gen.getTotalCompleted());
            helper.assertTrue(gen.getActiveCount() == 0, "the completed entry must leave the active set");
            helper.assertTrue(gen.getTotalTimeouts() == 0 && gen.getTotalRemovedInFlight() == 0,
                    "completion must not book a timeout or an in-flight removal");
            helper.assertTrue(lssTicketCount(tickets, cx, cz) == 0,
                    "completion must release the generation ticket (or the chunk stays "
                            + "force-loaded forever)");

            // Per-player cap is 2; two fresh submissions only fit if completion decremented
            // the per-player count held by the completed entry.
            if (slotReuse.get() == null) {
                slotReuse.set(new boolean[]{
                        gen.submitGeneration(playerA, level, cx + 1, cz, 33L),
                        gen.submitGeneration(playerA, level, cx + 2, cz, 44L)});
                gen.shutdown();
            }
            helper.assertTrue(slotReuse.get()[0] && slotReuse.get()[1],
                    "completion must free the per-player concurrency count (leaked count would "
                            + "reject the second post-completion submission)");
            helper.assertTrue(lssTicketCount(tickets, cx + 1, cz) == 0
                            && lssTicketCount(tickets, cx + 2, cz) == 0,
                    "shutdown must release every remaining generation ticket");
            helper.assertTrue(gen.getActiveCount() == 0, "shutdown must clear the active set");
        });
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void generationCapBoundariesRejectExactlyAtCapWithoutLeakingTickets(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        int bx = origin.x() + CAP_CHUNK_OFFSET;
        int z = origin.z() + 5;
        var tickets = ticketStorage(level);
        var gen = newGenService(4, 2, 60);
        var playerA = UUID.randomUUID();
        var playerB = UUID.randomUUID();
        var playerC = UUID.randomUUID();
        var playerD = UUID.randomUUID();
        var playerE = UUID.randomUUID();
        try {
            // Per-player boundary: cap 2, global has room (2 < 4) — rejection is per-player.
            helper.assertTrue(gen.submitGeneration(playerA, level, bx, z, 1L),
                    "submission 1 of 2 under the per-player cap must be accepted");
            helper.assertTrue(gen.submitGeneration(playerA, level, bx + 1, z, 2L),
                    "submission 2 of 2 at the per-player cap boundary must be accepted");
            helper.assertTrue(!gen.submitGeneration(playerA, level, bx + 2, z, 3L),
                    "a third distinct-chunk submission must be rejected at per-player cap 2");
            helper.assertTrue(gen.getTotalSubmitted() == 2,
                    "a rejected submission must not book submitted, got " + gen.getTotalSubmitted());
            helper.assertTrue(gen.getActiveCount() == 2,
                    "a rejected submission must not create an entry");
            helper.assertTrue(lssTicketCount(tickets, bx + 2, z) == 0,
                    "a rejected submission must NOT leak a load ticket");

            // Piggyback bypasses the per-player cap: it consumes no new entry or ticket.
            helper.assertTrue(gen.submitGeneration(playerB, level, bx, z, 4L),
                    "piggyback under cap must be accepted");
            helper.assertTrue(gen.submitGeneration(playerB, level, bx + 3, z, 5L),
                    "playerB's second submission must be accepted (global 3 of 4)");
            helper.assertTrue(gen.submitGeneration(playerB, level, bx + 1, z, 6L),
                    "a piggyback AT the per-player cap must be accepted — it consumes no new slot");
            helper.assertTrue(gen.getActiveCount() == 3 && gen.getTotalSubmitted() == 3,
                    "piggybacks must not create entries or book submissions, active="
                            + gen.getActiveCount() + " submitted=" + gen.getTotalSubmitted());

            // Global boundary: 4th entry fills the global cap; a FRESH player (count 0) is
            // rejected — unambiguously the global cap.
            helper.assertTrue(gen.submitGeneration(playerC, level, bx + 4, z, 7L),
                    "the 4th entry at the global cap boundary must be accepted");
            helper.assertTrue(!gen.submitGeneration(playerD, level, bx + 5, z, 8L),
                    "a fresh player's submission must be rejected once the global cap is full");
            helper.assertTrue(lssTicketCount(tickets, bx + 5, z) == 0,
                    "a globally rejected submission must not leak a ticket");
            helper.assertTrue(gen.submitGeneration(playerE, level, bx, z, 9L),
                    "a piggyback while global-full must be accepted — it consumes no new entry");
            helper.assertTrue(gen.getTotalSubmitted() == 4 && gen.getActiveCount() == 4,
                    "books after the boundary dance: submitted=" + gen.getTotalSubmitted()
                            + " active=" + gen.getActiveCount() + " (both must be 4)");
            helper.assertTrue(lssTicketCount(tickets, bx, z) == 1,
                    "three piggybacked callbacks must still share exactly ONE ticket");
        } finally {
            gen.shutdown();
        }
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void removePlayerKeepsSharedEntriesAliveReleasesOrphanedTicketsAndBalancesBooks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        int bx = origin.x() + REMOVAL_CHUNK_OFFSET;
        int z = origin.z() + 9;
        var tickets = ticketStorage(level);
        var gen = newGenService(4, 2, 60);
        var playerA = UUID.randomUUID();
        var playerB = UUID.randomUUID();
        var playerF = UUID.randomUUID();
        try {
            // A holds R0 and R1; B piggybacks R0 and holds R2.
            helper.assertTrue(gen.submitGeneration(playerA, level, bx, z, 1L), "seed R0");
            helper.assertTrue(gen.submitGeneration(playerA, level, bx + 1, z, 2L), "seed R1");
            helper.assertTrue(gen.submitGeneration(playerB, level, bx, z, 3L), "piggyback R0");
            helper.assertTrue(gen.submitGeneration(playerB, level, bx + 2, z, 4L), "seed R2");
            helper.assertTrue(gen.getActiveCount() == 3 && gen.getTotalSubmitted() == 3,
                    "premise: three active entries, three booked submissions");

            // Removing B must keep the shared entry R0 (A still waits on it) and its ticket;
            // releasing the ticket here would silently strand A's generation forever.
            gen.removePlayer(playerB);
            helper.assertTrue(gen.getActiveCount() == 2,
                    "removing B must drop only B's orphaned entry (R2), active=" + gen.getActiveCount());
            helper.assertTrue(lssTicketCount(tickets, bx, z) == 1,
                    "the shared entry's ticket must survive while another player still waits on it");
            helper.assertTrue(lssTicketCount(tickets, bx + 2, z) == 0,
                    "the orphaned entry's ticket must be released");
            helper.assertTrue(gen.getTotalRemovedInFlight() == 1,
                    "exactly the orphaned entry must book removedInFlight, got "
                            + gen.getTotalRemovedInFlight());
            helper.assertTrue(gen.getTotalSubmitted() == 3,
                    "removal must never un-book submissions");

            // Removing A orphans R0 and R1 — both release and book.
            gen.removePlayer(playerA);
            helper.assertTrue(gen.getActiveCount() == 0, "removing the last waiter must clear the active set");
            helper.assertTrue(lssTicketCount(tickets, bx, z) == 0 && lssTicketCount(tickets, bx + 1, z) == 0,
                    "every orphaned ticket must be released after the last waiter leaves");
            helper.assertTrue(gen.getTotalRemovedInFlight() == 3,
                    "each orphan-removed ENTRY books removedInFlight once, got "
                            + gen.getTotalRemovedInFlight());

            // Removal must free capacity: a fresh player fits again.
            helper.assertTrue(gen.submitGeneration(playerF, level, bx + 3, z, 5L),
                    "capacity freed by removePlayer must be reusable");
            helper.assertTrue(gen.getTotalSubmitted() == gen.getTotalCompleted()
                            + gen.getTotalTimeouts() + gen.getTotalRemovedInFlight() + gen.getActiveCount(),
                    "soak law A4 identity must hold locally: submitted(" + gen.getTotalSubmitted()
                            + ") == completed(" + gen.getTotalCompleted() + ") + timeouts("
                            + gen.getTotalTimeouts() + ") + removedInFlight(" + gen.getTotalRemovedInFlight()
                            + ") + active(" + gen.getActiveCount() + ")");
        } finally {
            gen.shutdown();
        }
        helper.assertTrue(lssTicketCount(tickets, bx + 3, z) == 0,
                "shutdown must release the remaining entry's ticket");
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void generationTimeoutFailsEveryCallbackAtExactBoundaryAndReleasesTicket(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x() + TIMEOUT_CHUNK_OFFSET;
        int cz = origin.z() + 7;
        var tickets = ticketStorage(level);
        // timeout 1s = 20 ticks; chunk promotion needs main-thread task pumping, which cannot
        // happen while this callback spins tick() — the chunk deterministically never loads.
        var gen = newGenService(3, 2, 1);
        var playerA = UUID.randomUUID();
        var playerB = UUID.randomUUID();
        try {
            helper.assertTrue(level.getChunkSource().getChunkNow(cx, cz) == null,
                    "premise: the timeout chunk must not already be loaded");
            helper.assertTrue(gen.submitGeneration(playerA, level, cx, cz, 7L), "seed the entry");
            helper.assertTrue(gen.submitGeneration(playerB, level, cx, cz, 8L), "piggyback the entry");
            helper.assertTrue(lssTicketCount(tickets, cx, cz) == 1, "premise: ticket held");

            int timeoutTicks = LSSConstants.TICKS_PER_SECOND; // generationTimeoutSeconds = 1
            for (int i = 1; i <= timeoutTicks; i++) {
                helper.assertTrue(gen.tick().isEmpty(),
                        "must not time out before the boundary (ticksWaiting > timeout): premature "
                                + "outcome on tick " + i + " of " + timeoutTicks);
            }
            var failures = gen.tick();
            helper.assertTrue(failures.size() == 2,
                    "the first tick past the boundary must fail every piggybacked callback, got "
                            + failures.size() + " outcomes");
            var byPlayer = new HashMap<UUID, TickSnapshot.GenerationReadyData>();
            for (var outcome : failures) byPlayer.put(outcome.playerUuid(), outcome);
            var forA = byPlayer.get(playerA);
            var forB = byPlayer.get(playerB);
            helper.assertTrue(forA != null && forB != null,
                    "both piggybacked players must get a failure outcome");
            helper.assertTrue(forA.submissionOrder() == 7L && forB.submissionOrder() == 8L,
                    "each failure must carry its own callback's submissionOrder");
            for (var outcome : failures) {
                helper.assertTrue(outcome.columnData() == null,
                        "timeout outcomes must carry null column data (the failure marker the "
                                + "processing thread routes to ColumnNotGenerated)");
                helper.assertTrue(outcome.cx() == cx && outcome.cz() == cz,
                        "failure coords must match the request");
                helper.assertTrue(LSSConstants.DIM_STR_OVERWORLD.equals(outcome.dimension()),
                        "failure outcomes must carry the dimension, got " + outcome.dimension());
            }
            helper.assertTrue(gen.getTotalTimeouts() == 1,
                    "the shared entry books ONE timeout, not one per callback, got "
                            + gen.getTotalTimeouts());
            helper.assertTrue(gen.getTotalCompleted() == 0 && gen.getTotalRemovedInFlight() == 0,
                    "a timeout must book neither a completion nor an in-flight removal");
            helper.assertTrue(gen.getActiveCount() == 0, "the timed-out entry must leave the active set");
            helper.assertTrue(lssTicketCount(tickets, cx, cz) == 0,
                    "timeout must release the generation ticket (or the never-loading chunk's "
                            + "ticket leaks forever)");
        } finally {
            gen.shutdown();
        }
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void dimensionChangeReplacesStatePreservesCapabilitiesAndDropsStaleWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        ServerLevel endLevel = server.getLevel(Level.END);
        helper.assertTrue(endLevel != null, "the End dimension must exist on the gametest server");
        var origin = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        int gx = origin.x() + DIMENSION_CHUNK_OFFSET;
        int gz = origin.z() + 11;
        var overworldTickets = ticketStorage(level);
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var service = new RequestProcessingService(server);
        try {
            var oldState = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            helper.assertTrue(oldState.getLastDimension().equals(Level.OVERWORLD),
                    "premise: the mock player starts in the overworld");

            // Stale work of all three kinds: queued incoming requests, an in-flight generation
            // entry (with its MC ticket), and the per-player disk-reader queue.
            oldState.addRequest(PositionUtil.packPosition(gx, gz + 1), -1L);
            oldState.addRequest(PositionUtil.packPosition(gx, gz + 2), 12345L);
            helper.assertTrue(oldState.getTotalRequestsReceived() == 2,
                    "premise: stale requests queued on the old state");

            var gen = service.getGenerationService();
            helper.assertTrue(gen != null,
                    "generation service expected (gametest config has enableChunkGeneration=true)");
            helper.assertTrue(level.getChunkSource().getChunkNow(gx, gz) == null,
                    "premise: the in-flight generation chunk must not be loaded (it must survive "
                            + "the tick's generation pass un-completed)");
            helper.assertTrue(gen.submitGeneration(uuid, level, gx, gz, 1L),
                    "premise: in-flight generation seeded");
            helper.assertTrue(lssTicketCount(overworldTickets, gx, gz) == 1,
                    "premise: generation ticket held in the old dimension");

            var diskQueueBefore = service.getDiskReader().getPlayerQueue(uuid);
            helper.assertTrue(diskQueueBefore != null,
                    "premise: registration created the disk-reader result queue");

            mock.setServerLevel(endLevel);
            helper.assertTrue(mock.level().dimension().equals(Level.END),
                    "premise: the player's level switched to the End");

            service.tick();

            var newState = service.getPlayers().get(uuid);
            helper.assertTrue(newState != null, "a dimension change must keep the player registered");
            helper.assertTrue(newState != oldState,
                    "a dimension change must REPLACE the state object (disconnect teardown + "
                            + "fresh registration), not mutate the old one");
            helper.assertTrue(newState.getCapabilities() == LSSConstants.CAPABILITY_VOXEL_COLUMNS,
                    "the fresh state must inherit the session's capabilities — losing them makes "
                            + "the router skip every request for the rest of the session");
            helper.assertTrue(newState.hasCompletedHandshake(),
                    "the fresh state must be handshake-complete (no client re-handshake happens "
                            + "on a dimension change)");
            helper.assertTrue(newState.getLastDimension().equals(Level.END),
                    "the fresh state must adopt the new dimension as its baseline");
            helper.assertTrue(newState.getTotalRequestsReceived() == 0
                            && !newState.getIncomingRequests().iterator().hasNext(),
                    "requests queued before the dimension change must die with the old state");
            helper.assertTrue(gen.getActiveCount() == 0,
                    "the in-flight generation entry must be dropped on dimension change");
            helper.assertTrue(gen.getTotalRemovedInFlight() == 1,
                    "the dropped in-flight generation must be booked as removed (the A4 "
                            + "re-balancing term), got " + gen.getTotalRemovedInFlight());
            helper.assertTrue(lssTicketCount(overworldTickets, gx, gz) == 0,
                    "the old dimension's generation ticket must be released, not leaked");
            var diskQueueAfter = service.getDiskReader().getPlayerQueue(uuid);
            helper.assertTrue(diskQueueAfter != null,
                    "re-registration must recreate the disk-reader result queue");
            helper.assertTrue(diskQueueAfter != diskQueueBefore,
                    "the disk-reader queue must be torn down and recreated (a carried-over queue "
                            + "would deliver old-dimension results to the new session)");

            service.tick();
            helper.assertTrue(service.getPlayers().get(uuid) == newState,
                    "the replacement must happen exactly once — the next tick must keep the "
                            + "fresh state instead of re-resetting every tick");
            helper.assertTrue(gen.getTotalRemovedInFlight() == 1,
                    "no repeated removal booking on subsequent ticks");
        } finally {
            mock.setServerLevel(level);
            service.shutdown();
            playerList.remove(mock);
        }
        helper.succeed();
    }

    /**
     * The drain-side dimension guard: a generation ticket request admitted by the processing
     * thread BEFORE a dimension change targets the old dimension's coordinates and must be
     * dropped by {@code drainGenerationTicketRequests}, never submitted into the new dimension.
     * The wait observes {@code heldGenSlots == 1} on the old state — the disk-notfound →
     * generation re-admission that immediately precedes the ticket-request enqueue — and only
     * manual snapshots (no {@code service.tick()}) drive the processing thread until then, so no
     * drain can run between the conversion and the dimension flip.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
    public void dimensionChangeDropsStaleGenerationTicketRequestsViaDrainGuard(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        ServerLevel endLevel = server.getLevel(Level.END);
        helper.assertTrue(endLevel != null, "the End dimension must exist on the gametest server");
        var origin = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x() + STALE_TICKET_CHUNK_OFFSET;
        // This chunk must not exist on disk (a found disk read never converts to generation).
        // No run ever generates it — the guard drops it pre-generation — and the per-run salt
        // keeps even pathological cross-run coordinate collisions away.
        int cz = origin.z() + (int) Math.floorMod(System.nanoTime(), 64L);
        var mock = placeMockServerPlayer(helper);
        var uuid = mock.getUUID();
        var service = new RequestProcessingService(server);
        var gen = service.getGenerationService();
        helper.assertTrue(gen != null, "generation service expected");
        var oldState = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        helper.assertTrue(level.getChunkSource().getChunkNow(cx, cz) == null,
                "premise: the requested chunk must not be loaded (a probe serve would bypass "
                        + "the disk-notfound path)");
        oldState.addRequest(PositionUtil.packPosition(cx, cz), 0L); // clientTimestamp 0 = generation

        // Tick 1 registers the dimension context and posts the routing snapshot. Its drain
        // cannot submit anything: the disk-notfound -> generation conversion needs a second
        // processing cycle, and no second snapshot exists within this tick.
        service.tick();

        var phase = new AtomicInteger();
        helper.succeedWhen(() -> {
            if (phase.get() == 0) {
                if (oldState.getHeldGenSlots() != 1) {
                    // Drive the processing thread without running the main-thread drain.
                    service.getOffThreadProcessor().postSnapshot(new TickSnapshot(
                            Map.of(uuid, LSSConstants.DIM_STR_OVERWORLD), Map.of(),
                            LSSServerConfig.CONFIG.sendQueueLimitPerPlayer, false), List.of());
                    helper.assertTrue(false,
                            "waiting for the disk-notfound -> generation conversion (heldGenSlots=1)");
                }
                // Conversion confirmed: the stale ticket request (old dimension) is enqueued.
                // Flip the dimension before any drain can submit it.
                mock.setServerLevel(endLevel);
                phase.set(1);
            }
            service.tick();
            helper.assertTrue(phase.incrementAndGet() >= 5,
                    "letting the stale ticket request reach a post-flip drain");
            helper.assertTrue(service.getPlayers().get(uuid) != null
                            && service.getPlayers().get(uuid) != oldState,
                    "premise: the dimension change replaced the state");
            helper.assertTrue(gen.getTotalSubmitted() == 0,
                    "a ticket request admitted before the dimension change must be DROPPED by "
                            + "the drain's dimension guard — submitting it would generate "
                            + "old-dimension coordinates inside the new dimension");
            helper.assertTrue(gen.getActiveCount() == 0,
                    "no generation entry may exist for the dropped stale request");
            helper.assertTrue(service.getOffThreadProcessor().pollGenerationTicketRequest() == null,
                    "the stale ticket request must be consumed by the drain, not left queued");
            // Success path cleanup (a failed run leaves only a delisted-on-cleanup mock).
            mock.setServerLevel(level);
            service.shutdown();
            server.getPlayerList().remove(mock);
        });
    }
}

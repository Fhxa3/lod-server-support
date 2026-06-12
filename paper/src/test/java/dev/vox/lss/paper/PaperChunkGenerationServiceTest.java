package dev.vox.lss.paper;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.common.processing.TickSnapshot;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.bukkit.Chunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Books-balance coverage for {@link PaperChunkGenerationService}. Paper's accounting is
 * async-callback based (unlike Fabric's ticket polling), so Fabric tests cannot cover it:
 * a leaked perPlayerActiveCount entry starves that player's generation until disconnect.
 * The launchAsyncLoad seam captures the launch instead of touching Bukkit's scheduler;
 * tests then fire onChunkReady exactly as the scheduled main-thread callback would.
 * Every outcome path (success, null chunk, extraction Throwable, timeout, removePlayer)
 * must drain the active map and free the per-player slots.
 */
// new LevelChunkSection(PalettedContainerFactory) is @Deprecated on Paper (anti-xray overload),
// but is the canonical vanilla ctor; same suppression as NbtSectionSerializerTest.
@SuppressWarnings("deprecation")
class PaperChunkGenerationServiceTest {

    private static PalettedContainerFactory FACTORY;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Same biome-registry construction as NbtSectionSerializerTest (success path needs
        // a real LevelChunkSection for PaperSectionSerializer to serialize).
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        src.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        FACTORY = PalettedContainerFactory.create(
                new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)));
    }

    // ---- harness ----

    record CapturedLaunch(PaperChunkGenerationService.PendingGenerationKey key,
                          ServerLevel level, int cx, int cz) {}

    static class CapturingGenService extends PaperChunkGenerationService {
        final List<CapturedLaunch> launches = new ArrayList<>();

        CapturingGenService(PaperConfig config) {
            super(config, null); // plugin only used by the real launchAsyncLoad, which is overridden
        }

        @Override
        void launchAsyncLoad(PendingGenerationKey key, ServerLevel level, int cx, int cz) {
            launches.add(new CapturedLaunch(key, level, cx, cz));
        }
    }

    private static PaperConfig config(int globalLimit, int perPlayerLimit, int timeoutSeconds) {
        var c = new PaperConfig();
        c.generationConcurrencyLimitGlobal = globalLimit;
        c.generationConcurrencyLimitPerPlayer = perPlayerLimit;
        c.generationTimeoutSeconds = timeoutSeconds;
        c.validate();
        return c;
    }

    private static ServerLevel overworldLevel() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        return level;
    }

    /** Bukkit Chunk stand-in — onChunkReady only null-checks it, never calls methods. */
    private static Chunk bukkitChunk() {
        return (Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(),
                new Class<?>[]{Chunk.class}, (p, m, a) -> null);
    }

    private static String diag(long submitted, long completed, int active, long timeouts, long removed) {
        return String.format("submitted=%d, completed=%d, active=%d, timeouts=%d, removed=%d",
                submitted, completed, active, timeouts, removed);
    }

    // ---- piggyback + launch accounting ----

    @Test
    void secondRequestForSameColumnPiggybacksOnSingleLaunch() {
        var svc = new CapturingGenService(config(8, 8, 60));
        var level = overworldLevel();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 3, -2, 11L));
        assertTrue(svc.submitGeneration(b, level, 3, -2, 22L));
        assertEquals(1, svc.launches.size(), "one async load serves both players");
        assertEquals(3, svc.launches.get(0).cx());
        assertEquals(-2, svc.launches.get(0).cz());
        assertEquals(diag(1, 0, 1, 0, 0), svc.getDiagnostics());

        // Failed load fans a failure outcome out to BOTH callbacks
        svc.onChunkReady(svc.launches.get(0).key(), level, null, 3, -2);
        var ready = svc.tick();
        assertEquals(2, ready.size());
        for (var r : ready) {
            assertNull(r.columnData(), "failed load reports ColumnNotGenerated");
            assertEquals(3, r.cx());
            assertEquals(-2, r.cz());
            assertEquals("minecraft:overworld", r.dimension());
        }
        assertEquals(11L, byPlayer(ready, a).submissionOrder());
        assertEquals(22L, byPlayer(ready, b).submissionOrder());
        assertEquals(diag(1, 0, 0, 0, 0), svc.getDiagnostics());
        assertTrue(svc.tick().isEmpty(), "outcomes are drained exactly once");
    }

    private static TickSnapshot.GenerationReadyData byPlayer(
            List<TickSnapshot.GenerationReadyData> ready, UUID uuid) {
        return ready.stream().filter(r -> r.playerUuid().equals(uuid)).findFirst().orElseThrow();
    }

    // ---- caps ----

    @Test
    void perPlayerCapBouncesOnlyThatPlayer() {
        var svc = new CapturingGenService(config(32, 2, 60));
        var level = overworldLevel();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        assertTrue(svc.submitGeneration(a, level, 1, 0, 2L));
        assertFalse(svc.submitGeneration(a, level, 2, 0, 3L), "third launch exceeds per-player cap");
        assertTrue(svc.submitGeneration(b, level, 2, 0, 4L), "other players are unaffected");
        assertEquals(3, svc.launches.size());
    }

    @Test
    void globalCapBouncesNewLaunches() {
        var svc = new CapturingGenService(config(2, 16, 60));
        var level = overworldLevel();

        assertTrue(svc.submitGeneration(UUID.randomUUID(), level, 0, 0, 1L));
        assertTrue(svc.submitGeneration(UUID.randomUUID(), level, 1, 0, 2L));
        assertFalse(svc.submitGeneration(UUID.randomUUID(), level, 2, 0, 3L));
        assertEquals(2, svc.launches.size());
        assertEquals(diag(2, 0, 2, 0, 0), svc.getDiagnostics());
    }

    @Test
    void completionFreesThePerPlayerSlot() {
        var svc = new CapturingGenService(config(32, 1, 60));
        var level = overworldLevel();
        UUID a = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        assertFalse(svc.submitGeneration(a, level, 1, 0, 2L), "cap 1: second column bounces while first is active");

        svc.onChunkReady(svc.launches.get(0).key(), level, null, 0, 0);
        assertEquals(1, svc.tick().size());
        assertTrue(svc.submitGeneration(a, level, 1, 0, 3L), "slot freed by the completed load");
    }

    // ---- removePlayer pruning + late callbacks ----

    @Test
    void removePlayerPrunesActiveAndLateCallbackIsNoOp() {
        var svc = new CapturingGenService(config(32, 1, 60));
        var level = overworldLevel();
        UUID a = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        svc.removePlayer(a);
        assertEquals(diag(1, 0, 0, 0, 1), svc.getDiagnostics());

        // Async load completes after the player left: must not emit, must not count completed
        svc.onChunkReady(svc.launches.get(0).key(), level, bukkitChunk(), 0, 0);
        assertTrue(svc.tick().isEmpty());
        assertEquals(diag(1, 0, 0, 0, 1), svc.getDiagnostics());

        assertTrue(svc.submitGeneration(a, level, 5, 5, 2L), "rejoining player starts with freed slots");
    }

    @Test
    void removePlayerKeepsSharedLaunchAliveForOtherPlayer() {
        var svc = new CapturingGenService(config(32, 4, 60));
        var level = overworldLevel();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        assertTrue(svc.submitGeneration(b, level, 0, 0, 2L));
        svc.removePlayer(a);
        assertEquals(diag(1, 0, 1, 0, 0), svc.getDiagnostics(),
                "launch survives (b still waits); removedInFlight only counts fully-orphaned launches");

        svc.onChunkReady(svc.launches.get(0).key(), level, null, 0, 0);
        var ready = svc.tick();
        assertEquals(1, ready.size(), "only the remaining player gets an outcome");
        assertEquals(b, ready.get(0).playerUuid());
        assertEquals(2L, ready.get(0).submissionOrder());
    }

    // ---- timeout ----

    @Test
    void timeoutEmitsFailureAndFreesSlots() {
        var svc = new CapturingGenService(config(32, 1, 1)); // 1s -> 20 ticks
        var level = overworldLevel();
        UUID a = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        for (int tick = 1; tick <= 20; tick++) {
            assertTrue(svc.tick().isEmpty(), "no timeout before tick 21 (tick " + tick + ")");
        }
        assertFalse(svc.submitGeneration(a, level, 1, 1, 2L), "slot still held right up to the timeout");

        var ready = svc.tick(); // tick 21: ticksWaiting exceeds timeoutTicks
        assertEquals(1, ready.size());
        assertNull(ready.get(0).columnData());
        assertEquals(a, ready.get(0).playerUuid());
        assertEquals(diag(1, 0, 0, 1, 0), svc.getDiagnostics());
        assertTrue(svc.submitGeneration(a, level, 1, 1, 3L), "timeout frees the per-player slot");

        // The real async load finishing after the timeout must be a no-op for the expired key
        svc.onChunkReady(svc.launches.get(0).key(), level, bukkitChunk(), 0, 0);
        assertTrue(svc.tick().isEmpty());
    }

    // ---- completion outcome paths ----

    @Test
    void nullChunkAfterAsyncLoadEmitsFailureAndFreesSlot() {
        var svc = new CapturingGenService(config(32, 1, 60));
        var level = overworldLevel();
        // getChunkNow returns null (mock default): "was null after async load completed" branch
        when(level.getChunkSource()).thenReturn(mock(ServerChunkCache.class));
        UUID a = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 4, 4, 1L));
        svc.onChunkReady(svc.launches.get(0).key(), level, bukkitChunk(), 4, 4);

        var ready = svc.tick();
        assertEquals(1, ready.size());
        assertNull(ready.get(0).columnData());
        assertEquals(diag(1, 0, 0, 0, 0), svc.getDiagnostics(), "not counted as completed");
        assertTrue(svc.submitGeneration(a, level, 5, 4, 2L));
    }

    @Test
    void extractionExceptionEmitsFailureAndFreesSlot() {
        var svc = new CapturingGenService(config(32, 1, 60));
        var level = overworldLevel();
        when(level.getChunkSource()).thenThrow(new IllegalStateException("boom"));
        UUID a = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        svc.onChunkReady(svc.launches.get(0).key(), level, bukkitChunk(), 0, 0); // must not throw

        var ready = svc.tick();
        assertEquals(1, ready.size());
        assertNull(ready.get(0).columnData());
        assertTrue(svc.submitGeneration(a, level, 1, 0, 2L), "slot freed despite the exception");
    }

    @Test
    void extractionErrorStillEmitsFailureBeforeRethrowing() {
        var svc = new CapturingGenService(config(32, 1, 60));
        var level = overworldLevel();
        when(level.getChunkSource()).thenThrow(new LinkageError("boom"));
        UUID a = UUID.randomUUID();

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        assertThrows(LinkageError.class,
                () -> svc.onChunkReady(svc.launches.get(0).key(), level, bukkitChunk(), 0, 0),
                "Errors propagate (not swallowed)");

        // ...but the books were balanced first: without the Throwable catch the slot would leak
        var ready = svc.tick();
        assertEquals(1, ready.size());
        assertNull(ready.get(0).columnData());
        assertTrue(svc.submitGeneration(a, level, 1, 0, 2L));
    }

    @Test
    void successFansOutSharedColumnDataToAllCallbacks() {
        var svc = new CapturingGenService(config(32, 1, 60));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        // Real section so PaperSectionSerializer produces actual bytes (mock light engine
        // reports no light data; minSectionY defaults to 0 on the mock).
        var section = new LevelChunkSection(FACTORY);
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        var nmsChunk = mock(LevelChunk.class);
        when(nmsChunk.getSections()).thenReturn(new LevelChunkSection[]{section});

        var lightEngine = mock(LevelLightEngine.class);
        when(lightEngine.getLayerListener(any())).thenReturn(mock(LayerLightEventListener.class));

        var chunkSource = mock(ServerChunkCache.class);
        when(chunkSource.getChunkNow(6, -9)).thenReturn(nmsChunk);

        var level = overworldLevel();
        when(level.getChunkSource()).thenReturn(chunkSource);
        when(level.getLightEngine()).thenReturn(lightEngine);

        assertTrue(svc.submitGeneration(a, level, 6, -9, 100L));
        assertTrue(svc.submitGeneration(b, level, 6, -9, 200L));
        svc.onChunkReady(svc.launches.get(0).key(), level, bukkitChunk(), 6, -9);

        var ready = svc.tick();
        assertEquals(2, ready.size());
        var forA = byPlayer(ready, a);
        var forB = byPlayer(ready, b);
        assertNotNull(forA.columnData());
        assertNotNull(forA.columnData().serializedSections(), "stone section serialized to bytes");
        assertSame(forA.columnData(), forB.columnData(), "one serialization shared by all callbacks");
        assertEquals(100L, forA.submissionOrder());
        assertEquals(200L, forB.submissionOrder());
        assertTrue(forA.columnTimestamp() > 0, "completed columns carry a real timestamp");
        assertEquals("minecraft:overworld", forA.dimension());
        assertEquals(diag(1, 1, 0, 0, 0), svc.getDiagnostics());

        // Per-player slots freed for both piggybacked players (cap is 1)
        assertTrue(svc.submitGeneration(a, level, 7, -9, 300L));
        assertTrue(svc.submitGeneration(b, level, 8, -9, 400L));
    }

    // ---- counter getters (soak exporter contract) ----

    /**
     * The soak harness (PaperSoakMetricsExporter) reads these getters — not the diag
     * string — and check_soak.py enforces A4 (submitted == completed + timeouts +
     * removed_in_flight) on them. Each counter must move at exactly its own lifecycle
     * transition, and the books must re-balance once every launch is accounted for.
     */
    @Test
    void counterGettersTrackTheLifecycleTheSoakExporterReads() {
        var svc = new CapturingGenService(config(32, 4, 1)); // 1s timeout -> 20 ticks
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        // Success-path wiring (same as successFansOutSharedColumnDataToAllCallbacks)
        var section = new LevelChunkSection(FACTORY);
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        var nmsChunk = mock(LevelChunk.class);
        when(nmsChunk.getSections()).thenReturn(new LevelChunkSection[]{section});
        var lightEngine = mock(LevelLightEngine.class);
        when(lightEngine.getLayerListener(any())).thenReturn(mock(LayerLightEventListener.class));
        var chunkSource = mock(ServerChunkCache.class);
        when(chunkSource.getChunkNow(0, 0)).thenReturn(nmsChunk);
        var level = overworldLevel();
        when(level.getChunkSource()).thenReturn(chunkSource);
        when(level.getLightEngine()).thenReturn(lightEngine);

        assertEquals(0L, svc.getTotalSubmitted());
        assertEquals(0, svc.getActiveCount());

        assertTrue(svc.submitGeneration(a, level, 0, 0, 1L));
        assertTrue(svc.submitGeneration(a, level, 1, 0, 2L));
        assertTrue(svc.submitGeneration(b, level, 2, 0, 3L));
        assertEquals(3L, svc.getTotalSubmitted());
        assertEquals(3, svc.getActiveCount());
        assertEquals(0L, svc.getTotalCompleted());

        // Piggyback on an active launch must NOT count as a new submission
        assertTrue(svc.submitGeneration(b, level, 0, 0, 4L));
        assertEquals(3L, svc.getTotalSubmitted());
        assertEquals(3, svc.getActiveCount());

        // (0,0) completes successfully -> completed only
        svc.onChunkReady(svc.launches.get(0).key(), level, bukkitChunk(), 0, 0);
        assertEquals(2, svc.tick().size(), "both piggybacked callbacks drain");
        assertEquals(1L, svc.getTotalCompleted());
        assertEquals(2, svc.getActiveCount());
        assertEquals(0L, svc.getTotalTimeouts());
        assertEquals(0L, svc.getTotalRemovedInFlight());

        // b leaves -> its now-orphaned launch (2,0) counts as removed-in-flight only
        svc.removePlayer(b);
        assertEquals(1L, svc.getTotalRemovedInFlight());
        assertEquals(1, svc.getActiveCount());
        assertEquals(1L, svc.getTotalCompleted());

        // (1,0) never completes -> timeout only
        for (int tick = 1; tick <= 21; tick++) svc.tick();
        assertEquals(1L, svc.getTotalTimeouts());
        assertEquals(0, svc.getActiveCount());

        // A4 books balance: submitted == completed + timeouts + removed_in_flight
        assertEquals(svc.getTotalSubmitted(),
                svc.getTotalCompleted() + svc.getTotalTimeouts() + svc.getTotalRemovedInFlight());
    }
}

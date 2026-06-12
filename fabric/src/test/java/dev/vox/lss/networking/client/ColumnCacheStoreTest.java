package dev.vox.lss.networking.client;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ColumnCacheStoreTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ResourceKey<Level> testDimension(String name) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:" + name));
    }

    private static Path getCacheFile(String serverAddress, ResourceKey<Level> dimension) {
        String dimKey = dimension.identifier().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
        String serverKey = serverAddress.replaceAll("[^a-zA-Z0-9._-]", "_");
        return FabricLoader.getInstance().getConfigDir()
                .resolve("lss").resolve("cache").resolve(serverKey).resolve(dimKey + ".bin");
    }

    @Test
    void saveAndLoadRoundtrip() {
        var dim = testDimension("roundtrip");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(100L, 1000L);
        map.put(200L, 2000L);
        map.put(300L, 3000L);

        ColumnCacheStore.save("test-server-rt", dim, map);
        var loaded = ColumnCacheStore.load("test-server-rt", dim);

        assertEquals(3, loaded.size());
        assertEquals(1000L, loaded.get(100L));
        assertEquals(2000L, loaded.get(200L));
        assertEquals(3000L, loaded.get(300L));
    }

    @Test
    void clearForServerRunsAfterQueuedAsyncSave() {
        var dim = testDimension("fifo_clear");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(7L, 700L);

        ColumnCacheStore.saveAsync("test-fifo-clear", dim, map);
        // clearForServer runs on the same FIFO IO thread and waits, so it must execute
        // AFTER the queued save — the save cannot resurrect the files we just cleared.
        ColumnCacheStore.clearForServer("test-fifo-clear");

        assertTrue(ColumnCacheStore.load("test-fifo-clear", dim).isEmpty(),
                "a queued async save must not survive a subsequent synchronous clear");
    }

    @Test
    void flushPendingIoWaitsForQueuedAsyncSave() {
        var dim = testDimension("flush_wait");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(9L, 900L);

        ColumnCacheStore.saveAsync("test-flush-wait", dim, map);
        // The soak/benchmark harness halts the JVM right after this call — it must
        // guarantee the queued save has landed on disk.
        ColumnCacheStore.flushPendingIo();

        assertEquals(900L, ColumnCacheStore.load("test-flush-wait", dim).get(9L));
        ColumnCacheStore.clearForServer("test-flush-wait");
    }

    @Test
    void missingFileReturnsEmpty() {
        var dim = testDimension("missing");
        var loaded = ColumnCacheStore.load("nonexistent-server", dim);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void invalidFormatVersionReturnsEmpty() throws IOException {
        var dim = testDimension("bad_version");
        Path file = getCacheFile("test-bad-version", dim);
        Files.createDirectories(file.getParent());
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(999); // wrong version
            out.writeInt(1);
            out.writeLong(1L);
            out.writeLong(2L);
        }

        var loaded = ColumnCacheStore.load("test-bad-version", dim);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void excessiveCountReturnsEmpty() throws IOException {
        var dim = testDimension("excess_count");
        Path file = getCacheFile("test-excess", dim);
        Files.createDirectories(file.getParent());
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(1); // correct version
            out.writeInt(3_000_000); // exceeds 2_000_000 guard
        }

        var loaded = ColumnCacheStore.load("test-excess", dim);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void truncatedDataReturnsPartial() throws IOException {
        var dim = testDimension("truncated");
        Path file = getCacheFile("test-truncated", dim);
        Files.createDirectories(file.getParent());
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(1); // correct version
            out.writeInt(5); // claims 5 entries
            // Only write 1 complete entry
            out.writeLong(42L);
            out.writeLong(100L);
            // Truncated — rest is missing
        }

        var loaded = ColumnCacheStore.load("test-truncated", dim);
        // IOException during read → returns partial map (whatever was read before error)
        // The implementation catches IOException and returns whatever was loaded
        assertTrue(loaded.size() <= 1);
    }

    @Test
    void clearForServerRemovesFiles() {
        var dim = testDimension("clear_test");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(1L, 1L);

        ColumnCacheStore.save("test-clear-server", dim, map);
        ColumnCacheStore.clearForServer("test-clear-server");

        var loaded = ColumnCacheStore.load("test-clear-server", dim);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void v2FileMigrationStripsLevelBits() throws IOException {
        var dim = testDimension("v2_migration");
        Path file = getCacheFile("test-v2-migration", dim);
        Files.createDirectories(file.getParent());
        long ts1 = 1_750_000_000L;
        long ts2 = 12_345L;
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(2); // v2 encoded values as (timestamp << 8 | detail level)
            out.writeInt(2);
            out.writeLong(100L);
            out.writeLong((ts1 << 8) | 7L);
            out.writeLong(200L);
            out.writeLong((ts2 << 8) | 0L);
        }

        var loaded = ColumnCacheStore.load("test-v2-migration", dim);
        assertEquals(2, loaded.size());
        // Loading v2 values raw would inflate timestamps ~256x — the server would then
        // answer up-to-date for stale columns forever.
        assertEquals(ts1, loaded.get(100L), "v2 entries must have the packed level bits stripped");
        assertEquals(ts2, loaded.get(200L));
    }

    @Test
    void v1FileLoadsTimestampsVerbatim() throws IOException {
        var dim = testDimension("v1_verbatim");
        Path file = getCacheFile("test-v1-verbatim", dim);
        Files.createDirectories(file.getParent());
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(1); // v1: raw timestamps, nothing packed
            out.writeInt(1);
            out.writeLong(42L);
            out.writeLong(1_700_000_000L);
        }

        var loaded = ColumnCacheStore.load("test-v1-verbatim", dim);
        assertEquals(1, loaded.size());
        assertEquals(1_700_000_000L, loaded.get(42L), "v1 values must not be shifted on load");
    }

    @Test
    void saveAsyncSnapshotsBeforeCallerMutates() {
        var dim = testDimension("snapshot");
        // Gate: occupy the FIFO IO thread with a slow save so the snapshot save below
        // cannot start until long after the caller's mutations have completed. Without
        // the defensive copy in saveAsync, the mutated map is what would get written.
        var gateMap = new Long2LongOpenHashMap();
        gateMap.defaultReturnValue(-1L);
        for (int i = 0; i < 20_000; i++) gateMap.put(i, i);
        ColumnCacheStore.saveAsync("test-snapshot-gate", dim, gateMap);

        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(1L, 111L);
        map.put(2L, 222L);
        ColumnCacheStore.saveAsync("test-snapshot", dim, map);
        // Mutate immediately — mirrors onDimensionChange's saveCache-then-clear ordering.
        map.put(1L, 999_999L);
        map.remove(2L);
        map.put(3L, 333L);

        ColumnCacheStore.flushPendingIo();
        var loaded = ColumnCacheStore.load("test-snapshot", dim);
        assertEquals(2, loaded.size(), "saved file must reflect the map as it was at saveAsync time");
        assertEquals(111L, loaded.get(1L));
        assertEquals(222L, loaded.get(2L));

        ColumnCacheStore.clearForServer("test-snapshot-gate");
        ColumnCacheStore.clearForServer("test-snapshot");
    }

    @Test
    void dimensionFilesDoNotBleedAcrossDimensions() {
        var dimA = testDimension("iso_a");
        var dimB = testDimension("iso_b");
        var mapA = new Long2LongOpenHashMap();
        mapA.defaultReturnValue(-1L);
        mapA.put(100L, 1111L);
        var mapB = new Long2LongOpenHashMap();
        mapB.defaultReturnValue(-1L);
        mapB.put(100L, 2222L); // same packed position, different dimension
        mapB.put(200L, 3333L);

        ColumnCacheStore.save("test-dim-iso", dimA, mapA);
        ColumnCacheStore.save("test-dim-iso", dimB, mapB);

        var loadedA = ColumnCacheStore.load("test-dim-iso", dimA);
        var loadedB = ColumnCacheStore.load("test-dim-iso", dimB);
        assertEquals(1, loadedA.size());
        assertEquals(1111L, loadedA.get(100L), "dimension A keeps its own timestamp for the shared position");
        assertEquals(2, loadedB.size());
        assertEquals(2222L, loadedB.get(100L));
        assertEquals(3333L, loadedB.get(200L));
    }

    @Test
    void clearForServerRemovesAllDimensionFiles() {
        var dimA = testDimension("clear_multi_a");
        var dimB = testDimension("clear_multi_b");
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(1L, 10L);

        ColumnCacheStore.save("test-clear-multi", dimA, map);
        ColumnCacheStore.save("test-clear-multi", dimB, map);
        ColumnCacheStore.clearForServer("test-clear-multi");

        assertTrue(ColumnCacheStore.load("test-clear-multi", dimA).isEmpty());
        assertTrue(ColumnCacheStore.load("test-clear-multi", dimB).isEmpty());
    }
}

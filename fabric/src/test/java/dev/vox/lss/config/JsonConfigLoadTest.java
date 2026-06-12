package dev.vox.lss.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vox.lss.common.config.ServerConfigBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins JsonConfig.load() lifecycle semantics against a real directory:
 * <ul>
 *   <li>a broken config file must never abort startup — defaults are used instead;</li>
 *   <li>a broken file must never be overwritten — the admin's hand-edit survives for fixing;</li>
 *   <li>a successfully parsed file IS re-saved, migrating newly added fields in;</li>
 *   <li>fields absent from the file keep their compiled defaults (GSON must instantiate via
 *       the no-arg constructor so field initializers run — see the landmine test below).</li>
 * </ul>
 * Uses a local ServerConfigBase subclass instead of LSSServerConfig so the test never trips
 * LSSServerConfig's static CONFIG initializer (FabricLoader config dir + real IO).
 */
class JsonConfigLoadTest {

    // The on-disk name admins know; renaming it would orphan every existing install's config.
    private static final String FILE = "lss-server-config.json";

    /** Local stand-in for LSSServerConfig: same fields/defaults/clamps via ServerConfigBase. */
    public static class TestServerConfig extends ServerConfigBase {
        static TestServerConfig load(Path configDir) {
            return load(TestServerConfig.class, FILE_NAME, configDir);
        }
    }

    private static List<String> serializedFieldNames() {
        List<String> names = Arrays.stream(TestServerConfig.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .toList();
        assertTrue(names.size() >= 13, "field reflection broke, found only: " + names);
        return names;
    }

    private static JsonObject savedJson(Path configDir) throws Exception {
        return JsonParser.parseString(Files.readString(configDir.resolve(FILE))).getAsJsonObject();
    }

    @Test
    void missingFileLoadsDefaultsAndCreatesFileWithAllFields(@TempDir Path tempDir) throws Exception {
        Path configDir = tempDir.resolve("config"); // not yet existing — load must create it
        TestServerConfig c = TestServerConfig.load(configDir);

        assertEquals(256, c.lodDistanceChunks);
        assertEquals(20_971_520, c.bytesPerSecondLimitPerPlayer);
        assertTrue(Files.isRegularFile(configDir.resolve(FILE)));

        JsonObject saved = savedJson(configDir);
        for (String key : serializedFieldNames()) {
            assertTrue(saved.has(key), "defaults file missing field " + key);
        }
        assertEquals(256, saved.get("lodDistanceChunks").getAsInt());
    }

    @Test
    void truncatedFileLoadsDefaultsAndPreservesFileExactly(@TempDir Path configDir) throws Exception {
        String broken = "{\"lodDistanceChunks\": 64"; // interrupted write: no closing brace
        Files.writeString(configDir.resolve(FILE), broken);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(256, c.lodDistanceChunks); // defaults, not the half-written value
        assertEquals(broken, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void garbageTextLoadsDefaultsAndPreservesFileExactly(@TempDir Path configDir) throws Exception {
        String broken = "this is not json at all {{{";
        Files.writeString(configDir.resolve(FILE), broken);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(256, c.lodDistanceChunks);
        assertEquals(broken, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void wrongTypedFieldLoadsDefaultsAndPreservesFileExactly(@TempDir Path configDir) throws Exception {
        String broken = "{\"lodDistanceChunks\": \"lots\"}"; // valid JSON, unbindable value
        Files.writeString(configDir.resolve(FILE), broken);

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(256, c.lodDistanceChunks);
        assertEquals(broken, Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void emptyFileLoadsDefaultsAndIsLeftUntouched(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE), "");

        TestServerConfig c = assertDoesNotThrow(() -> TestServerConfig.load(configDir));

        assertEquals(256, c.lodDistanceChunks);
        assertEquals("", Files.readString(configDir.resolve(FILE)));
    }

    @Test
    void partialFileKeepsCompiledDefaultsForAbsentFields(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE), "{\"lodDistanceChunks\": 64}");

        TestServerConfig c = TestServerConfig.load(configDir);

        assertEquals(64, c.lodDistanceChunks);
        // Every absent field must keep its compiled default. GSON only runs field
        // initializers when it can use the no-arg constructor; adding ANY explicit
        // constructor silently switches it to Unsafe.allocateInstance, zeroing every
        // default (then validate() clamps the zeros to the minimums, e.g. 20 MB/s -> 1 KB/s).
        // These exact-value assertions are the only guard against that landmine.
        assertTrue(c.enabled);
        assertEquals(20_971_520, c.bytesPerSecondLimitPerPlayer);
        assertEquals(5, c.diskReaderThreads);
        assertEquals(4000, c.sendQueueLimitPerPlayer);
        assertEquals(104_857_600, c.bytesPerSecondLimitGlobal);
        assertTrue(c.enableChunkGeneration);
        assertEquals(32, c.generationConcurrencyLimitGlobal);
        assertEquals(60, c.generationTimeoutSeconds);
        assertEquals(10, c.dirtyBroadcastIntervalSeconds);
        assertEquals(200, c.syncOnLoadConcurrencyLimitPerPlayer);
        assertEquals(16, c.generationConcurrencyLimitPerPlayer);
        assertEquals(32, c.perDimensionTimestampCacheSizeMB);
    }

    @Test
    void partialFileLoadResavesWithAllFieldsMigratedIn(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE), "{\"lodDistanceChunks\": 64}");

        TestServerConfig.load(configDir);

        JsonObject saved = savedJson(configDir);
        assertEquals(64, saved.get("lodDistanceChunks").getAsInt()); // admin's value kept
        for (String key : serializedFieldNames()) {
            assertTrue(saved.has(key), "re-saved file missing migrated field " + key);
        }
        assertEquals(20_971_520, saved.get("bytesPerSecondLimitPerPlayer").getAsInt());
    }

    @Test
    void outOfRangeValueInFileIsClampedInMemoryAndOnDisk(@TempDir Path configDir) throws Exception {
        Files.writeString(configDir.resolve(FILE), "{\"lodDistanceChunks\": 99999}");

        TestServerConfig c = TestServerConfig.load(configDir);

        assertEquals(512, c.lodDistanceChunks); // LSSConstants.MAX_LOD_DISTANCE
        assertEquals(512, savedJson(configDir).get("lodDistanceChunks").getAsInt());
    }
}

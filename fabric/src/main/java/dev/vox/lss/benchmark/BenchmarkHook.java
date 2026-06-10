package dev.vox.lss.benchmark;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.client.LSSClientNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.nio.file.Path;
import java.util.Map;

public final class BenchmarkHook {
    private static final boolean ENABLED = Boolean.getBoolean("lss.benchmark");
    private static final int DURATION_SECONDS = Integer.getInteger("lss.benchmark.duration", 60);

    // Latest client metrics snapshot — captured every second before disconnect clears them
    private static volatile Map<String, Object> latestClientSnapshot;

    private BenchmarkHook() {}

    public static void initServer() {
        String soakScenario = System.getProperty("lss.soak.scenario");
        if (soakScenario != null && !soakScenario.isBlank()) {
            // Soak mode: the scenario timeline owns the server lifetime — the benchmark
            // tick-halt below must not arm or it would kill a multi-minute scenario at 60s.
            SoakScenarioDriver.init(soakScenario);
            return;
        }
        if (!ENABLED) return;
        LSSLogger.info("[Benchmark] Server hook active, duration=" + DURATION_SECONDS + "s");

        final int targetTicks = DURATION_SECONDS * LSSConstants.TICKS_PER_SECOND;
        final int[] tickCount = {0};

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LSSLogger.info("[Benchmark] Server started, counting " + targetTicks + " ticks");
            tickCount[0] = 0;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCount[0]++;
            if (tickCount[0] == targetTicks) {
                LSSLogger.info("[Benchmark] Duration reached (" + DURATION_SECONDS + "s), exporting metrics");
                Path outputFile = Path.of("benchmark-results", "server.json");
                BenchmarkMetricsExporter.exportServer(outputFile, DURATION_SECONDS);
                LSSLogger.info("[Benchmark] Halting server");
                server.halt(false);
            }
        });
    }

    public static void initClient() {
        if (Boolean.getBoolean("lss.soak")) {
            initSoakClient();
            return;
        }
        if (!ENABLED) return;
        LSSLogger.info("[Benchmark] Client hook active");

        // Register a no-op consumer so the handshake includes CAPABILITY_VOXEL_COLUMNS.
        // Without this, the server skips request routing for players with capabilities=0.
        LSSApi.registerColumnConsumer((level, dimension, chunkX, chunkZ, columnData) -> {});

        // Snapshot client metrics every second so we have data even after disconnect clears them
        final int[] clientTick = {0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            clientTick[0]++;
            if (clientTick[0] % LSSConstants.TICKS_PER_SECOND == 0 && LSSClientNetworking.isServerEnabled()) {
                latestClientSnapshot = BenchmarkMetricsExporter.buildClientMetrics();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LSSLogger.info("[Benchmark] Client disconnected, exporting metrics");
            Path outputFile = Path.of("benchmark-results", "client.json");
            // Use the latest snapshot since LSSClientNetworking.DISCONNECT may have already cleared state
            var snapshot = latestClientSnapshot;
            if (snapshot != null) {
                BenchmarkMetricsExporter.writeClientSnapshot(outputFile, snapshot);
            } else {
                BenchmarkMetricsExporter.exportClient(outputFile);
            }
            LSSLogger.info("[Benchmark] Exiting client");
            // halt(0) instead of exit(0): skip MC shutdown hooks for fast benchmark teardown
            Runtime.getRuntime().halt(0);
        });
    }

    /**
     * Soak client mode (-Dlss.soak): append a snapshot row to soak-results/client.jsonl
     * every 5 seconds, and on disconnect write a final row, then synchronously flush the
     * column-cache IO queue BEFORE halting — the disconnect save runs async on a daemon
     * thread and halt(0) would otherwise lose it, silently turning a warm-rejoin scenario
     * into a cold one.
     */
    private static void initSoakClient() {
        LSSLogger.info("[Soak] Client hook active");
        Path outputFile = Path.of("soak-results", "client.jsonl");

        // Register a no-op consumer so the handshake includes CAPABILITY_VOXEL_COLUMNS.
        LSSApi.registerColumnConsumer((level, dimension, chunkX, chunkZ, columnData) -> {});

        final int[] clientTick = {0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            clientTick[0]++;
            if (clientTick[0] % (5 * LSSConstants.TICKS_PER_SECOND) == 0 && LSSClientNetworking.isServerEnabled()) {
                appendSoakClientRow(outputFile, "snapshot");
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LSSLogger.info("[Soak] Client disconnected, writing final snapshot");
            appendSoakClientRow(outputFile, "disconnect");
            // The LSS disconnect handler queued the cache save before us in registration
            // order; this drains the single-threaded IO executor so the write lands.
            dev.vox.lss.networking.client.ColumnCacheStore.flushPendingIo();
            LSSLogger.info("[Soak] Cache flushed, exiting client");
            Runtime.getRuntime().halt(0);
        });
    }

    private static void appendSoakClientRow(Path outputFile, String event) {
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("event", event);
        row.put("wallMs", System.currentTimeMillis());
        row.putAll(BenchmarkMetricsExporter.buildClientSnapshot());
        BenchmarkMetricsExporter.appendJsonLine(outputFile, row);
    }
}

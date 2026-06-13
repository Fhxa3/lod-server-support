package dev.vox.lss.benchmark;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HD-046: the harness gate separation. Pins (1) that under -Dlss.soak.scenario the 60s
 * benchmark auto-halt NEVER arms — even with -Dlss.benchmark simultaneously true, which
 * is why this class latches BenchmarkHook.ENABLED=true in a static initializer before
 * the hook class loads; (2) the positive control that the benchmark path alone DOES arm
 * the SERVER_STARTED+tick hooks (proving the registration-count oracle is live); (3) the
 * BenchmarkBridge anyHarnessEnabled property matrix; (4) that with no harness properties
 * the bridge activates nothing (the production-jar inertness path).
 *
 * <p>Registration is observed by counting handlers on the real Fabric event objects via
 * reflection (ArrayBackedEvent.handlers) — registered handlers never fire here (no
 * server/client ever starts in T1).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BenchmarkBridgeGateTest {

    static {
        // Must precede the first BenchmarkHook reference: ENABLED is a static final
        // latched at class init, and test 1's premise requires it to be true.
        System.setProperty("lss.benchmark", "true");
    }

    private static String originalBenchmark;
    private static String originalSoak;
    private static String originalScenario;

    @BeforeAll
    static void saveProperties() {
        // lss.benchmark was already overwritten by the static initializer above, which runs
        // before anything else in this class; in this JVM it is unset outside this test.
        originalBenchmark = null;
        originalSoak = System.getProperty("lss.soak");
        originalScenario = System.getProperty("lss.soak.scenario");
    }

    @AfterAll
    static void restoreProperties() {
        restore("lss.benchmark", originalBenchmark);
        restore("lss.soak", originalSoak);
        restore("lss.soak.scenario", originalScenario);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static int handlerCount(Event<?> event) {
        for (Class<?> c = event.getClass(); c != null; c = c.getSuperclass()) {
            try {
                var field = c.getDeclaredField("handlers");
                field.setAccessible(true);
                return ((Object[]) field.get(event)).length;
            } catch (NoSuchFieldException ignored) {
                // walk up
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("fabric Event impl " + event.getClass().getName()
                + " no longer has a 'handlers' field — update this reflection probe");
    }

    private static boolean hookEnabledFlag() throws Exception {
        var field = BenchmarkHook.class.getDeclaredField("ENABLED");
        field.setAccessible(true);
        return field.getBoolean(null);
    }

    private static boolean anyHarnessEnabled() throws Exception {
        Class<?> bridge = Class.forName("dev.vox.lss.BenchmarkBridge");
        Method m = bridge.getDeclaredMethod("anyHarnessEnabled");
        m.setAccessible(true);
        return (Boolean) m.invoke(null);
    }

    private static void invokeBridge(String method) throws Exception {
        Class<?> bridge = Class.forName("dev.vox.lss.BenchmarkBridge");
        Method m = bridge.getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(null);
    }

    @Test
    @Order(1)
    void soakScenarioWinsAndTheBenchmarkHaltNeverArmsEvenWithBenchmarkEnabled() throws Exception {
        assertTrue(hookEnabledFlag(),
                "premise: lss.benchmark must have latched true at BenchmarkHook class init —"
                        + " if this fails another test loaded the hook first and this class is vacuous");
        System.setProperty("lss.soak.scenario", "/nonexistent/lss-gate-test-scenario.json");

        int started = handlerCount(ServerLifecycleEvents.SERVER_STARTED);
        int ticks = handlerCount(ServerTickEvents.END_SERVER_TICK);
        int joins = handlerCount(ServerPlayConnectionEvents.JOIN);

        // Unreadable scenario fails fast — and the soak branch must run FIRST, so the
        // benchmark halt (armed via SERVER_STARTED + tick hooks) is never reached.
        assertThrows(IllegalStateException.class, BenchmarkHook::initServer);

        assertEquals(started, handlerCount(ServerLifecycleEvents.SERVER_STARTED),
                "the 60s benchmark auto-halt must not arm under -Dlss.soak.scenario");
        assertEquals(ticks, handlerCount(ServerTickEvents.END_SERVER_TICK));
        assertEquals(joins, handlerCount(ServerPlayConnectionEvents.JOIN));
    }

    @Test
    @Order(2)
    void validSoakScenarioRegistersTheDriverWithoutTheBenchmarkHalt() throws Exception {
        Path scenario = Files.createTempFile("lss-gate-scenario", ".json");
        scenario.toFile().deleteOnExit();
        Files.writeString(scenario, "{\"steps\":[],\"end\":{\"anchor\":1,\"at\":5}}");
        System.setProperty("lss.soak.scenario", scenario.toString());

        int started = handlerCount(ServerLifecycleEvents.SERVER_STARTED);
        int ticks = handlerCount(ServerTickEvents.END_SERVER_TICK);
        int joins = handlerCount(ServerPlayConnectionEvents.JOIN);

        BenchmarkHook.initServer();

        assertEquals(started, handlerCount(ServerLifecycleEvents.SERVER_STARTED),
                "soak mode must never arm the benchmark SERVER_STARTED hook (lss.benchmark is still true)");
        assertEquals(ticks + 1, handlerCount(ServerTickEvents.END_SERVER_TICK),
                "the soak driver registers exactly one tick handler");
        assertEquals(joins + 1, handlerCount(ServerPlayConnectionEvents.JOIN),
                "the soak driver registers exactly one join anchor handler");
    }

    @Test
    @Order(3)
    void benchmarkAloneArmsTheHaltHooksProvingTheCounterOracle() throws Exception {
        System.clearProperty("lss.soak.scenario");

        int started = handlerCount(ServerLifecycleEvents.SERVER_STARTED);
        int ticks = handlerCount(ServerTickEvents.END_SERVER_TICK);
        int joins = handlerCount(ServerPlayConnectionEvents.JOIN);

        BenchmarkHook.initServer();

        assertEquals(started + 1, handlerCount(ServerLifecycleEvents.SERVER_STARTED),
                "positive control: the benchmark path must register its start hook,"
                        + " otherwise the zero-delta assertions above prove nothing");
        assertEquals(ticks + 1, handlerCount(ServerTickEvents.END_SERVER_TICK));
        assertEquals(joins, handlerCount(ServerPlayConnectionEvents.JOIN),
                "the benchmark path registers no join handler");
    }

    @Test
    @Order(4)
    void bridgeActivatesNothingWithoutHarnessProperties() throws Exception {
        System.clearProperty("lss.benchmark");
        System.clearProperty("lss.soak");
        System.clearProperty("lss.soak.scenario");

        int started = handlerCount(ServerLifecycleEvents.SERVER_STARTED);
        int serverTicks = handlerCount(ServerTickEvents.END_SERVER_TICK);
        int clientTicks = handlerCount(ClientTickEvents.END_CLIENT_TICK);
        int disconnects = handlerCount(ClientPlayConnectionEvents.DISCONNECT);

        // The bridge reads the properties live (unlike the hook's latched ENABLED), so this
        // pins the production entry path: property-less JVMs reflect into nothing.
        invokeBridge("initServer");
        invokeBridge("initClient");

        assertEquals(started, handlerCount(ServerLifecycleEvents.SERVER_STARTED));
        assertEquals(serverTicks, handlerCount(ServerTickEvents.END_SERVER_TICK));
        assertEquals(clientTicks, handlerCount(ClientTickEvents.END_CLIENT_TICK));
        assertEquals(disconnects, handlerCount(ClientPlayConnectionEvents.DISCONNECT));
    }

    @Test
    @Order(5)
    void anyHarnessEnabledFollowsTheThreePropertyMatrix() throws Exception {
        System.clearProperty("lss.benchmark");
        System.clearProperty("lss.soak");
        System.clearProperty("lss.soak.scenario");
        assertFalse(anyHarnessEnabled(), "no properties -> disabled");

        System.setProperty("lss.benchmark", "true");
        assertTrue(anyHarnessEnabled(), "-Dlss.benchmark arms the gate");
        System.clearProperty("lss.benchmark");

        System.setProperty("lss.soak", "true");
        assertTrue(anyHarnessEnabled(), "-Dlss.soak arms the gate");
        System.clearProperty("lss.soak");

        System.setProperty("lss.soak.scenario", "scripts/soak-scenarios/x.json");
        assertTrue(anyHarnessEnabled(), "a non-blank scenario path arms the gate");

        System.setProperty("lss.soak.scenario", "");
        assertFalse(anyHarnessEnabled(), "an empty scenario property must count as unset");

        System.setProperty("lss.soak.scenario", "   ");
        assertFalse(anyHarnessEnabled(), "a blank scenario property must count as unset");
        System.clearProperty("lss.soak.scenario");
    }
}

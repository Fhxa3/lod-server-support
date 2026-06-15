package dev.vox.lss.paper;

import dev.vox.lss.common.LSSLogger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Reflective bridge to the dev-only soak harness package (mirrors Fabric's
 * BenchmarkBridge). dev.vox.lss.paper.soak is excluded from the release shadowJar, so the
 * plugin entry point must not reference it directly — soak runs use the soakShadowJar
 * variant where it is present, production jars simply no-op here.
 * Gate: -Dlss.soak.scenario (scripts/soak.sh SOAK_PLATFORM=paper).
 */
final class PaperSoakBridge {
    private PaperSoakBridge() {}

    static void init(LSSPaperPlugin plugin) {
        String scenario = System.getProperty("lss.soak.scenario");
        if (scenario == null || scenario.isBlank()) return;
        try {
            var driver = Class.forName("dev.vox.lss.paper.soak.PaperSoakScenarioDriver");
            MethodHandles.lookup()
                    .findStatic(driver, "init", MethodType.methodType(void.class, LSSPaperPlugin.class))
                    .invoke(plugin);
        } catch (ClassNotFoundException e) {
            LSSLogger.warn("-Dlss.soak.scenario is set but the soak classes are not present "
                    + "(they are excluded from release jars; use the soakShadowJar dev build)");
        } catch (Throwable t) {
            LSSLogger.error("Failed to initialize soak scenario driver", t);
        }
    }
}

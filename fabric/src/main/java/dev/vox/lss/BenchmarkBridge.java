package dev.vox.lss;

import dev.vox.lss.common.LSSLogger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Reflective bridge to the benchmark harness. The dev.vox.lss.benchmark package is
 * excluded from the production jar (it is a dev-only tool gated behind -Dlss.benchmark),
 * so the entrypoints must not reference it directly — dev runs execute from class
 * directories where it is present, production jars simply no-op here.
 */
final class BenchmarkBridge {
    private BenchmarkBridge() {}

    static void initServer() {
        invoke("initServer");
    }

    static void initClient() {
        invoke("initClient");
    }

    private static void invoke(String method) {
        if (!Boolean.getBoolean("lss.benchmark")) return;
        try {
            var hook = Class.forName("dev.vox.lss.benchmark.BenchmarkHook");
            MethodHandles.lookup()
                    .findStatic(hook, method, MethodType.methodType(void.class))
                    .invoke();
        } catch (ClassNotFoundException e) {
            LSSLogger.warn("-Dlss.benchmark is set but the benchmark classes are not present "
                    + "(they are excluded from release jars; use a dev run)");
        } catch (Throwable t) {
            LSSLogger.error("Failed to initialize benchmark hook", t);
        }
    }
}

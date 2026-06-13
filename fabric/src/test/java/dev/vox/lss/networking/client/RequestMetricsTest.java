package dev.vox.lss.networking.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the RequestMetrics surface the soak conservation laws lean on: lifetime totals are
 * monotonic — reset() (dimension change / fresh session config) clears only the rolling-rate
 * state — and totalPositionsRequested counts exactly at recordSendCycle (send time), so the
 * soak checker can balance requests against responses across dimension trips.
 */
class RequestMetricsTest {

    @Test
    void resetClearsRollingWindowsAndRatesButTotalsSurvive() {
        var m = new RequestMetrics();
        m.recordSendCycle(5);
        m.recordColumnReceived();
        m.recordUpToDate();
        m.recordNotGenerated();
        m.recordRateLimited();
        m.recordIngestFailure();
        m.updateRollingRates(); // first update window: counts > 0 -> rates strictly > 0
        assertTrue(m.getReceiveRate() > 0, "premise: a live rolling receive rate");
        assertTrue(m.getRequestRate() > 0, "premise: a live rolling request rate");

        m.reset();

        assertEquals(1, m.getTotalSendCycles(),
                "totals are lifetime-monotonic across reset (soak A1/A6 anchors)");
        assertEquals(5, m.getTotalPositionsRequested());
        assertEquals(1, m.getTotalColumnsReceived());
        assertEquals(1, m.getTotalUpToDate());
        assertEquals(1, m.getTotalNotGenerated());
        assertEquals(1, m.getTotalRateLimited());
        assertEquals(1, m.getTotalIngestFailures());
        assertEquals(0.0, m.getReceiveRate(), "rolling rates cleared");
        assertEquals(0.0, m.getRequestRate());

        // The window counters were cleared too: an immediate rate update right after reset
        // must compute from empty windows, not resurrect pre-reset activity.
        m.updateRollingRates();
        assertEquals(0.0, m.getReceiveRate(),
                "pre-reset window counts must not leak into post-reset rates");
        assertEquals(0.0, m.getRequestRate());
    }

    @Test
    void totalPositionsRequestedIncrementsOnlyAtRecordSendCycle() {
        var m = new RequestMetrics();
        m.recordColumnReceived();
        m.recordUpToDate();
        m.recordNotGenerated();
        m.recordRateLimited();
        m.recordIngestFailure();
        m.updateRollingRates();
        assertEquals(0, m.getTotalPositionsRequested(),
                "no response or rate path may count as a request");
        assertEquals(0, m.getTotalSendCycles());

        m.recordSendCycle(7);
        m.recordSendCycle(0); // an empty cycle counts as a cycle, never as positions

        assertEquals(7, m.getTotalPositionsRequested(),
                "requested positions count at send time (recordSendCycle)");
        assertEquals(2, m.getTotalSendCycles());
    }
}

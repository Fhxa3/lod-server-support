package dev.vox.lss.networking.server;

import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.processing.PlayerBandwidthTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SharedBandwidthLimiterTest {

    @Test
    void singlePlayerGetsFullAllocation() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        long allocation = limiter.getPerPlayerAllocation(1);
        assertEquals(10_000_000, allocation);
    }

    @Test
    void multiPlayerFairSplit() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        long allocation = limiter.getPerPlayerAllocation(4);
        assertEquals(2_500_000, allocation);
    }

    @Test
    void zeroPlayersReturnsZero() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        long allocation = limiter.getPerPlayerAllocation(0);
        assertEquals(0, allocation);
    }

    @Test
    void recordSendReducesRemaining() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        limiter.recordSend(3_000_000);
        long allocation = limiter.getPerPlayerAllocation(1);
        assertEquals(7_000_000, allocation);
    }

    @Test
    void budgetExhaustedReturnsZero() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        limiter.recordSend(10_000_000);
        long allocation = limiter.getPerPlayerAllocation(1);
        assertEquals(0, allocation);
    }

    @Test
    void overBudgetReturnsZero() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        limiter.recordSend(15_000_000);
        long allocation = limiter.getPerPlayerAllocation(1);
        assertEquals(0, allocation);
    }

    @Test
    void tokensRefillAfterTime() throws InterruptedException {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        limiter.recordSend(10_000_000);
        assertEquals(0, limiter.getPerPlayerAllocation(1));

        Thread.sleep(150);

        long allocation = limiter.getPerPlayerAllocation(1);
        assertTrue(allocation > 0, "Tokens should refill after elapsed time");
        assertTrue(allocation <= 10_000_000, "Tokens should not exceed max capacity");
    }

    @Test
    void tokensCappedAtMaxCapacity() throws InterruptedException {
        var limiter = new SharedBandwidthLimiter(10_000_000);

        // Wait for potential over-refill
        Thread.sleep(200);

        long allocation = limiter.getPerPlayerAllocation(1);
        assertEquals(10_000_000, allocation, "Tokens should be capped at maxBytesPerSecond");
    }

    @Test
    void totalBytesSentTracking() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        assertEquals(0, limiter.getTotalBytesSent(), "Total should be 0 initially");
        limiter.recordSend(1000);
        limiter.recordSend(2000);
        assertEquals(3000, limiter.getTotalBytesSent(), "Total should accumulate sends");
        limiter.recordSend(500);
        assertEquals(3500, limiter.getTotalBytesSent(), "Total should keep accumulating");
    }

    // ---- PlayerBandwidthTracker burst cap ----

    @Test
    void playerBurstAfterIdleIsCappedAtQuarterAllocation() throws InterruptedException {
        // An idle player accumulates refill; without the allocation/4 burst cap, the
        // backlog flushes in one tick as a lag spike (invisible in per-second averages).
        var tracker = new PlayerBandwidthTracker();
        long allocation = 4_000_000; // burst cap = allocation / 4 = 1_000_000
        int chunk = 50_000;

        // Idle past the 250ms burst window: the uncapped refill (~1.6MB) exceeds the cap
        Thread.sleep(400);

        long sent = 0;
        while (tracker.canSend(allocation) && sent < allocation) {
            tracker.recordSend(chunk);
            sent += chunk;
        }

        assertTrue(sent >= 1_000_000,
                "post-idle tokens must cover the full burst cap (sent=" + sent + ")");
        // Slack allows for refill drift if milliseconds elapse inside the loop (4000 bytes/ms);
        // an uncapped refill would send >= 1_600_000.
        assertTrue(sent <= 1_300_000,
                "post-idle burst must be capped at allocation/4, not the raw refill (sent=" + sent + ")");
    }
}

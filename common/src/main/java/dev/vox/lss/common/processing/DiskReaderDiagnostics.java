package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;

import java.util.concurrent.atomic.AtomicLong;

public class DiskReaderDiagnostics {
    private final AtomicLong submittedCount = new AtomicLong();
    private final AtomicLong completedCount = new AtomicLong();
    private final AtomicLong notFoundCount = new AtomicLong();
    private final AtomicLong allAirCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong saturationCount = new AtomicLong();
    private final AtomicLong totalReadTimeNanos = new AtomicLong();

    public void recordSubmitted() { this.submittedCount.incrementAndGet(); }
    public void recordCompleted(long readTimeNanos) {
        this.totalReadTimeNanos.addAndGet(readTimeNanos);
        this.completedCount.incrementAndGet();
    }
    public void recordNotFound() { this.notFoundCount.incrementAndGet(); }
    public void recordAllAir() { this.allAirCount.incrementAndGet(); }
    public void recordError() { this.errorCount.incrementAndGet(); }
    public void recordSaturation() { this.saturationCount.incrementAndGet(); }

    public String formatDiagnostics(int pendingCount) {
        long completed = this.completedCount.get();
        double avgMs = completed > 0 ? (this.totalReadTimeNanos.get() / (double) completed) / LSSConstants.NANOS_PER_MS : 0;
        long saturated = this.saturationCount.get();
        return String.format("submitted=%d, completed=%d, not_found=%d, all_air=%d, errors=%d, saturated=%d, avg_read=%.1fms, pending=%d",
                this.submittedCount.get(), completed, this.notFoundCount.get(), this.allAirCount.get(),
                this.errorCount.get(), saturated, avgMs, pendingCount);
    }

    /**
     * Returns the count of disk reads that successfully produced section data.
     * Every completion is exactly one of {successful, notFound, allAir, error, saturated} —
     * the saturation and error paths record a completion too, so they must be subtracted.
     */
    public long getSuccessfulReadCount() {
        return this.completedCount.get() - this.notFoundCount.get() - this.allAirCount.get()
                - this.errorCount.get() - this.saturationCount.get();
    }

    public long getSubmittedCount() { return this.submittedCount.get(); }
    public long getCompletedCount() { return this.completedCount.get(); }
    public long getNotFoundCount() { return this.notFoundCount.get(); }
    public long getAllAirCount() { return this.allAirCount.get(); }
    public long getErrorCount() { return this.errorCount.get(); }
    public long getSaturationCount() { return this.saturationCount.get(); }
    public long getTotalReadTimeNanos() { return this.totalReadTimeNanos.get(); }

}

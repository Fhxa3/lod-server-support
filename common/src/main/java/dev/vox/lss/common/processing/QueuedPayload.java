package dev.vox.lss.common.processing;

/**
 * A ready-to-send column payload queued for the main-thread flush, ordered by submission
 * sequence. Generic over the platform payload type (Fabric: CustomPacketPayload,
 * Paper: encoded byte[]) so common code can read {@link #estimatedBytes()} without
 * naming an MC type.
 */
public record QueuedPayload<T>(T payload, int estimatedBytes, long submissionOrder)
        implements Comparable<QueuedPayload<T>> {
    @Override
    public int compareTo(QueuedPayload<T> other) {
        return Long.compare(this.submissionOrder, other.submissionOrder);
    }
}

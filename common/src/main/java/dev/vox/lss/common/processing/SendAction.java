package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;

import java.util.UUID;

/**
 * Actions produced by the processing thread, consumed by the main thread.
 * These represent packets that must be sent from the main thread.
 */
public sealed interface SendAction {
    UUID playerUuid();
    int requestId();

    byte responseType();

    record RateLimited(UUID playerUuid, int requestId) implements SendAction {
        @Override public byte responseType() { return LSSConstants.RESPONSE_RATE_LIMITED; }
    }

    record ColumnUpToDate(UUID playerUuid, int requestId) implements SendAction {
        @Override public byte responseType() { return LSSConstants.RESPONSE_UP_TO_DATE; }
    }

    record ColumnNotGenerated(UUID playerUuid, int requestId) implements SendAction {
        @Override public byte responseType() { return LSSConstants.RESPONSE_NOT_GENERATED; }
    }
}

package dev.vox.lss.common.processing;

/**
 * An in-flight request tracked by the processing thread. The {@code heldSlot} records
 * which admission slot this entry occupies; slot occupancy is derived by counting
 * pending entries, so adding/removing a pending entry IS the acquire/release.
 */
public record PendingRequest(int cx, int cz, RequestType type, SlotType heldSlot) {}

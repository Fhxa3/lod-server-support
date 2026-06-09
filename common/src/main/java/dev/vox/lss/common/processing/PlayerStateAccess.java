package dev.vox.lss.common.processing;

import java.util.UUID;

/**
 * Interface for player state methods used by {@link OffThreadProcessor}.
 * Implemented by both Fabric's PlayerRequestState and Paper's PaperPlayerRequestState,
 * eliminating trivial delegate methods in the platform-specific processors.
 */
public interface PlayerStateAccess {
    boolean hasDiskReadDone(int cx, int cz);
    void markDiskReadDone(int cx, int cz);
    void clearDiskReadDone(long[] positions);
    int getSendQueueSize();
    boolean supportsVoxelColumns();
    UUID getPlayerUUID();

    // Per-request methods
    IncomingRequest pollIncomingRequest();
    /**
     * Admit a request if its slot has capacity: on success the pending entry is added
     * (which IS the slot acquisition) and true is returned; on a full slot nothing
     * changes and false is returned.
     */
    boolean tryAdmit(PendingRequest pending);
    PendingRequest removePendingByPosition(int cx, int cz);
    boolean hasPendingRequest(int cx, int cz);
}

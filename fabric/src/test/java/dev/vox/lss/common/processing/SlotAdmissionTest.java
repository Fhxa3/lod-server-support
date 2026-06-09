package dev.vox.lss.common.processing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the derived-slot admission model: a request occupies its slot exactly while its
 * pending entry exists, so admission counts can never drift from the pending map
 * (the structural guarantee that replaced the manually balanced ConcurrencyLimiter).
 */
class SlotAdmissionTest {

    /** Minimal concrete state — the queued payload type is irrelevant here. */
    private static final class TestState extends AbstractPlayerRequestState<TestState.NoPayload> {
        record NoPayload() implements Comparable<NoPayload> {
            @Override public int compareTo(NoPayload o) { return 0; }
        }
        TestState(int syncCap, int genCap) {
            super(UUID.randomUUID(), syncCap, genCap);
        }
    }

    private TestState state;

    @BeforeEach
    void setUp() {
        state = new TestState(2, 1);
    }

    private static PendingRequest sync(int cx, int cz) {
        return new PendingRequest(cx, cz, RequestType.SYNC, SlotType.SYNC_ON_LOAD);
    }

    private static PendingRequest gen(int cx, int cz) {
        return new PendingRequest(cx, cz, RequestType.GENERATION, SlotType.GENERATION);
    }

    @Test
    void admitsUpToCapThenBounces() {
        assertTrue(state.tryAdmit(sync(0, 0)));
        assertTrue(state.tryAdmit(sync(0, 1)));
        assertFalse(state.tryAdmit(sync(0, 2)), "third sync admission must bounce at cap 2");
        assertEquals(2, state.getHeldSyncSlots());
    }

    @Test
    void slotsAreIndependent() {
        assertTrue(state.tryAdmit(sync(0, 0)));
        assertTrue(state.tryAdmit(sync(0, 1)));
        assertTrue(state.tryAdmit(gen(1, 0)), "gen slot is independent of full sync slot");
        assertFalse(state.tryAdmit(gen(1, 1)));
        assertEquals(2, state.getHeldSyncSlots());
        assertEquals(1, state.getHeldGenSlots());
    }

    @Test
    void removeFreesTheSlot() {
        assertTrue(state.tryAdmit(sync(0, 0)));
        assertTrue(state.tryAdmit(sync(0, 1)));
        assertNotNull(state.removePendingByPosition(0, 0));
        assertEquals(1, state.getHeldSyncSlots());
        assertTrue(state.tryAdmit(sync(0, 2)), "freed slot is admittable again");
    }

    @Test
    void removingUntrackedPositionDoesNotUnderflow() {
        assertNull(state.removePendingByPosition(9, 9));
        assertEquals(0, state.getHeldSyncSlots());
        assertEquals(0, state.getHeldGenSlots());
        assertTrue(state.tryAdmit(sync(0, 0)));
        assertNull(state.removePendingByPosition(5, 5));
        assertEquals(1, state.getHeldSyncSlots(), "stray removal must not free a held slot");
    }

    @Test
    void samePositionReadmissionSwapsSlotInsteadOfLeaking() {
        // A GENERATION-type request routed disk-first holds a SYNC slot; when the disk read
        // comes back not-found, the pending entry is removed and a GENERATION-slot entry is
        // admitted for the same position. Simulate the same-position replace path directly.
        assertTrue(state.tryAdmit(sync(3, 3)));
        assertTrue(state.tryAdmit(gen(3, 3)), "same-position admit replaces the entry");
        assertEquals(0, state.getHeldSyncSlots(), "replaced entry's slot must be freed");
        assertEquals(1, state.getHeldGenSlots());
        assertNotNull(state.removePendingByPosition(3, 3));
        assertEquals(0, state.getHeldGenSlots());
    }

    @Test
    void hasPendingTracksAdmission() {
        assertFalse(state.hasPendingRequest(0, 0));
        assertTrue(state.tryAdmit(sync(0, 0)));
        assertTrue(state.hasPendingRequest(0, 0));
        state.removePendingByPosition(0, 0);
        assertFalse(state.hasPendingRequest(0, 0));
    }
}

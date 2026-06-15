package dev.vox.lss.common.processing;

/**
 * Which per-player admission slot an in-flight request occupies. Distinct from
 * {@link RequestType} because of disk-first routing: when disk reading is available,
 * GENERATION-type requests are first tried as disk reads and occupy a SYNC_ON_LOAD slot;
 * they only occupy a GENERATION slot once an actual generation is in flight.
 */
public enum SlotType {
    SYNC_ON_LOAD,
    GENERATION
}

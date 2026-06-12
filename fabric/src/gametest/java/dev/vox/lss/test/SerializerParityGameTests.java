package dev.vox.lss.test;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.networking.server.ChunkDiskReader;
import dev.vox.lss.networking.server.DirtyContentFilter;
import dev.vox.lss.networking.server.SectionSerializer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Content-level serializer truth on a real dedicated server:
 *
 * <ul>
 *   <li><b>Disk/live parity</b> — the wire bytes {@code NbtSectionSerializer} produces from a
 *       chunk's region NBT must byte-equal what {@code SectionSerializer} produces from the same
 *       chunk loaded back into memory. Divergence silently breaks the up-to-date economy (every
 *       disk-served column re-sends after its next save) and blocks seeding the
 *       {@code DirtyContentFilter} from disk serves.</li>
 *   <li><b>Dirty-content gating</b> — {@code DirtyContentFilter.contentChanged} on real chunks:
 *       first observation marks, identical re-saves stay quiet (the filter's entire reason to
 *       exist), edits re-mark, and the End-void ALL_AIR sentinel is stable and consistent with
 *       {@code seed}.</li>
 *   <li><b>All-air End disk read</b> — a FULL all-air End chunk on disk resolves as found
 *       (all-air, real timestamp), not "not found"; the pre-761b3fb regression triaged it as
 *       missing and caused endless re-generation storms in the End void.</li>
 * </ul>
 */
public class SerializerParityGameTests {

    /** Distinct far-away chunk offsets per test so concurrently running batch tests never share state. */
    private static final int PARITY_CHUNK_OFFSET = 64;
    private static final int DIRTY_FILTER_CHUNK_OFFSET = 96;
    // End void: between the main island (~chunk 22) and the outer islands (chunk 64+) the island
    // density function contributes nothing (max |chunk/2 + 12|^2 sum < 4096), so chunks there are
    // guaranteed all-air in every vanilla seed. The disk-read test may use a fixed position
    // because it never modifies blocks (the gametest world PERSISTS across runs — block-writing
    // tests must derive per-run positions instead, see the sentinel test).
    private static final int END_VOID_DISK_CX = 44;
    private static final int END_VOID_DISK_CZ = 8;

    /**
     * A column served from disk must be byte-identical to the same column served live after the
     * chunk loads back from that disk state. The chunk is generated, given a torch that is placed
     * and removed (leaving the light engine's allocated-but-all-zero BlockLight array in the
     * non-air section — the classic source of disk/live light asymmetry), then unloaded so the
     * save normalizes it; the comparison runs against the reloaded chunk because vanilla's save
     * path re-palettizes containers (first-appearance order), so a never-reloaded chunk differs
     * from its own save for reasons outside LSS's serializers.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void diskReadBytesMatchLiveBytesForDiskLoadedColumn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x() + PARITY_CHUNK_OFFSET;
        int cz = origin.z() + 7;
        var chunkPos = new ChunkPos(cx, cz);
        var chunkSource = level.getChunkSource();
        // Superflat surface: bedrock -64, dirt -63/-62, grass -61; first air block is -60.
        var torchPos = new BlockPos(cx * 16 + 8, -60, cz * 16 + 8);

        // Hold the chunk loaded for the torch dance (plain getChunk tickets expire after 1 tick).
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(cx, cz);
        level.setBlock(torchPos, Blocks.TORCH.defaultBlockState(), 3);
        helper.runAfterDelay(4, () -> level.setBlock(torchPos, Blocks.AIR.defaultBlockState(), 3));
        helper.runAfterDelay(8, () -> chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0));

        var reader = new ChunkDiskReader(1);
        var readerId = UUID.randomUUID();
        reader.registerPlayer(readerId);
        var step = new AtomicInteger();
        var diskBytes = new AtomicReference<byte[]>();

        // succeedWhen re-runs every tick until no assertion throws; assertTrue(false, ...) is the
        // "not yet, retry next tick" idiom and its message names the stuck phase on timeout.
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() >= 10, "waiting for the torch dance to finish");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(chunkSource.getChunkNow(cx, cz) == null,
                            "waiting for the chunk to unload");
                    // The unload save may still sit in the unload queue; saveAllChunks drains it
                    // and flushes storage so the region state is final before the read.
                    level.save(null, true, false);
                    reader.submitReadDirect(readerId, LSSConstants.DIM_STR_OVERWORLD, level, cx, cz, 0);
                    step.set(1);
                    helper.assertTrue(false, "disk read submitted, awaiting result");
                }
                case 1 -> {
                    var result = reader.getPlayerQueue(readerId).poll();
                    helper.assertTrue(result != null, "waiting for the disk read result");
                    reader.shutdown();
                    helper.assertTrue(!result.notFound(), "generated chunk must exist on disk after unload");
                    helper.assertTrue(!result.saturated(), "single read on a fresh reader must not saturate");
                    helper.assertTrue(result.sectionBytes() != null,
                            "superflat chunk must have non-air content on disk");
                    diskBytes.set(result.sectionBytes());
                    step.set(2);
                    helper.assertTrue(false, "disk bytes captured, reloading chunk");
                }
                // Re-evaluated until equal or timeout, so one tick of post-load light settling
                // only delays success instead of failing the test.
                case 2 -> {
                    var chunk = level.getChunk(cx, cz);
                    var live = SectionSerializer.serializeColumn(level, chunk, cx, cz).serializedSections();
                    helper.assertTrue(live != null, "reloaded superflat chunk must serialize live content");
                    helper.assertTrue(Arrays.equals(diskBytes.get(), live),
                            describeMismatch(diskBytes.get(), live));
                }
                default -> helper.fail("unexpected parity test step " + step.get());
            }
        });
    }

    /**
     * {@code DirtyContentFilter.contentChanged} ladder on a real superflat chunk:
     * first observation marks dirty, identical content stays quiet within a tick and across
     * ticks (pins SectionSerializer's call-to-call determinism — the suppress direction that
     * makes the filter worth having), a real block edit re-marks, and the post-edit baseline
     * suppresses again.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void dirtyContentFilterSuppressesIdenticalResavesAndCatchesEdits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x() + DIRTY_FILTER_CHUNK_OFFSET;
        int cz = origin.z() + 13;
        var chunkPos = new ChunkPos(cx, cz);
        var chunkSource = level.getChunkSource();
        var dim = LSSConstants.DIM_STR_OVERWORLD;
        var filter = new DirtyContentFilter();
        // Grass surface block of the default superflat preset.
        var editPos = new BlockPos(cx * 16 + 4, -61, cz * 16 + 4);

        // Keep the chunk loaded across the ladder so every step hashes the same live chunk
        // (a getChunk ticket lasts 1 tick; an unload+reload between steps would re-palettize
        // the sections and fake a content change).
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(cx, cz);

        // Tick 2: generation-time light has settled; take the baseline and pin same-tick quiet.
        helper.runAfterDelay(2, () -> {
            var chunk = level.getChunk(cx, cz);
            helper.assertTrue(filter.contentChanged(level, chunk, dim),
                    "first observation of a column is always a change");
            helper.assertTrue(!filter.contentChanged(level, chunk, dim),
                    "identical re-save in the same tick must stay quiet");
            helper.assertTrue(!filter.contentChanged(level, chunk, dim),
                    "suppression must hold across repeated identical saves");
        });

        // Tick 4: cross-tick quiet on untouched content, then edit the surface.
        helper.runAfterDelay(4, () -> {
            var chunk = level.getChunk(cx, cz);
            helper.assertTrue(!filter.contentChanged(level, chunk, dim),
                    "identical content two ticks later must still stay quiet (cross-tick determinism)");
            level.setBlock(editPos, Blocks.STONE.defaultBlockState(), 3);
        });

        // Tick 6: the edit must mark dirty exactly once, then suppression resumes.
        helper.runAfterDelay(6, () -> {
            var chunk = level.getChunk(cx, cz);
            helper.assertTrue(filter.contentChanged(level, chunk, dim),
                    "a real block edit must re-mark the column dirty");
            helper.assertTrue(!filter.contentChanged(level, chunk, dim),
                    "the save after the edit is absorbed must stay quiet again");
            chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
            helper.succeed();
        });
    }

    /**
     * End-void ALL_AIR sentinel: all-air columns serialize to null bytes, which the filter must
     * hash as a stable sentinel — air-to-air saves stay quiet, an all-air serve seed agrees with
     * the next all-air save, and an air-to-built transition still marks dirty.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void dirtyContentFilterAllAirSentinelInEndVoid(GameTestHelper helper) {
        ServerLevel endLevel = helper.getLevel().getServer().getLevel(Level.END);
        helper.assertTrue(endLevel != null, "the End dimension must exist on the gametest server");
        var dim = LSSConstants.DIM_STR_THE_END;

        // This test builds a block, and the gametest world persists across runs — so derive the
        // chunk from the per-run random batch position (cx 28..43, cz -16..-9: inside the void
        // guarantee band, disjoint from the disk-read test's chunk) and scan down-z past any
        // column a previous run already built in.
        var origin = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        int salt = Math.floorMod(origin.x() * 31 + origin.z(), 256);
        int cx = 28 + (salt & 15);
        int baseCz = -16 + ((salt >> 4) & 7);
        int cz = baseCz;
        var chunk = endLevel.getChunk(cx, cz);
        for (int remaining = 8; remaining > 0
                && SectionSerializer.serializeColumn(endLevel, chunk, cx, cz).serializedSections() != null;
                remaining--) {
            cz--;
            chunk = endLevel.getChunk(cx, cz);
        }
        var live = SectionSerializer.serializeColumn(endLevel, chunk, cx, cz);
        helper.assertTrue(live.serializedSections() == null,
                "premise: no all-air End void column found near chunk (" + cx + ", " + baseCz + ")");

        var filter = new DirtyContentFilter();
        helper.assertTrue(filter.contentChanged(endLevel, chunk, dim),
                "first observation of the all-air column is a change");
        helper.assertTrue(!filter.contentChanged(endLevel, chunk, dim),
                "air-to-air save must stay quiet (ALL_AIR sentinel is stable)");

        // A serve of this all-air column seeds null bytes; the next save of the same all-air
        // chunk must hash to the same sentinel, or every void column re-marks after every save.
        var seededFilter = new DirtyContentFilter();
        seededFilter.seed(dim, cx, cz, live.serializedSections());
        helper.assertTrue(!seededFilter.contentChanged(endLevel, chunk, dim),
                "all-air save after an all-air serve seed must stay quiet");

        endLevel.setBlock(new BlockPos(cx * 16 + 8, 80, cz * 16 + 8), Blocks.END_STONE.defaultBlockState(), 3);
        helper.assertTrue(filter.contentChanged(endLevel, chunk, dim),
                "air-to-built transition must mark dirty (the sentinel must not swallow it)");
        helper.succeed();
    }

    /**
     * The 761b3fb End-void chain at disk-reader level: an all-air FULL End chunk on disk resolves
     * as found (all-air triage, null section bytes, real timestamp for the up-to-date economy),
     * never as "not found" — which would re-trigger generation forever.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void allAirEndChunkDiskReadResolvesFoundNotMissing(GameTestHelper helper) {
        ServerLevel endLevel = helper.getLevel().getServer().getLevel(Level.END);
        helper.assertTrue(endLevel != null, "the End dimension must exist on the gametest server");
        int cx = END_VOID_DISK_CX;
        int cz = END_VOID_DISK_CZ;

        var chunk = endLevel.getChunk(cx, cz);
        helper.assertTrue(
                SectionSerializer.serializeColumn(endLevel, chunk, cx, cz).serializedSections() == null,
                "premise: the End void chunk serializes as all-air");
        // Flush the freshly generated chunk to its region file so the read below hits real disk state.
        endLevel.save(null, true, false);

        var reader = new ChunkDiskReader(1);
        var readerId = UUID.randomUUID();
        reader.registerPlayer(readerId);
        reader.submitReadDirect(readerId, LSSConstants.DIM_STR_THE_END, endLevel, cx, cz, 0);

        var result = new AtomicReference<dev.vox.lss.common.processing.ChunkReadResult>();
        helper.succeedWhen(() -> {
            if (result.get() == null) {
                var polled = reader.getPlayerQueue(readerId).poll();
                helper.assertTrue(polled != null, "waiting for the async disk read to complete");
                result.set(polled);
                reader.shutdown();
            }
            var r = result.get();
            helper.assertTrue(!r.notFound(),
                    "all-air FULL chunk on disk must resolve as found, not not-found (not-found re-triggers generation forever)");
            helper.assertTrue(!r.saturated(), "single read on a fresh reader must not saturate");
            helper.assertTrue(r.sectionBytes() == null, "all-air result carries null section bytes (nothing to send)");
            helper.assertTrue(r.columnTimestamp() > 0,
                    "all-air result must carry a real timestamp so the client can mark the column up-to-date");
            helper.assertTrue(reader.getDiag().getAllAirCount() == 1, "diagnostics must triage the read as all-air");
            helper.assertTrue(reader.getDiag().getNotFoundCount() == 0, "diagnostics must not count the read as not-found");
        });
    }

    private static String describeMismatch(byte[] disk, byte[] live) {
        if (disk == null || live == null) {
            return "disk/live serialization mismatch: disk=" + (disk == null ? "null" : disk.length + " bytes")
                    + ", live=" + (live == null ? "null" : live.length + " bytes");
        }
        int at = Arrays.mismatch(disk, live);
        return "disk-read wire bytes diverge from live-serialized bytes (serializer asymmetry breaks the "
                + "up-to-date economy): lengths disk=" + disk.length + " live=" + live.length
                + ", first mismatch at byte " + at
                + ", disk[..]=" + hexWindow(disk, at) + ", live[..]=" + hexWindow(live, at);
    }

    private static String hexWindow(byte[] bytes, int around) {
        int from = Math.max(0, around - 4);
        int to = Math.min(bytes.length, around + 8);
        var sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append(' ');
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }
}

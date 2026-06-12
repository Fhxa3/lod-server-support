package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire-format edges beyond the happy-path suites: future-version SessionConfig frames
 * (the failure mode at every release boundary), oversized backing buffers (the production
 * shape SendActionBatcher hands the codec), exact cap boundaries, max-length dimension
 * strings, the dirty-columns encode clamp, and the 2MB section-bytes guard that protects
 * the client from hostile multi-GB allocations.
 */
class WireEdgeCaseTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    /** Create a minimal raw empty column (0 sections). */
    private byte[] emptyColumn() {
        var wireBuf = new FriendlyByteBuf(Unpooled.buffer());
        wireBuf.writeVarInt(0);
        byte[] raw = new byte[wireBuf.readableBytes()];
        wireBuf.readBytes(raw);
        wireBuf.release();
        return raw;
    }

    // ---- SessionConfigS2CPayload: future version ----

    @Test
    void sessionConfigDrainsFutureVersionFrameWithTailBytes() {
        // A hypothetical v(N+1) server frame: same leading version VarInt, then unknown
        // fields the current decoder has never seen. It must surface the version (for the
        // client's protocol gate) and consume every byte — trailing bytes kick the client.
        var b = buf();
        b.writeVarInt(LSSConstants.PROTOCOL_VERSION + 1);
        b.writeBoolean(true);
        b.writeVarInt(128);
        b.writeLong(0xCAFEBABEL);     // unknown future field
        b.writeUtf("future-field");   // unknown future field
        try {
            var decoded = SessionConfigS2CPayload.CODEC.decode(b);
            assertEquals(LSSConstants.PROTOCOL_VERSION + 1, decoded.protocolVersion());
            assertFalse(decoded.enabled(), "a foreign-version config must decode disabled");
            assertEquals(0, b.readableBytes(),
                    "future frame must be fully drained (trailing bytes disconnect the client)");
        } finally {
            b.release();
        }
    }

    // ---- Oversized backing buffers with small count (production shape) ----

    @Test
    void batchResponseEncodesOnlyCountFromOversizedBackingArrays() {
        // SendActionBatcher hands its grown backing arrays to the codec with a smaller count;
        // anything past count is stale garbage that must never reach the wire.
        byte[] types = new byte[1024];
        long[] positions = new long[1024];
        for (int i = 3; i < 1024; i++) {
            types[i] = (byte) 99;
            positions[i] = PositionUtil.packPosition(-999, -999);
        }
        types[0] = LSSConstants.RESPONSE_RATE_LIMITED;
        types[1] = LSSConstants.RESPONSE_UP_TO_DATE;
        types[2] = LSSConstants.RESPONSE_NOT_GENERATED;
        positions[0] = PositionUtil.packPosition(1, -1);
        positions[1] = PositionUtil.packPosition(2, -2);
        positions[2] = PositionUtil.packPosition(3, -3);

        var b = buf();
        try {
            BatchResponseS2CPayload.CODEC.encode(b, new BatchResponseS2CPayload(types, positions, 3));
            var decoded = BatchResponseS2CPayload.CODEC.decode(b);
            assertEquals(3, decoded.count());
            assertEquals(3, decoded.responseTypes().length, "decoded arrays are exactly count-sized");
            for (int i = 0; i < 3; i++) {
                assertEquals(types[i], decoded.responseTypes()[i]);
                assertEquals(positions[i], decoded.packedPositions()[i]);
            }
            assertEquals(0, b.readableBytes(), "no garbage tail from the backing buffer");
        } finally {
            b.release();
        }
    }

    @Test
    void batchChunkRequestEncodesOnlyCountFromOversizedBackingArrays() {
        long[] positions = new long[1024];
        long[] timestamps = new long[1024];
        java.util.Arrays.fill(positions, PositionUtil.packPosition(-999, -999));
        java.util.Arrays.fill(timestamps, Long.MIN_VALUE);
        positions[0] = PositionUtil.packPosition(10, 20);
        positions[1] = PositionUtil.packPosition(-5, 5);
        timestamps[0] = -1L;
        timestamps[1] = 1700000000L;

        var b = buf();
        try {
            BatchChunkRequestC2SPayload.CODEC.encode(b,
                    new BatchChunkRequestC2SPayload(positions, timestamps, 2));
            var decoded = BatchChunkRequestC2SPayload.CODEC.decode(b);
            assertEquals(2, decoded.count());
            assertEquals(2, decoded.packedPositions().length);
            assertEquals(positions[0], decoded.packedPositions()[0]);
            assertEquals(positions[1], decoded.packedPositions()[1]);
            assertEquals(-1L, decoded.clientTimestamps()[0]);
            assertEquals(1700000000L, decoded.clientTimestamps()[1]);
            assertEquals(0, b.readableBytes(), "no garbage tail from the backing buffer");
        } finally {
            b.release();
        }
    }

    // ---- DirtyColumnsS2CPayload: cap boundary + encode clamp ----

    @Test
    void dirtyColumnsExactMaxRoundTripsIntact() {
        long[] positions = new long[LSSConstants.MAX_DIRTY_COLUMN_POSITIONS];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = PositionUtil.packPosition(i, -i);
        }
        var b = buf();
        try {
            DirtyColumnsS2CPayload.CODEC.encode(b, new DirtyColumnsS2CPayload(positions));
            var decoded = DirtyColumnsS2CPayload.CODEC.decode(b);
            assertArrayEquals(positions, decoded.dirtyPositions(),
                    "a batch of exactly MAX positions must survive untouched");
            assertEquals(0, b.readableBytes());
        } finally {
            b.release();
        }
    }

    @Test
    void dirtyColumnsEncoderClampsOversizedArrayToMax() {
        // Pin the encode clamp on the wire bytes themselves: relying on decode would let an
        // unclamped encoder hide behind the decoder's truncation.
        long[] positions = new long[LSSConstants.MAX_DIRTY_COLUMN_POSITIONS + 5];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = PositionUtil.packPosition(i, i + 1);
        }
        var b = buf();
        try {
            DirtyColumnsS2CPayload.CODEC.encode(b, new DirtyColumnsS2CPayload(positions));
            assertEquals(LSSConstants.MAX_DIRTY_COLUMN_POSITIONS, b.readVarInt(),
                    "encoder must clamp the count VarInt to the decoder cap");
            assertEquals(LSSConstants.MAX_DIRTY_COLUMN_POSITIONS * Long.BYTES, b.readableBytes(),
                    "encoder must clamp the position payload");
            assertEquals(positions[0], b.readLong(), "clamped frame keeps the leading positions");
        } finally {
            b.release();
        }
    }

    // ---- VoxelColumnS2CPayload: section-bytes guard + dimension string cap ----

    @Test
    void voxelColumnDecoderRejectsOversizedSectionBytes() {
        // Hostile frame with the oversized body actually present: an uncapped readByteArray
        // would happily allocate and succeed, so this pins the explicit 2MB cap.
        var b = buf();
        b.writeInt(0);
        b.writeInt(0);
        b.writeUtf(LSSConstants.DIM_STR_OVERWORLD, LSSConstants.MAX_DIMENSION_STRING_LENGTH);
        b.writeLong(0L);
        b.writeVarInt(LSSConstants.MAX_SECTIONS_SIZE + 1);
        b.writeBytes(new byte[LSSConstants.MAX_SECTIONS_SIZE + 1]);
        try {
            assertThrows(DecoderException.class, () -> VoxelColumnS2CPayload.CODEC.decode(b),
                    "section bytes over the 2MB cap must be rejected, not allocated");
        } finally {
            b.release();
        }
    }

    @Test
    void voxelColumnSectionBytesAtExactCapRoundTrip() {
        byte[] sections = new byte[LSSConstants.MAX_SECTIONS_SIZE];
        sections[0] = 42;
        sections[sections.length - 1] = 7;
        var original = new VoxelColumnS2CPayload(3, -4, Level.OVERWORLD, 123L, sections);
        var b = buf();
        try {
            VoxelColumnS2CPayload.CODEC.encode(b, original);
            var decoded = VoxelColumnS2CPayload.CODEC.decode(b);
            assertEquals(LSSConstants.MAX_SECTIONS_SIZE, decoded.decompressedSections().length,
                    "a legitimate column at exactly the cap must survive (guard is >, not >=)");
            assertEquals(42, decoded.decompressedSections()[0]);
            assertEquals(7, decoded.decompressedSections()[sections.length - 1]);
            assertEquals(0, b.readableBytes());
        } finally {
            b.release();
        }
    }

    @Test
    void voxelColumnDimensionStringAtMaxLengthRoundTrips() {
        String dimStr = "lss:" + "a".repeat(LSSConstants.MAX_DIMENSION_STRING_LENGTH - 4);
        assertEquals(LSSConstants.MAX_DIMENSION_STRING_LENGTH, dimStr.length());
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimStr));
        var original = new VoxelColumnS2CPayload(1, 2, dim, 9L, emptyColumn());
        var b = buf();
        try {
            VoxelColumnS2CPayload.CODEC.encode(b, original);
            var decoded = VoxelColumnS2CPayload.CODEC.decode(b);
            assertEquals(dim, decoded.dimension(),
                    "a datapack dimension id at exactly 256 chars must round-trip");
            assertEquals(0, b.readableBytes());
        } finally {
            b.release();
        }
    }

    @Test
    void voxelColumnDimensionStringOverMaxLengthFailsOnEncode() {
        // The writer cap must hold: an uncapped writeUtf would instead emit a frame the
        // reader's cap rejects, disconnecting every client receiving that dimension.
        String dimStr = "lss:" + "a".repeat(LSSConstants.MAX_DIMENSION_STRING_LENGTH - 3);
        assertEquals(LSSConstants.MAX_DIMENSION_STRING_LENGTH + 1, dimStr.length());
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimStr));
        var payload = new VoxelColumnS2CPayload(0, 0, dim, 0L, emptyColumn());
        var b = buf();
        try {
            assertThrows(EncoderException.class,
                    () -> VoxelColumnS2CPayload.CODEC.encode(b, payload));
        } finally {
            b.release();
        }
    }
}

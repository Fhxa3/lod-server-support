package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.QueuedPayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Hostile/edge-frame hardening for the Paper plugin-message codec. {@code WireParityTest}
 * pins the happy-path bytes; this suite pins the defensive contracts: count guards run
 * BEFORE array allocation (allocation DoS), legacy handshakes without a capabilities
 * VarInt still decode, truncated frames fail via a catchable Exception (the plugin's
 * onPluginMessageReceived catches Exception, not Error), trailing bytes are tolerated
 * (forward compat), the dirty-columns encoder clamps to the wire limit, and oversized
 * columns are dropped by {@link PaperOffThreadProcessor} because the client decoder's
 * {@code readByteArray(MAX_SECTIONS_SIZE)} guard would reject (and disconnect on) them.
 */
class PaperPayloadEdgeTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static byte[] frame(Consumer<FriendlyByteBuf> ops) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        ops.accept(b);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
    }

    // ---- C2S: batch chunk request decode guards ----

    @Test
    void batchRequestCountGuardRejectsBeforeAllocation() {
        // Integer.MAX_VALUE would be a ~16 GB allocation if the guard ran after `new long[count]`
        assertNull(PaperPayloadHandler.decodeBatchChunkRequest(
                frame(b -> b.writeVarInt(Integer.MAX_VALUE))));
        assertNull(PaperPayloadHandler.decodeBatchChunkRequest(
                frame(b -> b.writeVarInt(LSSConstants.MAX_BATCH_CHUNK_REQUESTS + 1))));
    }

    @Test
    void batchRequestNegativeCountRejected() {
        assertNull(PaperPayloadHandler.decodeBatchChunkRequest(frame(b -> b.writeVarInt(-1))));
    }

    @Test
    void batchRequestAtMaxCountAccepted() {
        int max = LSSConstants.MAX_BATCH_CHUNK_REQUESTS;
        byte[] data = frame(b -> {
            b.writeVarInt(max);
            for (int i = 0; i < max; i++) {
                b.writeLong(PositionUtil.packPosition(i, -i));
                b.writeLong(i);
            }
        });
        var decoded = PaperPayloadHandler.decodeBatchChunkRequest(data);
        assertNotNull(decoded);
        assertEquals(max, decoded.count());
        assertEquals(PositionUtil.packPosition(0, 0), decoded.packedPositions()[0]);
        assertEquals(PositionUtil.packPosition(max - 1, -(max - 1)), decoded.packedPositions()[max - 1]);
        assertEquals(max - 1, decoded.clientTimestamps()[max - 1]);
    }

    @Test
    void batchRequestNullOrEmptyPayloadRejected() {
        assertNull(PaperPayloadHandler.decodeBatchChunkRequest(null));
        assertNull(PaperPayloadHandler.decodeBatchChunkRequest(new byte[0]));
    }

    @Test
    void batchRequestTruncatedFrameThrowsCatchableException() {
        // Declares 2 entries but carries only one; must not produce a partial decode, and the
        // failure must be an Exception (LSSPaperPlugin.onPluginMessageReceived catches Exception)
        byte[] truncated = frame(b -> {
            b.writeVarInt(2);
            b.writeLong(PositionUtil.packPosition(1, 1));
            b.writeLong(-1L);
        });
        assertThrows(Exception.class, () -> PaperPayloadHandler.decodeBatchChunkRequest(truncated));
    }

    @Test
    void batchRequestTrailingBytesIgnored() {
        byte[] data = frame(b -> {
            b.writeVarInt(1);
            b.writeLong(PositionUtil.packPosition(-3, 9));
            b.writeLong(123L);
            b.writeBytes(new byte[]{0x7F, 0x00, 0x55}); // future protocol extension bytes
        });
        var decoded = PaperPayloadHandler.decodeBatchChunkRequest(data);
        assertNotNull(decoded);
        assertEquals(1, decoded.count());
        assertEquals(PositionUtil.packPosition(-3, 9), decoded.packedPositions()[0]);
        assertEquals(123L, decoded.clientTimestamps()[0]);
    }

    // ---- C2S: handshake decode guards ----

    @Test
    void handshakeLegacyFrameWithoutCapabilitiesDefaultsToZero() {
        var decoded = PaperPayloadHandler.decodeHandshake(frame(b -> b.writeVarInt(9)));
        assertNotNull(decoded);
        assertEquals(9, decoded.protocolVersion());
        assertEquals(0, decoded.capabilities());
    }

    @Test
    void handshakeNullOrEmptyPayloadRejected() {
        assertNull(PaperPayloadHandler.decodeHandshake(null));
        assertNull(PaperPayloadHandler.decodeHandshake(new byte[0]));
    }

    @Test
    void handshakeTrailingBytesIgnored() {
        byte[] data = frame(b -> {
            b.writeVarInt(LSSConstants.PROTOCOL_VERSION);
            b.writeVarInt(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            b.writeBytes(new byte[]{0x01, 0x02});
        });
        var decoded = PaperPayloadHandler.decodeHandshake(data);
        assertNotNull(decoded);
        assertEquals(LSSConstants.PROTOCOL_VERSION, decoded.protocolVersion());
        assertEquals(LSSConstants.CAPABILITY_VOXEL_COLUMNS, decoded.capabilities());
    }

    // ---- S2C: dirty-columns encoder clamp ----

    @Test
    void dirtyColumnsEncoderClampsToWireLimit() {
        int max = LSSConstants.MAX_DIRTY_COLUMN_POSITIONS;
        long[] positions = new long[max + 5];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = PositionUtil.packPosition(i, i + 1);
        }
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(PaperPayloadHandler.encodeDirtyColumns(positions)));
        try {
            assertEquals(max, buf.readVarInt(), "count clamped to MAX_DIRTY_COLUMN_POSITIONS");
            for (int i = 0; i < max; i++) {
                assertEquals(positions[i], buf.readLong());
            }
            assertEquals(0, buf.readableBytes(), "no positions written beyond the clamp");
        } finally {
            buf.release();
        }
    }

    @Test
    void dirtyColumnsEncoderAtLimitKeepsEveryPosition() {
        int max = LSSConstants.MAX_DIRTY_COLUMN_POSITIONS;
        long[] positions = new long[max];
        for (int i = 0; i < max; i++) {
            positions[i] = PositionUtil.packPosition(-i, i);
        }
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(PaperPayloadHandler.encodeDirtyColumns(positions)));
        try {
            assertEquals(max, buf.readVarInt());
            assertEquals(positions[0], buf.readLong());
            buf.skipBytes((max - 2) * Long.BYTES);
            assertEquals(positions[max - 1], buf.readLong(), "boundary frame is not truncated");
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    // ---- S2C: voxel column MAX_SECTIONS_SIZE boundary ----

    /** Decode a voxel-column frame with the exact grammar + guards of the Fabric client decoder. */
    private record ClientDecoded(int cx, int cz, String dimension, long timestamp, byte[] sections) {}

    private static ClientDecoded clientDecode(byte[] data) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            var decoded = new ClientDecoded(
                    buf.readInt(), buf.readInt(),
                    buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH),
                    buf.readLong(),
                    buf.readByteArray(LSSConstants.MAX_SECTIONS_SIZE));
            assertEquals(0, buf.readableBytes(), "frame fully drained");
            return decoded;
        } finally {
            buf.release();
        }
    }

    @Test
    void voxelColumnAtSectionsLimitPassesClientGuard() {
        byte[] sections = new byte[LSSConstants.MAX_SECTIONS_SIZE];
        sections[0] = 0x42;
        sections[sections.length - 1] = 0x24;
        byte[] data = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                7, -8, "minecraft:the_end", 1234L, sections);
        var decoded = clientDecode(data);
        assertEquals(7, decoded.cx());
        assertEquals(-8, decoded.cz());
        assertEquals("minecraft:the_end", decoded.dimension());
        assertEquals(1234L, decoded.timestamp());
        assertEquals(LSSConstants.MAX_SECTIONS_SIZE, decoded.sections().length);
        assertEquals(0x42, decoded.sections()[0]);
        assertEquals(0x24, decoded.sections()[decoded.sections().length - 1]);
    }

    @Test
    void voxelColumnOverSectionsLimitRejectedByClientGuard() {
        // One byte over the limit: the client-side readByteArray guard throws, which on a live
        // connection disconnects the player — this is why the server must drop instead of send.
        byte[] data = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                0, 0, "minecraft:overworld", 1L, new byte[LSSConstants.MAX_SECTIONS_SIZE + 1]);
        assertThrows(DecoderException.class, () -> clientDecode(data));
    }

    // ---- PaperOffThreadProcessor: oversized-column drop (Paper mirror of the client guard) ----

    private static PaperOffThreadProcessor newProcessor() {
        // Thread is created but never start()ed; null diskReader/dataDir follow the
        // OffThreadProcessorMailboxTest harness pattern.
        return new PaperOffThreadProcessor(new ConcurrentHashMap<>(), null, false, null, 1);
    }

    @Test
    void processorDropsOversizedColumnInsteadOfEncoding() {
        var proc = newProcessor();
        var state = mock(PaperPlayerRequestState.class);
        proc.buildAndEnqueueColumnPayload(state, 1, 2, "minecraft:overworld", 42L, 7L,
                new byte[LSSConstants.MAX_SECTIONS_SIZE + 1], 99);
        verify(state, never()).addReadyPayload(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void processorEncodesAndEnqueuesColumnAtSectionsLimit() {
        var proc = newProcessor();
        var state = mock(PaperPlayerRequestState.class);
        byte[] sections = new byte[LSSConstants.MAX_SECTIONS_SIZE];
        sections[123] = 0x5A;
        proc.buildAndEnqueueColumnPayload(state, 1, 2, "minecraft:overworld", 42L, 7L, sections, 99);

        var captor = ArgumentCaptor.forClass((Class<QueuedPayload<byte[]>>) (Class<?>) QueuedPayload.class);
        verify(state).addReadyPayload(captor.capture());
        var queued = captor.getValue();
        assertArrayEquals(PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                1, 2, "minecraft:overworld", 42L, sections), queued.payload());
        assertEquals(99, queued.estimatedBytes());
        assertEquals(7L, queued.submissionOrder());
    }
}

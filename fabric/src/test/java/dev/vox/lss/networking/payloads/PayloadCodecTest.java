package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PayloadCodecTest {

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

    // --- HandshakeC2SPayload ---

    @Test
    void handshakeRoundtrip() {
        var original = new HandshakeC2SPayload(4, 1);
        var b = buf();
        HandshakeC2SPayload.CODEC.encode(b, original);
        var decoded = HandshakeC2SPayload.CODEC.decode(b);
        assertEquals(original.protocolVersion(), decoded.protocolVersion());
        assertEquals(original.capabilities(), decoded.capabilities());
        b.release();
    }

    // --- SessionConfigS2CPayload ---

    @Test
    void sessionConfigRoundtrip() {
        var original = new SessionConfigS2CPayload(9, true, 128, 100, 40, true);
        var b = buf();
        SessionConfigS2CPayload.CODEC.encode(b, original);
        var decoded = SessionConfigS2CPayload.CODEC.decode(b);
        assertEquals(original.protocolVersion(), decoded.protocolVersion());
        assertEquals(original.enabled(), decoded.enabled());
        assertEquals(original.lodDistanceChunks(), decoded.lodDistanceChunks());
        assertEquals(original.syncOnLoadConcurrencyLimitPerPlayer(), decoded.syncOnLoadConcurrencyLimitPerPlayer());
        assertEquals(original.generationConcurrencyLimitPerPlayer(), decoded.generationConcurrencyLimitPerPlayer());
        assertEquals(original.generationEnabled(), decoded.generationEnabled());
        b.release();
    }

    // --- BatchResponseS2CPayload ---

    @Test
    void batchResponseRoundtrip() {
        byte[] types = {
                LSSConstants.RESPONSE_RATE_LIMITED,
                LSSConstants.RESPONSE_UP_TO_DATE,
                LSSConstants.RESPONSE_NOT_GENERATED
        };
        int[] requestIds = {42, 99, 77};
        var original = new BatchResponseS2CPayload(types, requestIds, 3);
        var b = buf();
        BatchResponseS2CPayload.CODEC.encode(b, original);
        var decoded = BatchResponseS2CPayload.CODEC.decode(b);
        assertEquals(3, decoded.count());
        for (int i = 0; i < 3; i++) {
            assertEquals(types[i], decoded.responseTypes()[i]);
            assertEquals(requestIds[i], decoded.requestIds()[i]);
        }
        assertEquals(0, b.readableBytes());
        b.release();
    }

    // --- DirtyColumnsS2CPayload ---

    @Test
    void dirtyColumnsRoundtrip() {
        long[] positions = {
                PositionUtil.packPosition(10, 20),
                PositionUtil.packPosition(-5, 100)
        };
        var original = new DirtyColumnsS2CPayload(positions);
        var b = buf();
        DirtyColumnsS2CPayload.CODEC.encode(b, original);
        var decoded = DirtyColumnsS2CPayload.CODEC.decode(b);
        assertArrayEquals(original.dirtyPositions(), decoded.dirtyPositions());
        b.release();
    }

    // --- BatchChunkRequestC2SPayload ---

    @Test
    void batchChunkRequestRoundtrip() {
        int[] requestIds = {7, 42, 99};
        long[] positions = {
                PositionUtil.packPosition(10, 20),
                PositionUtil.packPosition(-5, 100),
                PositionUtil.packPosition(7, -3)
        };
        long[] timestamps = {1000L, 0L, -1L};
        var original = new BatchChunkRequestC2SPayload(requestIds, positions, timestamps, 3);
        var b = buf();
        BatchChunkRequestC2SPayload.CODEC.encode(b, original);
        var decoded = BatchChunkRequestC2SPayload.CODEC.decode(b);
        assertEquals(3, decoded.count());
        assertArrayEquals(requestIds, decoded.requestIds());
        assertArrayEquals(positions, decoded.packedPositions());
        assertArrayEquals(timestamps, decoded.clientTimestamps());
        b.release();
    }

    // --- VoxelColumnS2CPayload dimension encoding ---

    @Test
    void voxelColumnOverworldDimensionRoundtrip() {
        byte[] sections = emptyColumn();
        var original = new VoxelColumnS2CPayload(1, 10, 20, Level.OVERWORLD, 12345L, sections);
        var b = buf();
        VoxelColumnS2CPayload.CODEC.encode(b, original);
        var decoded = VoxelColumnS2CPayload.CODEC.decode(b);
        assertEquals(Level.OVERWORLD, decoded.dimension());
        assertEquals(original.chunkX(), decoded.chunkX());
        assertEquals(original.chunkZ(), decoded.chunkZ());
        assertEquals(original.columnTimestamp(), decoded.columnTimestamp());
        assertEquals(0, b.readableBytes());
        b.release();
    }

    @Test
    void voxelColumnNetherDimensionRoundtrip() {
        byte[] sections = emptyColumn();
        var original = new VoxelColumnS2CPayload(2, 5, -5, Level.NETHER, 99L, sections);
        var b = buf();
        VoxelColumnS2CPayload.CODEC.encode(b, original);
        var decoded = VoxelColumnS2CPayload.CODEC.decode(b);
        assertEquals(Level.NETHER, decoded.dimension());
        b.release();
    }

    @Test
    void voxelColumnEndDimensionRoundtrip() {
        byte[] sections = emptyColumn();
        var original = new VoxelColumnS2CPayload(3, 0, 0, Level.END, 0L, sections);
        var b = buf();
        VoxelColumnS2CPayload.CODEC.encode(b, original);
        var decoded = VoxelColumnS2CPayload.CODEC.decode(b);
        assertEquals(Level.END, decoded.dimension());
        b.release();
    }

}

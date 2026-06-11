package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the count guards on the v16 batch codecs. Both decoders allocate arrays sized by a
 * remote-controlled VarInt; the range check is the only defense against a hostile frame
 * forcing a multi-GB allocation (hostile client vs the server for requests, hostile server
 * vs the client for responses). Dropping a guard in a future protocol edit must fail here.
 */
class BatchCountGuardTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @Test
    void batchRequestDecoderRejectsOversizedCount() {
        var b = buf();
        b.writeVarInt(LSSConstants.MAX_BATCH_CHUNK_REQUESTS + 1);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> BatchChunkRequestC2SPayload.CODEC.decode(b));
        } finally {
            b.release();
        }
    }

    @Test
    void batchRequestDecoderRejectsNegativeCount() {
        var b = buf();
        b.writeVarInt(-1);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> BatchChunkRequestC2SPayload.CODEC.decode(b));
        } finally {
            b.release();
        }
    }

    @Test
    void batchRequestDecoderAcceptsMaxCount() {
        var b = buf();
        b.writeVarInt(LSSConstants.MAX_BATCH_CHUNK_REQUESTS);
        for (int i = 0; i < LSSConstants.MAX_BATCH_CHUNK_REQUESTS; i++) {
            b.writeLong(i); // packed position
            b.writeLong(-1L); // client timestamp
        }
        try {
            var decoded = BatchChunkRequestC2SPayload.CODEC.decode(b);
            assertEquals(LSSConstants.MAX_BATCH_CHUNK_REQUESTS, decoded.count());
        } finally {
            b.release();
        }
    }

    @Test
    void batchResponseDecoderRejectsOversizedCount() {
        var b = buf();
        b.writeVarInt(LSSConstants.MAX_BATCH_RESPONSES + 1);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> BatchResponseS2CPayload.CODEC.decode(b));
        } finally {
            b.release();
        }
    }

    @Test
    void batchResponseDecoderRejectsNegativeCount() {
        var b = buf();
        b.writeVarInt(-1);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> BatchResponseS2CPayload.CODEC.decode(b));
        } finally {
            b.release();
        }
    }
}

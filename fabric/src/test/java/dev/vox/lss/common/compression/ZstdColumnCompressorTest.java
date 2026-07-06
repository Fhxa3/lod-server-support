package dev.vox.lss.common.compression;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip and edge-case tests for {@link ZstdColumnCompressor}.
 */
class ZstdColumnCompressorTest {

    private static final Random RNG = new Random(42);

    @Test
    void compressDecompressRoundTrip() {
        // Use structured data that compresses well (repeating pattern)
        byte[] original = new byte[4096];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i % 37); // repeating pattern
        }

        byte[] compressed = ZstdColumnCompressor.compress(original);
        assertNotNull(compressed, "structured data should be compressible");
        assertTrue(compressed.length < original.length,
                "compressed size " + compressed.length + " should be less than original " + original.length);

        byte[] decompressed = ZstdColumnCompressor.decompress(compressed, original.length);
        assertArrayEquals(original, decompressed, "round-trip should preserve data");
    }

    @Test
    void smallDataNotCompressed() {
        byte[] small = new byte[100];
        RNG.nextBytes(small);

        byte[] result = ZstdColumnCompressor.compress(small);
        assertNull(result, "data below minCompressBytes should not be compressed");
    }

    @Test
    void nullInputReturnsNull() {
        assertNull(ZstdColumnCompressor.compress(null));
    }

    @Test
    void decompressEmptyReturnsEmpty() {
        byte[] result = ZstdColumnCompressor.decompress(new byte[0], 10);
        assertEquals(0, result.length);
    }

    @Test
    void decompressNullReturnsEmpty() {
        byte[] result = ZstdColumnCompressor.decompress(null, 10);
        assertEquals(0, result.length);
    }

    @Test
    void decompressZeroOriginalSizeReturnsEmpty() {
        byte[] compressed = ZstdColumnCompressor.compress(new byte[512]);
        byte[] result = ZstdColumnCompressor.decompress(compressed, 0);
        assertEquals(0, result.length);
    }

    @Test
    void allZeroDataCompressesWell() {
        byte[] zeros = new byte[8192]; // all zeros
        byte[] compressed = ZstdColumnCompressor.compress(zeros);
        assertNotNull(compressed);
        assertTrue(compressed.length < zeros.length / 10,
                "all-zero data should compress very well, got " + compressed.length + " from 8192");

        byte[] decompressed = ZstdColumnCompressor.decompress(compressed, zeros.length);
        assertArrayEquals(zeros, decompressed);
    }

    @Test
    void highEntropyDataMayNotCompress() {
        // Very high entropy data may not compress; compress() returns null in that case.
        byte[] highEntropy = new byte[2048];
        // Fill with very high-entropy random data
        for (int i = 0; i < highEntropy.length; i++) {
            highEntropy[i] = (byte) RNG.nextInt(256);
        }

        byte[] compressed = ZstdColumnCompressor.compress(highEntropy);
        // It may or may not compress well enough — either result is valid
        if (compressed != null) {
            byte[] decompressed = ZstdColumnCompressor.decompress(compressed, highEntropy.length);
            assertArrayEquals(highEntropy, decompressed);
        }
    }

    @Test
    void configureCompressionLevel() {
        ZstdColumnCompressor.setCompressionLevel(1);
        assertEquals(1, ZstdColumnCompressor.getCompressionLevel());

        ZstdColumnCompressor.setCompressionLevel(19);
        assertEquals(19, ZstdColumnCompressor.getCompressionLevel());

        ZstdColumnCompressor.setCompressionLevel(100); // clamped
        assertEquals(19, ZstdColumnCompressor.getCompressionLevel());

        ZstdColumnCompressor.setCompressionLevel(-5); // clamped
        assertEquals(1, ZstdColumnCompressor.getCompressionLevel());

        // Restore default
        ZstdColumnCompressor.setCompressionLevel(ZstdColumnCompressor.DEFAULT_COMPRESSION_LEVEL);
    }

    @Test
    void configureMinCompressBytes() {
        ZstdColumnCompressor.setMinCompressBytes(100);
        assertEquals(100, ZstdColumnCompressor.getMinCompressBytes());

        // Now 200 bytes of structured data should be compressed (above 100)
        byte[] data = new byte[200];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 17); // repeating pattern
        }
        byte[] result = ZstdColumnCompressor.compress(data);
        assertNotNull(result, "200 structured bytes should be compressed when minCompressBytes=100");

        // Restore default
        ZstdColumnCompressor.setMinCompressBytes(ZstdColumnCompressor.DEFAULT_MIN_COMPRESS_BYTES);
    }

    @Test
    void decompressWrongSizeThrows() {
        byte[] original = new byte[1024];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i % 41);
        }
        byte[] compressed = ZstdColumnCompressor.compress(original);
        assertNotNull(compressed);

        // Decompressing with a wrong size should throw
        assertThrows(IllegalArgumentException.class, () ->
                ZstdColumnCompressor.decompress(compressed, original.length + 500));
    }
}

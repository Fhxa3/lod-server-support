package dev.vox.lss.common.compression;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;

/**
 * Stateless Zstd compression utility for LSS column section data.
 *
 * <p>Compression and decompression contexts are held in {@link ThreadLocal} storage,
 * safe for concurrent use from the OffThreadProcessor (single dedicated thread),
 * disk reader threads, and the client decode executor.
 *
 * <p>Magicless mode is used to shave the 4-byte zstd frame header; content-size
 * is omitted (we track originalSize separately on the wire). Window-log is 23
 * (8 MB window) to handle the largest column payloads.
 */
public final class ZstdColumnCompressor {
    private ZstdColumnCompressor() {}

    /** Minimum byte count to attempt compression — smaller data often expands. */
    public static final int DEFAULT_MIN_COMPRESS_BYTES = 256;
    /** Default Zstd compression level (1-19, higher = better ratio but slower). */
    public static final int DEFAULT_COMPRESSION_LEVEL = 3;
    /** Zstd window log (23 = 8 MB window, sufficient for MAX_SEND_SECTIONS_SIZE). */
    private static final int WINDOW_LOG = 23;

    private static volatile int compressionLevel = DEFAULT_COMPRESSION_LEVEL;
    private static volatile int minCompressBytes = DEFAULT_MIN_COMPRESS_BYTES;

    // Cumulative statistics (written from processing thread, read by /lsslod diag)
    private static volatile long totalColumnsCompressed;
    private static volatile long totalRawBytes;
    private static volatile long totalCompressedBytes;

    private static final ThreadLocal<ZstdCompressCtx> COMPRESS_CTX = ThreadLocal.withInitial(() -> {
        var ctx = new ZstdCompressCtx();
        ctx.setLevel(compressionLevel);
        ctx.setContentSize(false);
        ctx.setMagicless(true);
        ctx.setWindowLog(WINDOW_LOG);
        return ctx;
    });

    private static final ThreadLocal<ZstdDecompressCtx> DECOMPRESS_CTX = ThreadLocal.withInitial(() -> {
        var ctx = new ZstdDecompressCtx();
        ctx.setMagicless(true);
        return ctx;
    });

    /**
     * Update the compression level for subsequently created contexts.
     * Existing contexts are not affected until the next ThreadLocal creation cycle.
     */
    public static void setCompressionLevel(int level) {
        compressionLevel = Math.clamp(level, 1, 19);
    }

    /**
     * Update the minimum byte threshold for compression.
     */
    public static void setMinCompressBytes(int minBytes) {
        minCompressBytes = Math.max(minBytes, 0);
    }

    public static int getCompressionLevel() { return compressionLevel; }
    public static int getMinCompressBytes() { return minCompressBytes; }
    public static long getTotalColumnsCompressed() { return totalColumnsCompressed; }
    public static long getTotalRawBytes() { return totalRawBytes; }
    public static long getTotalCompressedBytes() { return totalCompressedBytes; }

    /**
     * Compress raw section bytes with Zstd.
     *
     * @param data the uncompressed section bytes
     * @return compressed bytes, or {@code null} if the data is below the minimum
     *         threshold or compression would not save space
     */
    public static byte[] compress(byte[] data) {
        if (data == null || data.length < minCompressBytes) {
            return null;
        }

        var ctx = COMPRESS_CTX.get();
        // Refresh level in case it was changed; setLevel is cheap (just updates an int field)
        ctx.setLevel(compressionLevel);

        int maxDstSize = (int) Zstd.compressBound(data.length);
        byte[] dst = new byte[maxDstSize];
        long compressedSize;
        try {
            compressedSize = ctx.compressByteArray(dst, 0, dst.length, data, 0, data.length);
        } catch (RuntimeException e) {
            // Zstd may throw on corrupted internal state or OOM; treat as incompressible
            return null;
        }

        if (Zstd.isError(compressedSize)) {
            return null;
        }

        int size = (int) compressedSize;
        // Only use compression if it actually saves space (>5% reduction to be worth it)
        if (size >= data.length - data.length / 20) {
            return null;
        }

        byte[] result = new byte[size];
        System.arraycopy(dst, 0, result, 0, size);

        // Record cumulative stats for /lsslod diag
        totalColumnsCompressed++;
        totalRawBytes += data.length;
        totalCompressedBytes += size;

        return result;
    }

    /**
     * Decompress Zstd-compressed section bytes back to their original form.
     *
     * @param compressed   the compressed data
     * @param originalSize the uncompressed size as recorded on the wire
     * @return decompressed bytes
     */
    public static byte[] decompress(byte[] compressed, int originalSize) {
        if (compressed == null || compressed.length == 0) {
            return new byte[0];
        }
        if (originalSize <= 0) {
            return new byte[0];
        }

        var ctx = DECOMPRESS_CTX.get();
        byte[] dst = new byte[originalSize];
        long decompressedSize;
        try {
            decompressedSize = ctx.decompressByteArray(dst, 0, dst.length, compressed, 0, compressed.length);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Zstd decompression failed: " + e.getMessage(), e);
        }

        if (Zstd.isError(decompressedSize)) {
            throw new IllegalArgumentException(
                    "Zstd decompression error: " + Zstd.getErrorName(decompressedSize));
        }

        if ((int) decompressedSize != originalSize) {
            throw new IllegalArgumentException(
                    "Zstd decompressed size mismatch: expected " + originalSize + ", got " + decompressedSize);
        }

        return dst;
    }
}

package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.compression.ZstdColumnCompressor;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Zstd-compressed variant of {@link VoxelColumnS2CPayload}.
 * Sent only to clients that declare {@link LSSConstants#CAPABILITY_ZSTD_COMPRESSION}.
 *
 * <p>Wire format: chunkX (int), chunkZ (int), dimension (utf), columnTimestamp (long),
 * originalSize (varint), compressedSectionBytes (byte array).
 */
public final class VoxelColumnZstdS2CPayload implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<VoxelColumnZstdS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_VOXEL_COLUMN_ZSTD));

    public static final StreamCodec<FriendlyByteBuf, VoxelColumnZstdS2CPayload> CODEC =
            StreamCodec.of(
                    VoxelColumnZstdS2CPayload::write,
                    VoxelColumnZstdS2CPayload::read
            );

    private final int chunkX;
    private final int chunkZ;
    private final ResourceKey<Level> dimension;
    private final long columnTimestamp;
    private final int originalSize;
    private final byte[] compressedSectionBytes;

    public VoxelColumnZstdS2CPayload(int chunkX, int chunkZ,
                                      ResourceKey<Level> dimension, long columnTimestamp,
                                      int originalSize, byte[] compressedSectionBytes) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dimension = dimension;
        this.columnTimestamp = columnTimestamp;
        this.originalSize = originalSize;
        this.compressedSectionBytes = compressedSectionBytes;
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public ResourceKey<Level> dimension() { return dimension; }
    public long columnTimestamp() { return columnTimestamp; }

    /** Returns the decompressed section bytes (used by client-side processing). */
    public byte[] decompressedSections() {
        return ZstdColumnCompressor.decompress(compressedSectionBytes, originalSize);
    }

    public int estimatedBytes() {
        return (compressedSectionBytes != null ? compressedSectionBytes.length : 0)
                + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES + 5; // +5 for varint originalSize
    }

    /** Returns true if this column carries zero sections (authoritative clear). */
    public boolean isClearColumn() {
        byte[] decompressed = decompressedSections();
        if (decompressed == null || decompressed.length == 0) return false;
        // Read just the leading section-count varint — a 0 means no sections
        return decompressed.length > 0 && decompressed[0] == 0;
    }

    private static void write(FriendlyByteBuf buf, VoxelColumnZstdS2CPayload payload) {
        buf.writeInt(payload.chunkX);
        buf.writeInt(payload.chunkZ);
        buf.writeUtf(payload.dimension.identifier().toString(), LSSConstants.MAX_DIMENSION_STRING_LENGTH);
        buf.writeLong(payload.columnTimestamp);
        buf.writeVarInt(payload.originalSize);
        buf.writeByteArray(payload.compressedSectionBytes);
    }

    private static VoxelColumnZstdS2CPayload read(FriendlyByteBuf buf) {
        int cx = buf.readInt();
        int cz = buf.readInt();
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH)));
        long columnTimestamp = buf.readLong();
        int originalSize = buf.readVarInt();
        byte[] compressedSectionBytes = buf.readByteArray(LSSConstants.MAX_SECTIONS_SIZE);

        return new VoxelColumnZstdS2CPayload(cx, cz, dim, columnTimestamp, originalSize, compressedSectionBytes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

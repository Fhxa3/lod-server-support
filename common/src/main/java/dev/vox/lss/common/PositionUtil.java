package dev.vox.lss.common;

/**
 * Chunk coordinate packing utilities shared by both Fabric and Paper.
 * Encodes two 32-bit chunk coordinates into a single 64-bit long.
 */
public final class PositionUtil {
    private PositionUtil() {}

    public static long packPosition(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackZ(long packed) {
        return (int) packed;
    }

    public static int chebyshevDistance(int x1, int z1, int x2, int z2) {
        // Widen to long before subtracting so extreme client-supplied coordinates
        // (e.g. Integer.MIN_VALUE) can't overflow and slip under the distance gate.
        long dx = Math.abs((long) x1 - x2);
        long dz = Math.abs((long) z1 - z2);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(dx, dz));
    }

    public static boolean isOutOfRange(long packed, int playerCx, int playerCz, int distance) {
        return chebyshevDistance(unpackX(packed), unpackZ(packed), playerCx, playerCz) > distance;
    }
}

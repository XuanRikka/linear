package org.linear.storage.util;

/**
 * 纯 Java 实现的 XXHash32（对应 xxhash 官方规范）。
 * <p>
 * 与 lz4-java 的 {@code XXHashFactory.hash32()}、Rust 的 {@code xxhash_rust::xxh32}
 * 结果一致，用于 BufferedLinearV3 的 chunk 校验和（种子 0x0721）。
 */
public final class XXHash32 {
    private static final int PRIME32_1 = 0x9E3779B1;
    private static final int PRIME32_2 = 0x85EBCA77;
    private static final int PRIME32_3 = 0xC2B2AE3D;
    private static final int PRIME32_4 = 0x27D4EB2F;
    private static final int PRIME32_5 = 0x165667B1;

    private XXHash32() {
    }

    public static int hash(byte[] input, int seed) {
        return hash(input, 0, input.length, seed);
    }

    public static int hash(byte[] input, int off, int len, int seed) {
        final int end = off + len;
        int h32;
        int p = off;

        if (len >= 16) {
            final int limit = end - 16;
            int v1 = seed + PRIME32_1 + PRIME32_2;
            int v2 = seed + PRIME32_2;
            int v3 = seed;
            int v4 = seed - PRIME32_1;
            do {
                v1 = round(v1, readIntLE(input, p));
                p += 4;
                v2 = round(v2, readIntLE(input, p));
                p += 4;
                v3 = round(v3, readIntLE(input, p));
                p += 4;
                v4 = round(v4, readIntLE(input, p));
                p += 4;
            } while (p <= limit);

            h32 = Integer.rotateLeft(v1, 1) + Integer.rotateLeft(v2, 7)
                    + Integer.rotateLeft(v3, 12) + Integer.rotateLeft(v4, 18);
        } else {
            h32 = seed + PRIME32_5;
        }

        h32 += len;

        while (p + 4 <= end) {
            h32 += readIntLE(input, p) * PRIME32_3;
            h32 = Integer.rotateLeft(h32, 17) * PRIME32_4;
            p += 4;
        }

        while (p < end) {
            h32 += (input[p] & 0xFF) * PRIME32_5;
            h32 = Integer.rotateLeft(h32, 11) * PRIME32_1;
            p++;
        }

        h32 ^= h32 >>> 15;
        h32 *= PRIME32_2;
        h32 ^= h32 >>> 13;
        h32 *= PRIME32_3;
        h32 ^= h32 >>> 16;
        return h32;
    }

    private static int round(int acc, int input) {
        acc += input * PRIME32_2;
        acc = Integer.rotateLeft(acc, 13);
        acc *= PRIME32_1;
        return acc;
    }

    private static int readIntLE(byte[] b, int i) {
        return (b[i] & 0xFF)
                | ((b[i + 1] & 0xFF) << 8)
                | ((b[i + 2] & 0xFF) << 16)
                | ((b[i + 3] & 0xFF) << 24);
    }
}

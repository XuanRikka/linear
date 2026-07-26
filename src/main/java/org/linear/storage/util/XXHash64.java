package org.linear.storage.util;

/**
 * 纯 Java 实现的 XXHash64（对应 xxhash 官方规范）。
 * <p>
 * 与 Rust 的 {@code xxhash_rust::xxh64}、zero-allocation-hashing 的
 * {@code LongHashFunction.xx()} 结果一致，用于 LinearV2 bucket 的校验和（种子 0）。
 */
public final class XXHash64 {
    private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
    private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
    private static final long PRIME64_3 = 0x165667B19E3779F9L;
    private static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
    private static final long PRIME64_5 = 0x27D4EB2F165667C5L;

    private XXHash64() {
    }

    public static long hash(byte[] input, long seed) {
        return hash(input, 0, input.length, seed);
    }

    public static long hash(byte[] input, int off, int len, long seed) {
        final int end = off + len;
        long h64;
        int p = off;

        if (len >= 32) {
            final int limit = end - 32;
            long v1 = seed + PRIME64_1 + PRIME64_2;
            long v2 = seed + PRIME64_2;
            long v3 = seed;
            long v4 = seed - PRIME64_1;
            do {
                v1 = round(v1, readLongLE(input, p));
                p += 8;
                v2 = round(v2, readLongLE(input, p));
                p += 8;
                v3 = round(v3, readLongLE(input, p));
                p += 8;
                v4 = round(v4, readLongLE(input, p));
                p += 8;
            } while (p <= limit);

            h64 = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7)
                    + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            h64 = mergeRound(h64, v1);
            h64 = mergeRound(h64, v2);
            h64 = mergeRound(h64, v3);
            h64 = mergeRound(h64, v4);
        } else {
            h64 = seed + PRIME64_5;
        }

        h64 += len;

        while (p + 8 <= end) {
            h64 ^= round(0, readLongLE(input, p));
            h64 = Long.rotateLeft(h64, 27) * PRIME64_1 + PRIME64_4;
            p += 8;
        }

        if (p + 4 <= end) {
            h64 ^= (readIntLE(input, p) & 0xFFFFFFFFL) * PRIME64_1;
            h64 = Long.rotateLeft(h64, 23) * PRIME64_2 + PRIME64_3;
            p += 4;
        }

        while (p < end) {
            h64 ^= (input[p] & 0xFFL) * PRIME64_5;
            h64 = Long.rotateLeft(h64, 11) * PRIME64_1;
            p++;
        }

        h64 ^= h64 >>> 33;
        h64 *= PRIME64_2;
        h64 ^= h64 >>> 29;
        h64 *= PRIME64_3;
        h64 ^= h64 >>> 32;
        return h64;
    }

    private static long round(long acc, long input) {
        acc += input * PRIME64_2;
        acc = Long.rotateLeft(acc, 31);
        acc *= PRIME64_1;
        return acc;
    }

    private static long mergeRound(long acc, long val) {
        acc ^= round(0, val);
        acc = acc * PRIME64_1 + PRIME64_4;
        return acc;
    }

    private static long readLongLE(byte[] b, int i) {
        return (b[i] & 0xFFL)
                | ((b[i + 1] & 0xFFL) << 8)
                | ((b[i + 2] & 0xFFL) << 16)
                | ((b[i + 3] & 0xFFL) << 24)
                | ((b[i + 4] & 0xFFL) << 32)
                | ((b[i + 5] & 0xFFL) << 40)
                | ((b[i + 6] & 0xFFL) << 48)
                | ((b[i + 7] & 0xFFL) << 56);
    }

    private static int readIntLE(byte[] b, int i) {
        return (b[i] & 0xFF)
                | ((b[i + 1] & 0xFF) << 8)
                | ((b[i + 2] & 0xFF) << 16)
                | ((b[i + 3] & 0xFF) << 24);
    }
}

package org.linear.storage.util;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * zstd-jni 的薄封装，收敛本 mod 用到的全部 zstd API。
 */
public final class ZstdUtil {
    private ZstdUtil() {
    }

    /**
     * 一次性压缩。
     *
     * @param checksum 是否在 zstd frame 内附带校验和
     *                 （LinearV2 的参考实现带，BufferedLinearV3 的参考实现不带）
     */
    public static byte[] compress(byte[] data, int level, boolean checksum) {
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.setLevel(level);
            ctx.setChecksum(checksum);
            return ctx.compress(data);
        }
    }

    /** 已知原始长度的一次性解压（BufferedLinearV3 的 bucket / swap sector）。 */
    public static byte[] decompress(byte[] data, int rawSize) {
        return Zstd.decompress(data, rawSize);
    }

    /** 未知原始长度的流式解压（LinearV2 的 bucket 不存原始长度）。 */
    public static byte[] decompressStream(byte[] data) throws IOException {
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }
}

import net.minecraft.world.level.ChunkPos;
import org.linear.LinearConfig;
import org.linear.storage.BufferedLinearFlusher;
import org.linear.storage.BufferedLinearV3RegionFile;
import org.linear.storage.IRegionFile;
import org.linear.storage.LinearV2Flusher;
import org.linear.storage.LinearV2RegionFile;
import org.linear.storage.util.XXHash64;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 交叉验证 harness：绕过 FabricLoader 直接驱动 LinearV2RegionFile / BufferedLinearV3RegionFile。
 * 用法：
 *   dump <file> <rx> <rz>                         —— 输出每个非空 chunk 的 "idx len xxh64"
 *   copy <src> <rx> <rz> <v2|v3> <dst> <grid> <level> —— 读 src 全部 chunk 写入 dst
 *   expectv1error <file>                          —— 验证打开 v1 文件报清晰错误
 */
public final class Xval {
    private static final long V2_MAGIC = 0xc3ff13183cca9d9aL;
    private static final long V3_MAGIC = 0xFFFFDFF7EDDAFD97L;

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "dump" -> dump(Path.of(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            case "copy" -> copy(Path.of(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                    args[4], Path.of(args[5]), Integer.parseInt(args[6]), Integer.parseInt(args[7]));
            case "expectv1error" -> expectV1Error(Path.of(args[1]));
            case "flushtest" -> flushTest(Path.of(args[1]));
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static LinearConfig cfg(LinearConfig.Format format, int level, int grid, int v2Interval)
            throws Exception {
        Constructor<LinearConfig> c = LinearConfig.class.getDeclaredConstructor(
                LinearConfig.Format.class, int.class, int.class, int.class, int.class, int.class);
        c.setAccessible(true);
        return c.newInstance(format, level, grid, v2Interval, 600, 1);
    }

    static LinearConfig cfg(LinearConfig.Format format, int level, int grid) throws Exception {
        return cfg(format, level, grid, 0); // 常规命令禁用周期 flush，行为与旧 harness 一致
    }

    static BufferedLinearFlusher flusher(LinearConfig config) throws Exception {
        Constructor<BufferedLinearFlusher> c =
                BufferedLinearFlusher.class.getDeclaredConstructor(LinearConfig.class);
        c.setAccessible(true);
        return c.newInstance(config);
    }

    static LinearV2Flusher v2Flusher(LinearConfig config) throws Exception {
        Constructor<LinearV2Flusher> c =
                LinearV2Flusher.class.getDeclaredConstructor(LinearConfig.class);
        c.setAccessible(true);
        return c.newInstance(config);
    }

    static long magic(Path p) throws IOException {
        if (!Files.isRegularFile(p) || Files.size(p) < 8) return 0;
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            while (buf.hasRemaining() && ch.read(buf) >= 0) { /* fill */ }
            buf.flip();
            return buf.getLong();
        }
    }

    static IRegionFile open(Path p, int rx, int rz, LinearConfig config, BufferedLinearFlusher fl,
                            LinearV2Flusher v2fl) throws Exception {
        long m = magic(p);
        if (m == V2_MAGIC) return new LinearV2RegionFile(p, rx, rz, config, v2fl);
        if (m == V3_MAGIC) return new BufferedLinearV3RegionFile(p, config, fl);
        if (m == 0) { // 新文件：按扩展名决定
            if (p.toString().endsWith(".b_linear")) return new BufferedLinearV3RegionFile(p, config, fl);
            return new LinearV2RegionFile(p, rx, rz, config, v2fl);
        }
        throw new IOException("未知魔数 " + Long.toHexString(m) + ": " + p);
    }

    static void dump(Path p, int rx, int rz) throws Exception {
        LinearConfig config = cfg(LinearConfig.Format.LINEAR_V2, 3, 8);
        BufferedLinearFlusher fl = flusher(config);
        LinearV2Flusher v2fl = v2Flusher(config);
        int count = 0;
        long total = 0;
        try (IRegionFile f = open(p, rx, rz, config, fl, v2fl)) {
            for (int dz = 0; dz < 32; dz++) {
                for (int dx = 0; dx < 32; dx++) {
                    ChunkPos pos = new ChunkPos(rx * 32 + dx, rz * 32 + dz);
                    try (DataInputStream in = f.getChunkDataInputStream(pos)) {
                        if (in == null) continue;
                        byte[] data = in.readAllBytes();
                        int idx = dx + dz * 32;
                        System.out.printf("%d %d %016x%n", idx, data.length,
                                XXHash64.hash(data, 0));
                        count++;
                        total += data.length;
                    }
                }
            }
        }
        System.out.printf("TOTAL %d chunks %d bytes%n", count, total);
    }

    static void copy(Path src, int rx, int rz, String fmt, Path dst, int grid, int level)
            throws Exception {
        LinearConfig config = cfg("v3".equals(fmt)
                ? LinearConfig.Format.BUFFERED_LINEAR_V3
                : LinearConfig.Format.LINEAR_V2, level, grid);
        BufferedLinearFlusher fl = flusher(config);
        LinearV2Flusher v2fl = v2Flusher(config);
        Files.createDirectories(dst.getParent());
        Files.deleteIfExists(dst);
        int copied = 0;
        try (IRegionFile in = open(src, rx, rz, config, fl, v2fl);
             IRegionFile out = open(dst, rx, rz, config, fl, v2fl)) {
            for (int dz = 0; dz < 32; dz++) {
                for (int dx = 0; dx < 32; dx++) {
                    ChunkPos pos = new ChunkPos(rx * 32 + dx, rz * 32 + dz);
                    byte[] data;
                    try (DataInputStream s = in.getChunkDataInputStream(pos)) {
                        if (s == null) continue;
                        data = s.readAllBytes();
                    }
                    try (DataOutputStream o = out.getChunkDataOutputStream(pos)) {
                        o.write(data);
                    }
                    copied++;
                }
            }
            out.flush();
        }
        System.out.println("COPIED " + copied + " -> " + dst);
    }

    /**
     * 端到端验证周期 flush：interval=5s，写入后不 flush/close，
     * 文件应在 5~15s 内由后台线程落盘，且内容可读、与写入一致。
     */
    static void flushTest(Path dst) throws Exception {
        LinearConfig config = cfg(LinearConfig.Format.LINEAR_V2, 1, 8, 5);
        LinearV2Flusher v2fl = v2Flusher(config);
        Files.createDirectories(dst.getParent());
        Files.deleteIfExists(dst);

        byte[] payload = new byte[8192];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 131 + 17);

        LinearV2RegionFile f = new LinearV2RegionFile(dst, 0, 0, config, v2fl);
        ChunkPos pos = new ChunkPos(5, 7);
        try (DataOutputStream o = f.getChunkDataOutputStream(pos)) {
            o.write(payload);
        }

        if (Files.exists(dst)) {
            System.out.println("FAIL: 写入后立即出现文件（应等周期到）");
            System.exit(1);
        }
        long start = System.nanoTime();
        while (!Files.exists(dst)) {
            if (System.nanoTime() - start > 20_000_000_000L) {
                System.out.println("FAIL: 20s 内后台线程未落盘");
                System.exit(1);
            }
            Thread.sleep(500);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 独立重开验证内容（此时原实例仍开着、未 close）
        try (LinearV2RegionFile check = new LinearV2RegionFile(dst, 0, 0, config, v2fl);
             DataInputStream in = check.getChunkDataInputStream(pos)) {
            byte[] got = in.readAllBytes();
            if (!java.util.Arrays.equals(payload, got)) {
                System.out.println("FAIL: 落盘内容与写入不一致");
                System.exit(1);
            }
        }
        f.close();
        System.out.println("PASS flushtest：后台落盘耗时 " + elapsedMs + "ms（预期约 4000~6000ms），内容一致");
    }

    static void expectV1Error(Path p) throws Exception {
        LinearConfig config = cfg(LinearConfig.Format.LINEAR_V2, 3, 8);
        try {
            new LinearV2RegionFile(p, 0, 0, config, v2Flusher(config)).close();
            System.out.println("FAIL: v1 文件没有报错");
            System.exit(1);
        } catch (IOException e) {
            System.out.println("OK v1 rejected: " + e.getMessage());
        }
    }
}

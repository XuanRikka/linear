package org.linear.storage;

import net.minecraft.world.level.ChunkPos;
import org.linear.LinearConfig;
import org.linear.storage.util.XXHash64;
import org.linear.storage.util.ZstdUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LinearV2（xymb 格式，{@code r.X.Z.linear}）区域文件。
 * <p>
 * 全部数据驻留内存：打开时整文件解析，{@link #flush()} / {@link #close()} 时若有修改
 * 则整文件重新序列化（先写 {@code .tmp} 再原子替换）。
 * <p>
 * 格式事实（与 linear-tools-rs 对齐，全部大端）：
 * <ul>
 *   <li>磁盘上的 version 字节是 3（LINEARv2.md 写 2 是文档错误）；1/2 是 linear v1 布局，直接报错。</li>
 *   <li>128B 存在位图只写不信：读取按 entry size 判断（上游 Java 实现写出的位图是坏的）。</li>
 *   <li>entry 的 size 含 8 字节时间戳（size = 数据长 + 8），空 chunk size=0 但时间戳照写；
 *       size&lt;8 视为空（Rust 同款防御）。时间戳单位是<b>秒</b>。</li>
 *   <li>bucket 的 xxh64 对压缩后字节、种子 0 计算；zstd 帧带 checksum；
 *       格式不存原始长度，解压必须走流式。</li>
 *   <li>不复刻 Rust 写入端 data.len()==64 写空桶的 quirk（那是上游抄来的丢数据 bug）。</li>
 * </ul>
 */
public final class LinearV2RegionFile implements IRegionFile {
    private static final long LINEAR_SUPER_BLOCK = 0xc3ff13183cca9d9aL;
    private static final long B_LINEAR_SUPER_BLOCK = 0xFFFFDFF7EDDAFD97L;
    private static final byte LINEAR_V2_VERSION = 3;

    private final Path path;
    private final int regionX;
    private final int regionZ;
    private final LinearConfig config;

    private final Object lock = new Object();
    private final byte[][] chunks = new byte[1024][];
    private final long[] timestamps = new long[1024];
    private final Map<String, Integer> nbtFeatures = new LinkedHashMap<>();
    private boolean dirty;

    public LinearV2RegionFile(Path path, int regionX, int regionZ, LinearConfig config) throws IOException {
        this.path = path;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.config = config;

        if (Files.isRegularFile(path) && Files.size(path) > 0) {
            try {
                this.parse(Files.readAllBytes(path));
            } catch (BufferUnderflowException e) {
                throw new IOException("linear 文件截断或损坏: " + path, e);
            }
        }
    }

    private static int chunkIndex(ChunkPos pos) {
        return (pos.x() & 31) + ((pos.z() & 31) << 5);
    }

    // ---------------------------------------------------------------- 解析

    private void parse(byte[] fileBytes) throws IOException {
        final ByteBuffer buf = ByteBuffer.wrap(fileBytes);

        final long magic = buf.getLong();
        if (magic == B_LINEAR_SUPER_BLOCK) {
            throw new IOException("这是 BufferedLinear master 文件却使用了 .linear 扩展名，请改回 .b_linear: " + this.path);
        }
        if (magic != LINEAR_SUPER_BLOCK) {
            throw new IOException("未知魔数 0x" + Long.toHexString(magic) + ": " + this.path);
        }

        final int version = buf.get() & 0xFF;
        if (version == 1 || version == 2) {
            throw new IOException("linear v1 格式不支持，请先用 linear-tools-rs 转换为 LinearV2: " + this.path);
        }
        if (version != LINEAR_V2_VERSION) {
            throw new IOException("未知 linear 版本 " + version + ": " + this.path);
        }

        buf.getLong(); // newestTimestamp，重新保存时会重算
        final int grid = buf.get();
        if (grid != 1 && grid != 2 && grid != 4 && grid != 8 && grid != 16 && grid != 32) {
            throw new IOException("非法 grid-size " + grid + ": " + this.path);
        }
        buf.getInt(); // region_x，以构造参数为准
        buf.getInt(); // region_z
        buf.position(buf.position() + 128); // 位图坏的多，跳过不信，按 entry size 判断存在

        while (true) {
            final int keyLen = buf.get() & 0xFF;
            if (keyLen == 0) {
                break;
            }
            final byte[] keyBytes = new byte[keyLen];
            buf.get(keyBytes);
            final int value = buf.getInt();
            this.nbtFeatures.put(new String(keyBytes, StandardCharsets.UTF_8), value);
        }

        final int totalBuckets = grid * grid;
        final int[] bucketSizes = new int[totalBuckets];
        final long[] bucketHashes = new long[totalBuckets];
        for (int i = 0; i < totalBuckets; i++) {
            bucketSizes[i] = buf.getInt();
            buf.get(); // 每桶压缩等级，读取用不到
            bucketHashes[i] = buf.getLong();
        }

        final int cpb = 32 / grid;
        for (int bx = 0; bx < grid; bx++) {
            for (int bz = 0; bz < grid; bz++) {
                final int bucketIdx = bx * grid + bz;
                final int size = bucketSizes[bucketIdx];
                if (size <= 0) {
                    continue; // 整桶为空是合法情况
                }

                final byte[] compressed = new byte[size];
                buf.get(compressed);
                final long hash = XXHash64.hash(compressed, 0);
                if (hash != bucketHashes[bucketIdx]) {
                    throw new IOException("bucket " + bucketIdx + " xxh64 校验失败，期望 0x"
                            + Long.toHexString(bucketHashes[bucketIdx]) + " 实际 0x"
                            + Long.toHexString(hash) + ": " + this.path);
                }

                final ByteBuffer bucket = ByteBuffer.wrap(ZstdUtil.decompressStream(compressed));
                readBucket:
                for (int ix = 0; ix < cpb; ix++) {
                    for (int iz = 0; iz < cpb; iz++) {
                        if (bucket.remaining() < 12) {
                            break readBucket; // entry 不足按空 chunk 处理（对齐 Rust 的宽容读取）
                        }
                        final int idx = (bx * cpb + ix) + (bz * cpb + iz) * 32;
                        final int entrySize = bucket.getInt();
                        final long timestamp = bucket.getLong();
                        this.timestamps[idx] = timestamp;
                        if (entrySize < 8) {
                            continue;
                        }
                        final int dataLen = entrySize - 8;
                        if (bucket.remaining() < dataLen) {
                            break readBucket;
                        }
                        final byte[] data = new byte[dataLen];
                        bucket.get(data);
                        this.chunks[idx] = data;
                    }
                }
            }
        }

        final long footer = buf.getLong();
        if (footer != LINEAR_SUPER_BLOCK) {
            throw new IOException("尾部魔数校验失败: " + this.path);
        }
    }

    // ---------------------------------------------------------------- 保存

    private byte[] serialize() throws IOException {
        final int grid = this.config.gridSize;
        final int cpb = 32 / grid;
        final int level = this.config.compressionLevel;

        final byte[][] compressedBuckets = new byte[grid * grid][];
        for (int bx = 0; bx < grid; bx++) {
            for (int bz = 0; bz < grid; bz++) {
                final ByteArrayOutputStream rawBuf = new ByteArrayOutputStream();
                final DataOutputStream raw = new DataOutputStream(rawBuf);
                for (int ix = 0; ix < cpb; ix++) {
                    for (int iz = 0; iz < cpb; iz++) {
                        final int idx = (bx * cpb + ix) + (bz * cpb + iz) * 32;
                        final byte[] data = this.chunks[idx];
                        if (data == null) {
                            raw.writeInt(0);
                            raw.writeLong(this.timestamps[idx]);
                        } else {
                            raw.writeInt(data.length + 8);
                            raw.writeLong(this.timestamps[idx]);
                            raw.write(data);
                        }
                    }
                }
                compressedBuckets[bx * grid + bz] = ZstdUtil.compress(rawBuf.toByteArray(), level, true);
            }
        }

        long newestTimestamp = 0;
        for (final long ts : this.timestamps) {
            newestTimestamp = Math.max(newestTimestamp, ts);
        }

        final byte[] bitmap = new byte[128];
        for (int i = 0; i < 1024; i++) {
            if (this.chunks[i] != null) {
                bitmap[i >> 3] |= (byte) (1 << (7 - (i & 7)));
            }
        }

        final ByteArrayOutputStream fileBuf = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(fileBuf);
        out.writeLong(LINEAR_SUPER_BLOCK);
        out.writeByte(LINEAR_V2_VERSION);
        out.writeLong(newestTimestamp);
        out.writeByte(grid);
        out.writeInt(this.regionX);
        out.writeInt(this.regionZ);
        out.write(bitmap);

        for (final Map.Entry<String, Integer> feature : this.nbtFeatures.entrySet()) {
            final byte[] keyBytes = feature.getKey().getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length == 0 || keyBytes.length > 255) {
                throw new IOException("nbt feature 键长度非法: " + feature.getKey());
            }
            out.writeByte(keyBytes.length);
            out.write(keyBytes);
            out.writeInt(feature.getValue());
        }
        out.writeByte(0);

        for (final byte[] bucket : compressedBuckets) {
            out.writeInt(bucket.length);
            out.writeByte(level);
            out.writeLong(XXHash64.hash(bucket, 0));
        }
        for (final byte[] bucket : compressedBuckets) {
            out.write(bucket);
        }

        out.writeLong(LINEAR_SUPER_BLOCK);
        return fileBuf.toByteArray();
    }

    private void save() throws IOException {
        final byte[] fileBytes = this.serialize();
        final Path tmp = Path.of(this.path + ".tmp");

        try (FileChannel channel = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            final ByteBuffer buf = ByteBuffer.wrap(fileBytes);
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
        }

        try {
            Files.move(tmp, this.path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp, this.path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                e.addSuppressed(e2);
                Files.deleteIfExists(tmp);
                throw new IOException("替换 " + this.path + " 失败", e);
            }
        }

        this.dirty = false;
    }

    // ---------------------------------------------------------------- IRegionFile

    @Override
    public Path getPath() {
        return this.path;
    }

    @Override
    public DataInputStream getChunkDataInputStream(ChunkPos pos) {
        final byte[] data;
        synchronized (this.lock) {
            data = this.chunks[chunkIndex(pos)];
        }
        if (data == null) {
            return null;
        }
        // chunks[idx] 的数组只会整体替换、从不原地修改，直接暴露无需拷贝
        return new DataInputStream(new ByteArrayInputStream(data));
    }

    @Override
    public DataOutputStream getChunkDataOutputStream(ChunkPos pos) {
        return new DataOutputStream(new ChunkBuffer(chunkIndex(pos)));
    }

    @Override
    public boolean doesChunkExist(ChunkPos pos) {
        synchronized (this.lock) {
            return this.chunks[chunkIndex(pos)] != null;
        }
    }

    @Override
    public boolean hasChunk(ChunkPos pos) {
        return this.doesChunkExist(pos);
    }

    @Override
    public void clear(ChunkPos pos) {
        final int idx = chunkIndex(pos);
        synchronized (this.lock) {
            this.chunks[idx] = null;
            this.timestamps[idx] = 0;
            this.dirty = true;
        }
    }

    @Override
    public void flush() throws IOException {
        synchronized (this.lock) {
            if (this.dirty) {
                this.save();
            }
        }
    }

    @Override
    public void close() throws IOException {
        this.flush();
    }

    private final class ChunkBuffer extends ByteArrayOutputStream {
        private final int idx;

        private ChunkBuffer(int idx) {
            this.idx = idx;
        }

        @Override
        public void close() {
            synchronized (LinearV2RegionFile.this.lock) {
                LinearV2RegionFile.this.chunks[this.idx] = this.toByteArray();
                LinearV2RegionFile.this.timestamps[this.idx] = System.currentTimeMillis() / 1000L;
                LinearV2RegionFile.this.dirty = true;
            }
        }
    }
}

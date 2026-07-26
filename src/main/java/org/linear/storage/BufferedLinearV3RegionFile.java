package org.linear.storage;

import net.minecraft.world.level.ChunkPos;
import org.linear.LinearConfig;
import org.linear.storage.util.ByteBufferInputStream;
import org.linear.storage.util.XXHash32;
import org.linear.storage.util.ZstdUtil;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * BufferedLinearV3（{@code r.X.Z.b_linear}）区域文件，移植自 Luminol 的
 * {@code BufferedLinearRegionFile}（见 linear-tools-rs/BufferedLinearRegionFile.java）。
 * <p>
 * 结构：写入先落 {@code .swp} swap 文件（进程内部文件，打开时删除残留、DELETE_ON_CLOSE、
 * 从不跨进程恢复，崩溃安全性完全由 master 承担），由 {@link BufferedLinearFlusher}
 * 后台线程在写入静默一段时间后把脏 bucket 同步进 master；swap 垃圾空间超阈值时自动 compact。
 * master 的 16 个 bucket 懒加载：首次访问某 bucket 才解压进 swap。
 * <p>
 * 与参考实现的差异：
 * <ul>
 *   <li>swap sector 压缩用 zstd level 1 而非 LZ4（swap 是纯内部文件、格式自由，省一个依赖）；
 *       sector 内容为 {@code [u32 rawLen][zstd bytes]}。master 文件格式与参考完全一致。</li>
 *   <li>VarHandle 并发控制换成 AtomicBoolean / AtomicLong。</li>
 *   <li>{@code synced} 初始为 true（参考实现为 false）：只被读过的区域关闭时不重写 master，
 *       只探测过、从未写入的区域不会在磁盘上留下空 master 文件。</li>
 *   <li>单 chunk 上限自定 256 MiB（26.2 原版没有 RegionFile.MAX_CHUNK_SIZE，那是 Paper 加的）。</li>
 * </ul>
 * 锁顺序：bucket.lock → masterFileLock → regionObjectLock，全程不得反向。
 */
public final class BufferedLinearV3RegionFile implements IRegionFile {
    private static final double SWAP_AUTO_COMPACT_PERCENT = 3.0 / 5.0; // 垃圾超活数据 60%
    private static final long SWAP_AUTO_COMPACT_SIZE = 1024 * 1024;    // 且超 1 MiB 才 compact

    private static final long SWAP_SUPER_BLOCK = 0x1145141919810L;
    private static final byte SWAP_VERSION = 0x02;
    private static final int DEFAULT_HASH_SEED = 0x0721;

    private static final long MASTER_SUPER_BLOCK = 0xFFFFDFF7EDDAFD97L;
    private static final byte MASTER_VERSION_V2 = 0x02;
    private static final byte MASTER_VERSION_BUCKET = 0x03;
    private static final long LINEAR_SUPER_BLOCK = 0xc3ff13183cca9d9aL;

    private static final int BUCKET_SHIFT = 6;
    private static final int BUCKET_SIZE = 1 << BUCKET_SHIFT; // 64 chunk / bucket
    private static final int BUCKET_COUNT = 1024 / BUCKET_SIZE; // 16

    private static final long MAX_CHUNK_SIZE = 256L * 1024 * 1024;

    // master 布局：[0,14) 头（魔数8+版本1+zstd等级1+种子4）；[14,142) 16×u64 bucket 偏移表；[142,EOF) 数据
    private static final int MASTER_HEADER_SIZE = 14;
    private static final long POS_TABLE_OFFSET = MASTER_HEADER_SIZE;
    private static final int POS_TABLE_SIZE = BUCKET_COUNT * Long.BYTES;
    private static final long DATA_AREA_OFFSET = POS_TABLE_OFFSET + POS_TABLE_SIZE;

    // swap 布局：魔数8+版本1+种子4+acquiredIndex8 + 1024×Sector(offset8+len8+hasData1)
    private static final int SECTOR_ENCODED_SIZE = Long.BYTES * 2 + 1;
    private static final int SWAP_HEADER_SIZE = 8 + 1 + 4 + 8 + 1024 * SECTOR_ENCODED_SIZE;

    private static final StandardOpenOption[] SWAP_CHANNEL_OPTIONS = {
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ,
            StandardOpenOption.DELETE_ON_CLOSE
    };

    private static final class Bucket {
        final Object lock = new Object();
        final AtomicLong writeEpoch = new AtomicLong();
        final AtomicLong syncedEpoch = new AtomicLong();
        volatile boolean loaded;
    }

    private final Bucket[] buckets = new Bucket[BUCKET_COUNT];

    private final Path masterFilePath;
    private final Path swapFilePath;

    private final ReadWriteLock regionObjectLock = new ReentrantReadWriteLock();
    private final ReadWriteLock masterFileLock = new ReentrantReadWriteLock();
    private Sector[] sectors = new Sector[1024];
    private long currentAcquiredIndex = SWAP_HEADER_SIZE;
    private FileChannel swapFileChannel;

    private final int xxHash32Seed;
    private final byte compressionLevel;
    private final BufferedLinearFlusher flusher;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean beingSynced = new AtomicBoolean(false);
    private final AtomicBoolean synced = new AtomicBoolean(true);
    private final AtomicLong lastWritten = new AtomicLong(System.nanoTime());

    public BufferedLinearV3RegionFile(Path masterFilePath, LinearConfig config,
                                      BufferedLinearFlusher flusher) throws IOException {
        this.masterFilePath = masterFilePath;
        this.swapFilePath = Path.of(masterFilePath + ".swp");
        this.compressionLevel = (byte) config.compressionLevel;

        for (int i = 0; i < this.buckets.length; i++) {
            this.buckets[i] = new Bucket();
        }

        Files.deleteIfExists(this.swapFilePath);
        Files.deleteIfExists(Path.of(this.swapFilePath + ".tmp"));
        Files.deleteIfExists(Path.of(masterFilePath + ".tmp"));

        // 只校验 14 字节头并取出种子，数据区懒加载；写 chunk 校验和时必须用文件头里的种子
        int seed = DEFAULT_HASH_SEED;
        if (Files.isRegularFile(masterFilePath) && Files.size(masterFilePath) > 0) {
            seed = this.validateMasterHeader();
        }
        this.xxHash32Seed = seed;

        this.swapFileChannel = FileChannel.open(this.swapFilePath, SWAP_CHANNEL_OPTIONS);
        for (int i = 0; i < 1024; i++) {
            this.sectors[i] = new Sector(i, SWAP_HEADER_SIZE, 0);
        }

        this.flusher = flusher;
        flusher.addFile(this);
    }

    private int validateMasterHeader() throws IOException {
        try (FileChannel channel = FileChannel.open(this.masterFilePath, StandardOpenOption.READ)) {
            if (channel.size() < MASTER_HEADER_SIZE) {
                throw new IOException("master 文件不足 " + MASTER_HEADER_SIZE + " 字节，已损坏: " + this.masterFilePath);
            }
            final ByteBuffer header = ByteBuffer.allocate(MASTER_HEADER_SIZE);
            readFullyAt(channel, header, 0);
            header.flip();

            final long magic = header.getLong();
            if (magic == LINEAR_SUPER_BLOCK) {
                throw new IOException("这是 linear 文件却使用了 .b_linear 扩展名，请改回 .linear: " + this.masterFilePath);
            }
            if (magic != MASTER_SUPER_BLOCK) {
                throw new IOException("未知魔数 0x" + Long.toHexString(magic) + ": " + this.masterFilePath);
            }
            final byte version = header.get();
            if (version == MASTER_VERSION_V2) {
                throw new IOException("bufferedlinear v2 格式不支持，请先用 linear-tools-rs 转换为 v3: " + this.masterFilePath);
            }
            if (version != MASTER_VERSION_BUCKET) {
                throw new IOException("未知 bufferedlinear 版本 " + version + ": " + this.masterFilePath);
            }
            header.get(); // 压缩等级，写入以配置为准
            return header.getInt();
        }
    }

    // ---------------------------------------------------------------- 基础 IO 工具

    private static void writeFullyAt(FileChannel channel, ByteBuffer buf, long startOffset) throws IOException {
        long offset = startOffset;
        while (buf.hasRemaining()) {
            offset += channel.write(buf, offset);
        }
    }

    private static void readFullyAt(FileChannel channel, ByteBuffer buf, long startOffset) throws IOException {
        long offset = startOffset;
        while (buf.hasRemaining()) {
            final int read = channel.read(buf, offset);
            if (read < 0) {
                throw new EOFException("offset " + offset + " 处意外 EOF");
            }
            offset += read;
        }
    }

    private static int getChunkIndex(int x, int z) {
        return (x & 31) + ((z & 31) << 5);
    }

    /** swap sector 内容：[u32 rawLen][zstd level-1 bytes]。 */
    private static ByteBuffer compressForSwap(byte[] section) {
        final byte[] compressed = ZstdUtil.compress(section, 1, false);
        final ByteBuffer out = ByteBuffer.allocate(4 + compressed.length);
        out.putInt(section.length);
        out.put(compressed);
        out.flip();
        return out;
    }

    private static byte[] decompressFromSwap(ByteBuffer sectorContent) throws IOException {
        if (sectorContent.remaining() < 4) {
            throw new IOException("swap sector 内容截断");
        }
        final int rawLen = sectorContent.getInt();
        final byte[] compressed = new byte[sectorContent.remaining()];
        sectorContent.get(compressed);
        return ZstdUtil.decompress(compressed, rawLen);
    }

    // ---------------------------------------------------------------- bucket 懒加载

    private void ensureBucketLoaded(int chunkIndex) throws IOException {
        final int bucketIndex = chunkIndex >>> BUCKET_SHIFT;
        final Bucket bucket = this.buckets[bucketIndex];

        synchronized (bucket.lock) {
            if (bucket.loaded) {
                return;
            }
            this.loadBucketFromMaster(bucketIndex);
            bucket.loaded = true;
        }
    }

    private void loadBucketFromMaster(int bucketIndex) throws IOException {
        final int beginChunkIndex = bucketIndex << BUCKET_SHIFT;

        this.masterFileLock.readLock().lock();
        try {
            if (!Files.exists(this.masterFilePath)) {
                return;
            }
            try (FileChannel channel = FileChannel.open(this.masterFilePath, StandardOpenOption.READ)) {
                if (channel.size() < DATA_AREA_OFFSET) {
                    return;
                }

                final ByteBuffer header = ByteBuffer.allocate(MASTER_HEADER_SIZE);
                readFullyAt(channel, header, 0);
                header.flip();
                if (header.getLong() != MASTER_SUPER_BLOCK) {
                    throw new IOException("master 魔数非法: " + this.masterFilePath);
                }
                if (header.get() != MASTER_VERSION_BUCKET) {
                    throw new IOException("master 版本非法: " + this.masterFilePath);
                }

                final long[] posTable = readPosTable(channel);
                final long bucketOffset = posTable[bucketIndex];
                if (bucketOffset == 0) {
                    return;
                }

                final ByteBuffer lens = ByteBuffer.allocate(8);
                readFullyAt(channel, lens, bucketOffset);
                lens.flip();
                final int rawLen = lens.getInt();
                final int compressedLen = lens.getInt();
                if (rawLen < 0 || compressedLen < 0) {
                    throw new IOException("bucket " + bucketIndex + " 长度字段非法: " + this.masterFilePath);
                }

                final byte[] compressed = new byte[compressedLen];
                readFullyAt(channel, ByteBuffer.wrap(compressed), bucketOffset + 8);
                final ByteBuffer raw = ByteBuffer.wrap(ZstdUtil.decompress(compressed, rawLen));

                for (int chunkIndex = beginChunkIndex; chunkIndex < beginChunkIndex + BUCKET_SIZE; chunkIndex++) {
                    final int sectionSize = raw.getInt();
                    if (sectionSize <= 0) {
                        continue;
                    }
                    final byte[] section = new byte[sectionSize];
                    raw.get(section);
                    this.writeSectionToSwap(chunkIndex, section, true);
                }
            }
        } finally {
            this.masterFileLock.readLock().unlock();
        }
    }

    private static long[] readPosTable(FileChannel channel) throws IOException {
        final ByteBuffer buf = ByteBuffer.allocate(POS_TABLE_SIZE);
        readFullyAt(channel, buf, POS_TABLE_OFFSET);
        buf.flip();
        final long[] table = new long[BUCKET_COUNT];
        for (int i = 0; i < BUCKET_COUNT; i++) {
            table[i] = buf.getLong();
        }
        return table;
    }

    // ---------------------------------------------------------------- 脏标记 / 同步标记

    private void markBucketDirty(int chunkIndex) {
        this.buckets[chunkIndex >>> BUCKET_SHIFT].writeEpoch.incrementAndGet();
    }

    private void markBucketSynced(int bucketIndex, long syncedEpoch) {
        this.buckets[bucketIndex].syncedEpoch.accumulateAndGet(syncedEpoch, Math::max);
    }

    private void markAsToSync() {
        this.synced.set(false);
        this.lastWritten.set(System.nanoTime());
    }

    boolean markAsBeingSynced() {
        return this.beingSynced.compareAndSet(false, true);
    }

    long getLastWritten() {
        return this.lastWritten.get();
    }

    boolean shouldSync() {
        return !this.synced.get();
    }

    private boolean isClosed() {
        this.regionObjectLock.readLock().lock();
        try {
            return this.closed.get();
        } finally {
            this.regionObjectLock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------- swap 读写

    private void writeSectionToSwap(int index, byte[] section, boolean skipSync) throws IOException {
        final ByteBuffer committed = compressForSwap(section); // 压缩放锁外

        this.regionObjectLock.writeLock().lock();
        try {
            this.sectors[index].store(committed, this.swapFileChannel);
            if (!skipSync) {
                this.markBucketDirty(index);
            }
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }

        if (!skipSync) {
            this.markAsToSync();
        }
    }

    /** 返回解压后的完整 chunk section（u32 nbtLen + u64 时间戳毫秒 + u32 xxh32 + nbt），无数据返回 null。 */
    private byte[] readSwapSection(int index) throws IOException {
        final ByteBuffer raw;
        this.regionObjectLock.readLock().lock();
        try {
            final Sector sector = this.sectors[index];
            if (!sector.hasData()) {
                return null;
            }
            raw = sector.read(this.swapFileChannel);
        } finally {
            this.regionObjectLock.readLock().unlock();
        }
        return decompressFromSwap(raw);
    }

    private void writeChunk(int chunkIndex, byte[] nbt, int len) throws IOException {
        if (len > MAX_CHUNK_SIZE) {
            throw new IOException("chunk 过大：" + len + " 字节，上限 " + MAX_CHUNK_SIZE + ": " + this.masterFilePath);
        }

        final ByteBuffer section = ByteBuffer.allocate(4 + 8 + 4 + len);
        section.putInt(len);
        section.putLong(System.currentTimeMillis());
        section.putInt(XXHash32.hash(nbt, 0, len, this.xxHash32Seed));
        section.put(nbt, 0, len);

        this.writeSectionToSwap(chunkIndex, section.array(), false);
    }

    private void clearChunkData(int index) throws IOException {
        this.ensureBucketLoaded(index);

        this.regionObjectLock.writeLock().lock();
        try {
            this.sectors[index].clear();
            this.markBucketDirty(index);
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }

        this.markAsToSync();
    }

    private boolean hasData(int index) throws IOException {
        this.ensureBucketLoaded(index);

        this.regionObjectLock.readLock().lock();
        try {
            return this.sectors[index].hasData();
        } finally {
            this.regionObjectLock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------- flush / compact

    private void flushInternal() throws IOException {
        final boolean initialSyncRequired;

        this.regionObjectLock.writeLock().lock();
        try {
            if (this.closed.get()) {
                return;
            }

            long liveSize = 0;
            for (final Sector sector : this.sectors) {
                if (sector.hasData()) {
                    liveSize += sector.length;
                }
            }
            final long spareSize = this.currentAcquiredIndex - SWAP_HEADER_SIZE - liveSize;

            final boolean compactRequested = spareSize > SWAP_AUTO_COMPACT_SIZE
                    && (double) spareSize > (double) liveSize * SWAP_AUTO_COMPACT_PERCENT;
            if (compactRequested) {
                this.compactSwapFile();
            }

            // compact 可能耗时，紧随其后的首次同步就不做了；master 已存在时交给后台 flusher
            initialSyncRequired = !Files.exists(this.masterFilePath) && !compactRequested;
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }

        if (initialSyncRequired) {
            this.syncToMasterFile();
        }
    }

    private void writeSwapFileHeaders() throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(SWAP_HEADER_SIZE);
        buffer.putLong(SWAP_SUPER_BLOCK);
        buffer.put(SWAP_VERSION);
        buffer.putInt(this.xxHash32Seed);
        buffer.putLong(this.currentAcquiredIndex);
        for (final Sector sector : this.sectors) {
            sector.encodeInto(buffer);
        }
        buffer.flip();
        writeFullyAt(this.swapFileChannel, buffer, 0);
        this.swapFileChannel.force(true);
    }

    private void recalculateAcquiredIndex() {
        long newValue = SWAP_HEADER_SIZE;
        for (final Sector sector : this.sectors) {
            if (sector.hasData()) {
                newValue = Math.max(newValue, sector.offset + sector.length);
            }
        }
        this.currentAcquiredIndex = newValue;
    }

    private void reopenSwapFileChannel() throws IOException {
        if (this.swapFileChannel.isOpen()) {
            this.swapFileChannel.close();
        }
        this.swapFileChannel = FileChannel.open(this.swapFilePath, SWAP_CHANNEL_OPTIONS);
    }

    /** 调用方必须持有 regionObjectLock 写锁。 */
    private void compactSwapFile() throws IOException {
        this.writeSwapFileHeaders();

        final Sector[] newSectors = new Sector[this.sectors.length];
        for (int i = 0; i < this.sectors.length; i++) {
            final Sector old = this.sectors[i];
            // 无数据的 sector 重置 length=0，保证后续 store 一定走 append，不会覆写别的 sector
            newSectors[i] = old.hasData() ? old : new Sector(i, 0, 0);
        }

        final long newAcquiredIndex;
        final Path targetTemp = Path.of(this.swapFilePath + ".tmp");

        try (FileChannel tempChannel = FileChannel.open(targetTemp,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
            long offsetPointer = SWAP_HEADER_SIZE;
            tempChannel.position(offsetPointer);

            for (final Sector sector : newSectors) {
                if (!sector.hasData()) {
                    continue;
                }
                sector.transferTo(this.swapFileChannel, tempChannel);

                final Sector recalculated = new Sector(sector.index, offsetPointer, sector.length);
                recalculated.hasData = true;
                offsetPointer += sector.length;
                newSectors[sector.index] = recalculated;
            }

            tempChannel.force(true);
            newAcquiredIndex = offsetPointer;
        } catch (Throwable ex) {
            this.recalculateAcquiredIndex();
            Files.deleteIfExists(targetTemp);
            // 可恢复失败：不阻止后续写入
            throw new IOException("compact swap 文件失败: " + this.swapFilePath, ex);
        }

        this.swapFileChannel.close();

        try {
            Files.move(targetTemp, this.swapFilePath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable e) {
            try {
                Files.move(targetTemp, this.swapFilePath, StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable ex) {
                e.addSuppressed(ex);
                Files.deleteIfExists(targetTemp);
                this.recalculateAcquiredIndex();
                this.reopenSwapFileChannel();
                this.markClosed(); // 阻止后续写入与同步
                throw new IOException("替换 swap 文件失败: " + this.swapFilePath, e);
            }
        }

        try {
            this.reopenSwapFileChannel();
            this.sectors = newSectors;
            this.currentAcquiredIndex = newAcquiredIndex;
            this.writeSwapFileHeaders();
        } catch (Throwable ex) {
            // swap 已替换但内存态没跟上，继续写会写坏数据，只能整体关闭
            this.markClosed();
            throw new IOException("compact 后恢复 swap 状态失败: " + this.swapFilePath, ex);
        }
    }

    // ---------------------------------------------------------------- master 同步

    void syncIfNeeded() throws IOException {
        try {
            if (this.isClosed()) {
                return;
            }
            this.syncToMasterFile();
        } finally {
            this.beingSynced.set(false);
        }
    }

    private void syncToMasterFile() throws IOException {
        // CAS 防并发同步；失败还原标记
        if (!this.synced.compareAndSet(false, true)) {
            return;
        }
        try {
            this.writeMainFileBucketed();
        } catch (Throwable e) {
            this.synced.set(false);
            throw new IOException("同步 master 文件失败: " + this.masterFilePath, e);
        }
    }

    private void writeMainFileBucketed() throws IOException {
        final Path tmpFilePath = Path.of(this.masterFilePath + ".tmp");
        final long[] syncedBucketEpochs = new long[BUCKET_COUNT];
        final long[] newPositionTable = new long[BUCKET_COUNT];

        long[] oldPositionTable = null;
        FileChannel oldChannel = null;

        this.masterFileLock.writeLock().lock();
        try {
            if (Files.exists(this.masterFilePath)) {
                try {
                    oldChannel = FileChannel.open(this.masterFilePath, StandardOpenOption.READ);
                    if (oldChannel.size() >= DATA_AREA_OFFSET) {
                        final ByteBuffer header = ByteBuffer.allocate(MASTER_HEADER_SIZE);
                        readFullyAt(oldChannel, header, 0);
                        header.flip();
                        if (header.getLong() == MASTER_SUPER_BLOCK && header.get() == MASTER_VERSION_BUCKET) {
                            oldPositionTable = readPosTable(oldChannel);
                        } else {
                            oldChannel.close();
                            oldChannel = null;
                        }
                    } else {
                        oldChannel.close();
                        oldChannel = null;
                    }
                } catch (Throwable e) {
                    if (oldChannel != null) {
                        try {
                            oldChannel.close();
                        } catch (IOException e2) {
                            e.addSuppressed(e2);
                        }
                    }
                    throw new IOException("读取旧 master 失败: " + this.masterFilePath, e);
                }
            }

            this.regionObjectLock.readLock().lock();
            try (FileChannel outChannel = FileChannel.open(tmpFilePath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

                final ByteBuffer header = ByteBuffer.allocate(MASTER_HEADER_SIZE);
                header.putLong(MASTER_SUPER_BLOCK);
                header.put(MASTER_VERSION_BUCKET);
                header.put(this.compressionLevel);
                header.putInt(this.xxHash32Seed);
                header.flip();
                writeFullyAt(outChannel, header, 0);

                // 偏移表占位，收尾时回填
                writeFullyAt(outChannel, ByteBuffer.allocate(POS_TABLE_SIZE), POS_TABLE_OFFSET);

                long dataOffset = DATA_AREA_OFFSET;

                for (int bucketIndex = 0; bucketIndex < BUCKET_COUNT; bucketIndex++) {
                    final Bucket bucket = this.buckets[bucketIndex];
                    final long bucketWriteEpoch = bucket.writeEpoch.get();
                    final boolean bucketDirty = bucketWriteEpoch != bucket.syncedEpoch.get();

                    if (bucketDirty) {
                        // 不变式：脏 bucket 必然已 loaded（write/clear 前都 ensureBucketLoaded），
                        // 此刻 swap 里就是该 bucket 的完整内容
                        final int baseChunk = bucketIndex << BUCKET_SHIFT;
                        final ByteArrayOutputStream rawBuf = new ByteArrayOutputStream();
                        final DataOutputStream rawOut = new DataOutputStream(rawBuf);
                        boolean hasAny = false;

                        for (int i = 0; i < BUCKET_SIZE; i++) {
                            final byte[] section = this.readSwapSection(baseChunk + i);
                            if (section == null) {
                                rawOut.writeInt(0);
                            } else {
                                rawOut.writeInt(section.length);
                                rawOut.write(section);
                                hasAny = true;
                            }
                        }

                        if (hasAny) {
                            final byte[] raw = rawBuf.toByteArray();
                            final byte[] compressed = ZstdUtil.compress(raw, this.compressionLevel, false);

                            newPositionTable[bucketIndex] = dataOffset;
                            final ByteBuffer bucketBuf = ByteBuffer.allocate(8 + compressed.length);
                            bucketBuf.putInt(raw.length);
                            bucketBuf.putInt(compressed.length);
                            bucketBuf.put(compressed);
                            bucketBuf.flip();
                            writeFullyAt(outChannel, bucketBuf, dataOffset);
                            dataOffset += 8L + compressed.length;
                        }
                        // hasAny == false：偏移表保持 0（全空桶）

                        syncedBucketEpochs[bucketIndex] = bucketWriteEpoch;
                    } else if (oldPositionTable != null && oldPositionTable[bucketIndex] != 0) {
                        // 净桶：从旧 master 原样复制
                        final long oldOffset = oldPositionTable[bucketIndex];
                        final ByteBuffer lens = ByteBuffer.allocate(8);
                        readFullyAt(oldChannel, lens, oldOffset);
                        lens.flip();
                        lens.getInt(); // rawLen
                        final int compressedLen = lens.getInt();

                        final long bucketTotalSize = 8L + compressedLen;
                        newPositionTable[bucketIndex] = dataOffset;
                        outChannel.position(dataOffset);
                        long remaining = bucketTotalSize;
                        long srcPos = oldOffset;
                        while (remaining > 0) {
                            final long transferred = oldChannel.transferTo(srcPos, remaining, outChannel);
                            srcPos += transferred;
                            remaining -= transferred;
                        }
                        dataOffset += bucketTotalSize;
                    }
                }

                final ByteBuffer posTableBuf = ByteBuffer.allocate(POS_TABLE_SIZE);
                for (final long pos : newPositionTable) {
                    posTableBuf.putLong(pos);
                }
                posTableBuf.flip();
                writeFullyAt(outChannel, posTableBuf, POS_TABLE_OFFSET);

                outChannel.force(true);
            } finally {
                this.regionObjectLock.readLock().unlock();
                if (oldChannel != null) {
                    oldChannel.close();
                }
            }

            try {
                Files.move(tmpFilePath, this.masterFilePath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable e) {
                try {
                    Files.move(tmpFilePath, this.masterFilePath, StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable ex) {
                    e.addSuppressed(ex);
                    Files.deleteIfExists(tmpFilePath);
                    throw new IOException("替换 master 文件失败: " + this.masterFilePath, e);
                }
            }
        } finally {
            this.masterFileLock.writeLock().unlock();
        }

        for (int i = 0; i < syncedBucketEpochs.length; i++) {
            if (syncedBucketEpochs[i] != 0L) {
                this.markBucketSynced(i, syncedBucketEpochs[i]);
            }
        }
    }

    // ---------------------------------------------------------------- 关闭

    private void markClosed() throws IOException {
        if (!this.closed.compareAndSet(false, true)) {
            throw new IOException("已经关闭: " + this.masterFilePath);
        }
        this.flusher.removeFile(this);
    }

    private void closeInternal() throws IOException {
        this.syncIfNeeded(); // 最后一次同步

        this.regionObjectLock.writeLock().lock();
        try {
            this.markClosed();
            this.swapFileChannel.close();
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------- IRegionFile

    @Override
    public Path getPath() {
        return this.masterFilePath;
    }

    @Override
    public DataInputStream getChunkDataInputStream(ChunkPos pos) throws IOException {
        final int chunkIndex = getChunkIndex(pos.x(), pos.z());
        this.ensureBucketLoaded(chunkIndex);

        final byte[] section = this.readSwapSection(chunkIndex);
        if (section == null) {
            return null;
        }
        if (section.length < 16) {
            throw new IOException("chunk section 截断（" + section.length + " 字节）: " + this.masterFilePath);
        }

        final ByteBuffer buf = ByteBuffer.wrap(section);
        buf.getInt(); // nbtLen
        buf.getLong(); // 时间戳
        final int expectedHash = buf.getInt();
        final int actualHash = XXHash32.hash(section, 16, section.length - 16, this.xxHash32Seed);
        if (expectedHash != actualHash) {
            // 校验失败必须抛出，阻止加载坏数据
            throw new IOException("chunk " + pos + " xxhash32 校验失败，期望 " + expectedHash
                    + " 实际 " + actualHash + ": " + this.masterFilePath);
        }

        return new DataInputStream(new ByteBufferInputStream(buf));
    }

    @Override
    public DataOutputStream getChunkDataOutputStream(ChunkPos pos) {
        return new DataOutputStream(new ChunkBuffer(pos));
    }

    @Override
    public boolean doesChunkExist(ChunkPos pos) throws IOException {
        return this.hasData(getChunkIndex(pos.x(), pos.z()));
    }

    @Override
    public boolean hasChunk(ChunkPos pos) throws IOException {
        return this.doesChunkExist(pos);
    }

    @Override
    public void clear(ChunkPos pos) throws IOException {
        this.clearChunkData(getChunkIndex(pos.x(), pos.z()));
    }

    @Override
    public void flush() throws IOException {
        this.flushInternal();
    }

    @Override
    public void close() throws IOException {
        this.closeInternal();
    }

    // ---------------------------------------------------------------- 内部类

    /** swap 文件里一个 chunk 的存储段。offset/length/hasData 的读写都在 regionObjectLock 保护下。 */
    private final class Sector {
        final int index;
        long offset;
        long length;
        boolean hasData;

        Sector(int index, long offset, long length) {
            this.index = index;
            this.offset = offset;
            this.length = length;
        }

        void transferTo(FileChannel source, FileChannel target) throws IOException {
            long transferred = 0;
            while (transferred < this.length) {
                transferred += source.transferTo(this.offset + transferred, this.length - transferred, target);
            }
        }

        ByteBuffer read(FileChannel channel) throws IOException {
            final ByteBuffer result = ByteBuffer.allocate((int) this.length);
            readFullyAt(channel, result, this.offset);
            result.flip();
            return result;
        }

        void store(ByteBuffer newData, FileChannel channel) throws IOException {
            final long oldLength = this.length;
            final long newDataLength = newData.remaining();

            this.hasData = true;
            this.length = newDataLength;

            // 新数据放得进原位置就原地写，否则 append 到文件尾
            if (newDataLength <= oldLength) {
                writeFullyAt(channel, newData, this.offset);
                return;
            }

            this.offset = BufferedLinearV3RegionFile.this.currentAcquiredIndex;
            BufferedLinearV3RegionFile.this.currentAcquiredIndex += this.length;
            writeFullyAt(channel, newData, this.offset);
        }

        void encodeInto(ByteBuffer buffer) {
            buffer.putLong(this.offset);
            buffer.putLong(this.length);
            buffer.put((byte) (this.hasData ? 1 : 0));
        }

        void clear() {
            this.hasData = false;
        }

        boolean hasData() {
            return this.hasData;
        }
    }

    private final class ChunkBuffer extends ByteArrayOutputStream {
        private final ChunkPos pos;

        private ChunkBuffer(ChunkPos pos) {
            this.pos = pos;
        }

        @Override
        public void close() throws IOException {
            final int chunkIndex = getChunkIndex(this.pos.x(), this.pos.z());
            BufferedLinearV3RegionFile.this.ensureBucketLoaded(chunkIndex);
            BufferedLinearV3RegionFile.this.writeChunk(chunkIndex, this.buf, this.count);
            BufferedLinearV3RegionFile.this.flushInternal();
        }
    }
}

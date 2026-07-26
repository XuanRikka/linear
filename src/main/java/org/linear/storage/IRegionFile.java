package org.linear.storage;

import net.minecraft.world.level.ChunkPos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 区域文件抽象：与原版 {@link net.minecraft.world.level.chunk.storage.RegionFile}
 * 的公开方法一一对应（方法名、参数、返回值完全一致），
 * 因此通过 mixin 让原版 RegionFile 也实现本接口后，
 * {@code RegionFileStorage} 可以统一操作 anvil / LinearV2 / BufferedLinearV3 三种实现。
 */
public interface IRegionFile extends AutoCloseable {

    Path getPath();

    /** 返回 chunk 的原始（未压缩）NBT 数据流；chunk 不存在时返回 {@code null}。 */
    DataInputStream getChunkDataInputStream(ChunkPos pos) throws IOException;

    /** 返回用于写入 chunk 原始 NBT 数据的输出流，{@code close()} 时提交。 */
    DataOutputStream getChunkDataOutputStream(ChunkPos pos) throws IOException;

    boolean doesChunkExist(ChunkPos pos) throws IOException;

    boolean hasChunk(ChunkPos pos) throws IOException;

    void clear(ChunkPos pos) throws IOException;

    void flush() throws IOException;

    @Override
    void close() throws IOException;
}

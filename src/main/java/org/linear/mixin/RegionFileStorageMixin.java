package org.linear.mixin;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.util.ExceptionCollector;
import net.minecraft.util.FileUtil;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.linear.LinearConfig;
import org.linear.storage.BufferedLinearFlusher;
import org.linear.storage.BufferedLinearV3RegionFile;
import org.linear.storage.IRegionFile;
import org.linear.storage.LinearV2RegionFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接管 {@link RegionFileStorage} 的全部五个公开 IO 方法（read / scanChunk / write / flush / close）。
 * region、entities、poi 的 IO 全部经 IOWorker 走这五个方法，因此一个 mixin 覆盖三者。
 * <p>
 * 区域文件解析顺序：配置格式的文件 → 另一种 linear 文件 → {@code .mca}（原版兜底 + 提示转换）
 * → 按配置格式新建。
 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {
    @Unique
    private static final Logger linear$LOGGER = LoggerFactory.getLogger("linear");
    @Unique
    private static final int linear$MAX_CACHE_SIZE = 256;

    @Shadow
    @Final
    private RegionStorageInfo info;
    @Shadow
    @Final
    private Path folder;
    @Shadow
    @Final
    private boolean sync;

    /** mixin 的字段初始化器不会执行，必须在 {@link #linear$getFile} 里 lazy new。 */
    @Unique
    private LinkedHashMap<Long, IRegionFile> linear$cache;

    @Unique
    private IRegionFile linear$getFile(ChunkPos pos) throws IOException {
        if (this.linear$cache == null) {
            this.linear$cache = new LinkedHashMap<>(64, 0.75f, true);
        }

        final long key = ChunkPos.pack(pos.getRegionX(), pos.getRegionZ());
        final IRegionFile cached = this.linear$cache.get(key);
        if (cached != null) {
            return cached;
        }

        if (this.linear$cache.size() >= linear$MAX_CACHE_SIZE) {
            final Iterator<Map.Entry<Long, IRegionFile>> iterator = this.linear$cache.entrySet().iterator();
            final IRegionFile eldest = iterator.next().getValue();
            iterator.remove();
            eldest.close();
        }

        FileUtil.createDirectoriesSafe(this.folder);

        final LinearConfig config = LinearConfig.get();
        final int regionX = pos.getRegionX();
        final int regionZ = pos.getRegionZ();
        final String baseName = "r." + regionX + "." + regionZ + ".";
        final Path v2Path = this.folder.resolve(baseName + "linear");
        final Path v3Path = this.folder.resolve(baseName + "b_linear");

        final boolean v2Exists = Files.isRegularFile(v2Path);
        final boolean v3Exists = Files.isRegularFile(v3Path);

        final IRegionFile result;
        if (v2Exists || v3Exists) {
            // 两种都在时按配置格式优先
            final boolean preferV2 = config.format == LinearConfig.Format.LINEAR_V2;
            if (v2Exists && (preferV2 || !v3Exists)) {
                result = new LinearV2RegionFile(v2Path, regionX, regionZ, config);
            } else {
                result = new BufferedLinearV3RegionFile(v3Path, config, BufferedLinearFlusher.get());
            }
        } else {
            final Path mcaPath = this.folder.resolve(baseName + "mca");
            if (Files.isRegularFile(mcaPath)) {
                // 混合世界安全兜底：只有 .mca 时回落原版读写，避免静默丢档
                linear$LOGGER.warn("{} 只有 .mca 文件，本区域回落原版格式读写；建议用 linear-tools-rs 转换为 linear 格式",
                        mcaPath);
                result = (IRegionFile) (Object) new RegionFile(this.info, mcaPath, this.folder, this.sync);
            } else if (config.format == LinearConfig.Format.LINEAR_V2) {
                result = new LinearV2RegionFile(v2Path, regionX, regionZ, config);
            } else {
                result = new BufferedLinearV3RegionFile(v3Path, config, BufferedLinearFlusher.get());
            }
        }

        this.linear$cache.put(key, result);
        return result;
    }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void linear$read(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) throws IOException {
        final IRegionFile file = this.linear$getFile(pos);
        try (DataInputStream in = file.getChunkDataInputStream(pos)) {
            if (in == null) {
                cir.setReturnValue(null);
                return;
            }
            cir.setReturnValue(NbtIo.read(in));
        }
    }

    @Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
    private void linear$scanChunk(ChunkPos pos, StreamTagVisitor visitor, CallbackInfo ci) throws IOException {
        final IRegionFile file = this.linear$getFile(pos);
        try (DataInputStream in = file.getChunkDataInputStream(pos)) {
            if (in != null) {
                NbtIo.parse(in, visitor, NbtAccounter.unlimitedHeap());
            }
        }
        ci.cancel();
    }

    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void linear$write(ChunkPos pos, CompoundTag tag, CallbackInfo ci) throws IOException {
        ci.cancel();
        if (SharedConstants.DEBUG_DONT_SAVE_WORLD) {
            return;
        }
        final IRegionFile file = this.linear$getFile(pos);
        if (tag == null) {
            file.clear(pos);
        } else {
            try (DataOutputStream out = file.getChunkDataOutputStream(pos)) {
                NbtIo.write(tag, out);
            }
        }
    }

    @Inject(method = "flush", at = @At("HEAD"), cancellable = true)
    private void linear$flush(CallbackInfo ci) throws IOException {
        if (this.linear$cache != null) {
            final ExceptionCollector<IOException> collector = new ExceptionCollector<>();
            for (final IRegionFile file : this.linear$cache.values()) {
                try {
                    file.flush();
                } catch (IOException e) {
                    collector.add(e);
                }
            }
            collector.throwIfPresent();
        }
        ci.cancel();
    }

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void linear$close(CallbackInfo ci) throws IOException {
        if (this.linear$cache != null) {
            final ExceptionCollector<IOException> collector = new ExceptionCollector<>();
            for (final IRegionFile file : this.linear$cache.values()) {
                try {
                    file.close();
                } catch (IOException e) {
                    collector.add(e);
                }
            }
            this.linear$cache.clear();
            collector.throwIfPresent();
        }
        ci.cancel();
    }
}

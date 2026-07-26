package org.linear.mixin;

import net.minecraft.world.level.chunk.storage.RegionFile;
import org.linear.storage.IRegionFile;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 给原版 {@link RegionFile} 贴上 {@link IRegionFile} 接口。
 * 原版类的公开方法与接口方法签名完全一致，自动满足接口，无需任何方法体；
 * 用于 .mca 兜底路径把原版 RegionFile 当 IRegionFile 使用：
 * {@code (IRegionFile) (Object) new RegionFile(info, mcaPath, folder, sync)}。
 */
@Mixin(RegionFile.class)
public abstract class RegionFileMixin implements IRegionFile {
}

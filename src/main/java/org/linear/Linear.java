package org.linear;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public class Linear implements ModInitializer {

    @Override
    public void onInitialize() {
        // fabric.mod.json 的 breaks 已在启动前拦截；这里兜底防止用 loader 参数绕过依赖检查。
        // C2ME 的 chunk IO 重写（ioSystem.replaceImpl，默认开启）会绕过本 mod 直读写 .mca，
        // linear 世界的区域会被静默视为空 → 地形重新生成，因此必须硬性拒绝共存。
        if (FabricLoader.getInstance().isModLoaded("c2me")) {
            throw new IllegalStateException(
                    "linear 与 C2ME 不兼容：C2ME 的 chunk IO 重写会绕过 linear 存档格式，"
                            + "导致已有 linear 世界地形静默重新生成。请移除 C2ME 或移除 linear。"
                            + "（未来版本计划支持 ioSystem.replaceImpl=false 的共存组合）");
        }

        // 触发配置加载（生成默认配置文件并打印生效配置）
        LinearConfig.get();
    }
}

package org.linear;

import net.fabricmc.api.ModInitializer;

public class Linear implements ModInitializer {

    @Override
    public void onInitialize() {
        // 触发配置加载（生成默认配置文件并打印生效配置）
        LinearConfig.get();
    }
}

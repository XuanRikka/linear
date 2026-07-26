package org.linear;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Linear implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("linear");

    @Override
    public void onInitialize() {
        checkC2meCompat();

        // 触发配置加载（生成默认配置文件并打印生效配置）
        LinearConfig.get();
    }

    /**
     * C2ME 兼容性守卫。
     * <p>
     * C2ME 只有两个模块会绕过本 mod 的存储拦截直接读写 .mca（linear 世界的区域会被
     * 静默视为空 → 地形重新生成）：
     * <ul>
     *   <li>c2me-rewrites-chunkio：config 键 {@code ioSystem.replaceImpl}，默认 true；</li>
     *   <li>c2me-rewrites-chunk-serializer：config 键 {@code ioSystem.gcFreeChunkSerializer}，默认 false。</li>
     * </ul>
     * 两者都关闭时 chunk IO 走原版公开方法，本 mod 完整接管，其余 C2ME 优化正常共存。
     * 判定顺序：反射读模块门控字段（权威，含默认值语义）→ 解析 config/c2me.toml 兜底
     * → 都失败按危险处理拒绝启动（宁可误拦，不可静默丢档）。
     */
    private static void checkC2meCompat() {
        if (!FabricLoader.getInstance().isModLoaded("c2me")) {
            return;
        }

        final Boolean replaceImpl = readC2meModuleEnabled(
                "com.ishland.c2me.rewrites.chunkio.ModuleEntryPoint",
                "ioSystem.replaceImpl", true);
        final Boolean gcFreeSerializer = readC2meModuleEnabled(
                "com.ishland.c2me.rewrites.chunk_serializer.ModuleEntryPoint",
                "ioSystem.gcFreeChunkSerializer", false);

        if (replaceImpl == null || gcFreeSerializer == null) {
            throw new IllegalStateException(
                    "linear 无法确认 C2ME 的 chunk IO 配置（C2ME 内部结构可能已变化）。"
                            + "为避免 linear 存档被静默绕过导致地形重新生成，拒绝启动。"
                            + "请在 config/c2me.toml 中确认 ioSystem.replaceImpl=false 且 "
                            + "gcFreeChunkSerializer=false 后，更新 linear 到适配版本，"
                            + "或移除 C2ME / linear 之一。");
        }
        if (replaceImpl || gcFreeSerializer) {
            throw new IllegalStateException(
                    "linear 与 C2ME 的 chunk IO 重写不兼容：它会绕过 linear 存档格式直接读写 "
                            + ".mca，已有 linear 世界的地形会被静默重新生成。"
                            + "解决办法：编辑 config/c2me.toml，把 [ioSystem] 下的 "
                            + "replaceImpl 设为 false（并保持 gcFreeChunkSerializer=false，默认即是），"
                            + "C2ME 的其余优化不受影响；或移除 C2ME / linear 之一。"
                            + "当前检测值：ioSystem.replaceImpl=" + replaceImpl
                            + ", ioSystem.gcFreeChunkSerializer=" + gcFreeSerializer);
        }
        LOGGER.info("检测到 C2ME 且 chunk IO 处于兼容配置"
                + "（ioSystem.replaceImpl=false, gcFreeChunkSerializer=false），正常共存");
    }

    /**
     * 读 C2ME 模块的门控开关：优先反射 ModuleEntryPoint#enabled（static boolean，
     * 类加载时已解析完 config，语义含默认值），类不存在视为模块未随 jar 分发（=关闭），
     * 反射失败（字段改名等）回落解析 c2me.toml；均失败返回 null 由调用方拒绝启动。
     */
    private static Boolean readC2meModuleEnabled(String className, String tomlKey, boolean defaultValue) {
        try {
            final Class<?> cls = Class.forName(className);
            final Field field = cls.getDeclaredField("enabled");
            field.setAccessible(true);
            return field.getBoolean(null);
        } catch (ClassNotFoundException e) {
            return false; // 模块不在 C2ME 构建里，绕过路径不存在
        } catch (Throwable t) {
            LOGGER.warn("反射读取 {} 失败，回落解析 c2me.toml", className, t);
        }
        return parseC2meToml(FabricLoader.getInstance().getConfigDir().resolve("c2me.toml"),
                tomlKey, defaultValue);
    }

    /**
     * 极简 TOML 解析：只认 {@code [section]} 与 {@code key = true/false} 两种行，
     * 足够覆盖 C2ME ConfigSystem（night-config）写出的格式。文件不存在按 C2ME
     * 默认值处理；解析不出该键也按默认值处理（C2ME 缺键时同样落默认）。
     */
    static Boolean parseC2meToml(Path file, String dottedKey, boolean defaultValue) {
        final int dot = dottedKey.indexOf('.');
        final String section = dottedKey.substring(0, dot);
        final String key = dottedKey.substring(dot + 1);
        try {
            if (!Files.isRegularFile(file)) {
                return defaultValue;
            }
            final List<String> lines = Files.readAllLines(file);
            String currentSection = "";
            for (String line : lines) {
                final String s = stripTomlComment(line).trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (s.startsWith("[") && s.endsWith("]")) {
                    currentSection = s.substring(1, s.length() - 1).trim();
                    continue;
                }
                final int eq = s.indexOf('=');
                if (eq < 0 || !currentSection.equals(section)) {
                    continue;
                }
                final String k = s.substring(0, eq).trim();
                // night-config 可能给键加引号
                final String bare = k.length() >= 2 && k.startsWith("\"") && k.endsWith("\"")
                        ? k.substring(1, k.length() - 1) : k;
                if (!bare.equals(key)) {
                    continue;
                }
                final String v = s.substring(eq + 1).trim();
                // TOML 规范只认小写布尔；"True" 等写法会让 night-config 判整个文件非法
                // 而落回默认值（危险态），宽松解析会漏放，必须严格匹配
                if (v.equals("true") || v.equals("false")) {
                    return Boolean.parseBoolean(v);
                }
                return null; // 键存在但不是合法布尔字面量，视为无法确认
            }
            return defaultValue;
        } catch (Exception e) {
            LOGGER.warn("解析 {} 失败", file, e);
            return null;
        }
    }

    private static String stripTomlComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (c == '#' && !inString) {
                return line.substring(0, i);
            }
        }
        return line;
    }
}

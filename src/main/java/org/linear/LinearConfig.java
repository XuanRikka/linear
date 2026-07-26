package org.linear;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * config/linear.properties 配置。
 * 首次启动时生成默认配置文件，之后按需读取；解析失败的项回落到默认值。
 */
public final class LinearConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("linear");
    private static volatile LinearConfig instance;

    public enum Format {
        LINEAR_V2("linearv2"),
        BUFFERED_LINEAR_V3("bufferedlinearv3");

        public final String id;

        Format(String id) {
            this.id = id;
        }

        static Format byId(String id) {
            for (Format f : values()) {
                if (f.id.equalsIgnoreCase(id)) {
                    return f;
                }
            }
            return null;
        }
    }

    /** 写入新区域时采用的格式（已有文件按魔数自动识别，与该项无关）。 */
    public final Format format;
    /** zstd 压缩等级，1-22。 */
    public final int compressionLevel;
    /** LinearV2 的分桶网格，1/2/4/8/16/32。 */
    public final int gridSize;
    /** LinearV2：脏区域文件多久落盘一次（秒），崩溃兜底；0 = 禁用。 */
    public final int v2FlushIntervalSeconds;
    /** BufferedLinearV3：最后一次写入静默多少秒后由后台线程同步 master 文件。 */
    public final int v3FlushDelaySeconds;
    /** BufferedLinearV3：后台同步线程数。 */
    public final int v3FlushThreads;

    private LinearConfig(Format format, int compressionLevel, int gridSize,
                         int v2FlushIntervalSeconds, int v3FlushDelaySeconds, int v3FlushThreads) {
        this.format = format;
        this.compressionLevel = compressionLevel;
        this.gridSize = gridSize;
        this.v2FlushIntervalSeconds = v2FlushIntervalSeconds;
        this.v3FlushDelaySeconds = v3FlushDelaySeconds;
        this.v3FlushThreads = v3FlushThreads;
    }

    public static LinearConfig get() {
        LinearConfig local = instance;
        if (local == null) {
            synchronized (LinearConfig.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    private static LinearConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("linear.properties");
        Properties props = new Properties();

        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                LOGGER.error("读取 {} 失败，使用默认配置", file, e);
            }
        } else {
            try {
                Files.createDirectories(file.getParent());
                try (OutputStream out = Files.newOutputStream(file)) {
                    out.write(DEFAULT_FILE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                LOGGER.info("已生成默认配置 {}", file);
            } catch (IOException e) {
                LOGGER.error("写入默认配置 {} 失败", file, e);
            }
        }

        Format format = Format.byId(props.getProperty("format", Format.BUFFERED_LINEAR_V3.id).trim());
        if (format == null) {
            LOGGER.warn("format 配置非法：{}，回落到 bufferedlinearv3",
                    props.getProperty("format"));
            format = Format.BUFFERED_LINEAR_V3;
        }

        int level = clamp(parseInt(props, "compression-level", 3), 1, 22, "compression-level");

        int grid = parseInt(props, "grid-size", 8);
        if (grid != 1 && grid != 2 && grid != 4 && grid != 8 && grid != 16 && grid != 32) {
            LOGGER.warn("grid-size 配置非法：{}（允许 1/2/4/8/16/32），回落到 8", grid);
            grid = 8;
        }

        int v2FlushInterval = parseInt(props, "v2-flush-interval-seconds", 60);
        if (v2FlushInterval < 0) {
            LOGGER.warn("v2-flush-interval-seconds = {} 非法（0 表示禁用），按 0 处理", v2FlushInterval);
            v2FlushInterval = 0;
        } else if (v2FlushInterval > 0) {
            v2FlushInterval = clamp(v2FlushInterval, 5, 3600, "v2-flush-interval-seconds");
        }

        int flushDelay = clamp(parseInt(props, "v3-flush-delay-seconds", 5), 1, 600,
                "v3-flush-delay-seconds");
        int flushThreads = clamp(parseInt(props, "v3-flush-threads",
                        Math.max(1, Runtime.getRuntime().availableProcessors() / 4)),
                1, 16, "v3-flush-threads");

        LinearConfig cfg = new LinearConfig(format, level, grid, v2FlushInterval, flushDelay, flushThreads);
        LOGGER.info("linear 配置：format={} compression-level={} grid-size={} "
                        + "v2-flush-interval={}s v3-flush-delay={}s v3-flush-threads={}",
                cfg.format.id, cfg.compressionLevel, cfg.gridSize,
                cfg.v2FlushIntervalSeconds, cfg.v3FlushDelaySeconds, cfg.v3FlushThreads);
        return cfg;
    }

    private static int parseInt(Properties props, String key, int def) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("{} 配置非法：{}，回落到 {}", key, raw, def);
            return def;
        }
    }

    private static int clamp(int value, int min, int max, String key) {
        if (value < min || value > max) {
            int clamped = Math.max(min, Math.min(max, value));
            LOGGER.warn("{} = {} 超出范围 [{}, {}]，取 {}", key, value, min, max, clamped);
            return clamped;
        }
        return value;
    }

    private static final String DEFAULT_FILE = String.join("\n",
            "# linear mod 配置",
            "#",
            "# format: 写入新区域文件时使用的格式，可选：",
            "#   bufferedlinearv3  —— .b_linear，16 bucket 独立 zstd 压缩 + 懒加载 + swap 缓冲（默认）",
            "#   linearv2          —— .linear，xymb LinearV2，整文件按 grid 分桶 zstd 压缩",
            "# 已存在的区域文件按内容自动识别格式读取，与该项无关。",
            "format=bufferedlinearv3",
            "",
            "# zstd 压缩等级（1-22），影响两种格式的 master 文件。",
            "compression-level=3",
            "",
            "# LinearV2 专用：分桶网格大小，允许 1/2/4/8/16/32。1 压缩率最高，32 最低。",
            "grid-size=8",
            "",
            "# BufferedLinearV3 专用：最后一次写入静默多少秒后，后台线程把脏 bucket 同步进 master 文件。",
            "v3-flush-delay-seconds=5",
            "",
            "# BufferedLinearV3 专用：后台同步线程数。",
            "v3-flush-threads=" + Math.max(1, Runtime.getRuntime().availableProcessors() / 4),
            "");
}

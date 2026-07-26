package org.linear.storage;

import org.linear.LinearConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * LinearV2 的周期性落盘兜底（进程级单例）。
 * <p>
 * LinearV2 全内存驻留，正常只在 {@code RegionFileStorage.flush/close} 时落盘，而游戏内
 * Esc 暂停存档不触发 flush——崩溃会丢上次落盘后的全部改动。一个 daemon 调度线程每秒
 * 扫描注册的区域文件，把持续脏了超过 {@code v2-flush-interval-seconds} 的文件在调度线程上
 * 顺序 {@link LinearV2RegionFile#flush()}（整文件重写，顺序执行天然错峰）。
 * 没有修改的文件永远不会被写；与游戏 IO 线程的互斥由 LinearV2RegionFile 内部单锁保证。
 * <p>
 * {@code v2-flush-interval-seconds=0} 时禁用：不启动线程，行为与旧版完全一致。
 */
public final class LinearV2Flusher {
    private static final Logger LOGGER = LoggerFactory.getLogger("linear");
    private static volatile LinearV2Flusher instance;

    public static LinearV2Flusher get() {
        LinearV2Flusher local = instance;
        if (local == null) {
            synchronized (LinearV2Flusher.class) {
                local = instance;
                if (local == null) {
                    local = new LinearV2Flusher(LinearConfig.get());
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Set<LinearV2RegionFile> files = ConcurrentHashMap.newKeySet();
    private final long intervalNanos;

    private LinearV2Flusher(LinearConfig config) {
        this.intervalNanos = TimeUnit.SECONDS.toNanos(config.v2FlushIntervalSeconds);
        if (this.intervalNanos <= 0) {
            LOGGER.info("LinearV2 周期 flush 已禁用（v2-flush-interval-seconds=0），只在退出世界时落盘");
            return;
        }

        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, "linear-v2-flusher");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::scan, 1, 1, TimeUnit.SECONDS);

        LOGGER.info("LinearV2 flusher 已启动：interval={}s", config.v2FlushIntervalSeconds);
    }

    void addFile(LinearV2RegionFile file) {
        if (this.intervalNanos > 0) {
            this.files.add(file);
        }
    }

    void removeFile(LinearV2RegionFile file) {
        this.files.remove(file);
    }

    private void scan() {
        // 任何异常都不能抛出去，否则调度线程会被终止
        try {
            final long now = System.nanoTime();
            for (final LinearV2RegionFile file : this.files) {
                if (!file.isDirtyLongerThan(now, this.intervalNanos)) {
                    continue;
                }
                try {
                    file.flush();
                } catch (Throwable t) {
                    // 失败后把脏龄清零：等满一个完整周期再重试，避免每秒刷错误日志和无谓 IO
                    file.postponePeriodicFlush();
                    LOGGER.error("周期 flush {} 失败，{}s 后重试", file.getPath(),
                            TimeUnit.NANOSECONDS.toSeconds(this.intervalNanos), t);
                }
            }
        } catch (Throwable t) {
            LOGGER.error("linear v2 flusher 调度异常", t);
        }
    }
}

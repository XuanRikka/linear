package org.linear.storage;

import org.linear.LinearConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BufferedLinearV3 的后台同步器（进程级单例）。
 * <p>
 * 一个 daemon 调度线程每秒扫描注册的区域文件：距最后一次写入静默超过
 * {@code v3-flush-delay-seconds} 且有未同步数据的文件，提交给
 * {@code v3-flush-threads} 个 daemon worker 执行 {@link BufferedLinearV3RegionFile#syncIfNeeded()}。
 * 全部线程都是 daemon：JVM 退出不被阻塞，最后一次同步由
 * {@link BufferedLinearV3RegionFile#close()} 保证。
 */
public final class BufferedLinearFlusher {
    private static final Logger LOGGER = LoggerFactory.getLogger("linear");
    private static volatile BufferedLinearFlusher instance;

    public static BufferedLinearFlusher get() {
        BufferedLinearFlusher local = instance;
        if (local == null) {
            synchronized (BufferedLinearFlusher.class) {
                local = instance;
                if (local == null) {
                    local = new BufferedLinearFlusher(LinearConfig.get());
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Set<BufferedLinearV3RegionFile> files = ConcurrentHashMap.newKeySet();
    private final long flushDelayNanos;
    private final ExecutorService workers;

    private BufferedLinearFlusher(LinearConfig config) {
        this.flushDelayNanos = TimeUnit.SECONDS.toNanos(config.v3FlushDelaySeconds);

        final AtomicInteger workerId = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(config.v3FlushThreads, task -> {
            final Thread thread = new Thread(task, "linear-v3-sync-" + workerId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, "linear-v3-flusher");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::scan, 1, 1, TimeUnit.SECONDS);

        LOGGER.info("BufferedLinearV3 flusher 已启动：delay={}s threads={}",
                config.v3FlushDelaySeconds, config.v3FlushThreads);
    }

    void addFile(BufferedLinearV3RegionFile file) {
        this.files.add(file);
    }

    void removeFile(BufferedLinearV3RegionFile file) {
        this.files.remove(file);
    }

    private void scan() {
        // 任何异常都不能抛出去，否则调度线程会被终止
        try {
            final long now = System.nanoTime();
            for (final BufferedLinearV3RegionFile file : this.files) {
                if (file.shouldSync()
                        && now - file.getLastWritten() >= this.flushDelayNanos
                        && file.markAsBeingSynced()) {
                    this.workers.execute(() -> {
                        try {
                            file.syncIfNeeded();
                        } catch (Throwable t) {
                            LOGGER.error("后台同步 {} 失败", file.getPath(), t);
                        }
                    });
                }
            }
        } catch (Throwable t) {
            LOGGER.error("linear flusher 调度异常", t);
        }
    }
}

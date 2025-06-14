package com.example.demo.core.holder;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 扫水时的线程池
 * 单例复用（全局共享），因为扫水需要每0.1秒执行一次的高实时性，所以用单例
 */
@Slf4j
@Component
public class SweepWaterThreadPoolHolder {

    // 联赛级线程池（外层）
    private final ExecutorService leagueExecutor;

    // 事件级线程池（内层）
    private final ExecutorService eventExecutor;

    // 轻量配置线程池
    private final ExecutorService configExecutor;

    public SweepWaterThreadPoolHolder() {
        int cpuCoreCount = Math.min(Runtime.getRuntime().availableProcessors() * 4, 100);

        this.leagueExecutor = new ThreadPoolExecutor(
                cpuCoreCount, 100,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new ThreadFactoryBuilder().setNameFormat("league-pool-%d").build(),
                new ThreadPoolExecutor.AbortPolicy()
        );

        this.eventExecutor = new ThreadPoolExecutor(
                100, 200,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000),
                new ThreadFactoryBuilder().setNameFormat("event-pool-%d").build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        this.configExecutor = new ThreadPoolExecutor(
                8, 16, // 核心线程数和最大线程数
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                new ThreadFactoryBuilder().setNameFormat("config-pool-%d").build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public ExecutorService getLeagueExecutor() {
        return leagueExecutor;
    }

    public ExecutorService getEventExecutor() {
        return eventExecutor;
    }

    public ExecutorService getConfigExecutor() {
        return configExecutor;
    }

    // 优雅关闭线程池示例方法（可在容器关闭时调用）
    @PreDestroy
    public void shutdown() {
        shutdownExecutor(leagueExecutor);
        shutdownExecutor(eventExecutor);
        shutdownExecutor(configExecutor);
    }

    /**
     * 优化线程池关闭逻辑
     * @param executorService
     */
    private void shutdownExecutor(ExecutorService executorService) {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                log.error("任务超时，强制关闭线程池");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}

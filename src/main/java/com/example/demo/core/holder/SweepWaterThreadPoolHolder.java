package com.example.demo.core.holder;

import com.example.demo.common.utils.ThreadPoolMonitor;
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

    // 总控 orchestrator 线程池（用于 autoSweepwater 外层控制任务）
    private final ExecutorService sweepOrchestratorExecutor;

    // 平台用户级线程池（最外层）
    private final ExecutorService userSweepExecutor;
    // 联赛级线程池（外层）
    private final ExecutorService leagueExecutor;

    // 赛事级线程池（内层）
    private final ExecutorService eventExecutor;

    // 球队赔率级线程池（内层）
    private final ExecutorService teamOddsExecutor;

    // 轻量配置线程池
    private final ExecutorService configExecutor;

    private ThreadPoolMonitor orchestratorMonitor;
    private ThreadPoolMonitor userSweepMonitor;
    private ThreadPoolMonitor leagueMonitor;
    private ThreadPoolMonitor eventMonitor;
    private ThreadPoolMonitor oddsMonitor;
    private ThreadPoolMonitor configMonitor;

    public SweepWaterThreadPoolHolder() {
        int cpuCoreCount = Math.min(Runtime.getRuntime().availableProcessors() * 4, 100);

        // ✅ 扫水调度 orchestrator 线程池
        this.sweepOrchestratorExecutor = new ThreadPoolExecutor(
                240, 480,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                new ThreadFactoryBuilder().setNameFormat("sweep-orchestrator-%d").build(),
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );

        // 平台用户扫水主线程池（最外层）
        this.userSweepExecutor = new ThreadPoolExecutor(
                500, 1000,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1500),
                new ThreadFactoryBuilder().setNameFormat("user-sweep-%d").build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 联赛线程池
        this.leagueExecutor = new ThreadPoolExecutor(
                2000, 3000,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000),
                new ThreadFactoryBuilder().setNameFormat("league-pool-%d").build(),
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );

        // 赛事线程池
        this.eventExecutor = new ThreadPoolExecutor(
                3000, 4000,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(5000),
                new ThreadFactoryBuilder().setNameFormat("event-pool-%d").build(),
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );

        // 球队赔率线程池
        this.teamOddsExecutor = new ThreadPoolExecutor(
                800, 1200,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000),
                new ThreadFactoryBuilder().setNameFormat("odds-pool-%d").build(),
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );

        // 获取平台用户软件设置的的线程池
        this.configExecutor = new ThreadPoolExecutor(
                32, 64, // 核心线程数和最大线程数
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                new ThreadFactoryBuilder().setNameFormat("config-pool-%d").build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 监控器初始化
        orchestratorMonitor = new ThreadPoolMonitor("扫水定时任务线程", (ThreadPoolExecutor) sweepOrchestratorExecutor, 30);
        userSweepMonitor = new ThreadPoolMonitor("扫水平台用户线程", (ThreadPoolExecutor) userSweepExecutor, 30);
        leagueMonitor = new ThreadPoolMonitor("扫水联赛列表任务线程", (ThreadPoolExecutor) leagueExecutor, 30);
        eventMonitor = new ThreadPoolMonitor("扫水赛事任务线程", (ThreadPoolExecutor) eventExecutor, 30);
        oddsMonitor = new ThreadPoolMonitor("扫水球队赔率线程", (ThreadPoolExecutor) teamOddsExecutor, 30);
        configMonitor = new ThreadPoolMonitor("扫水基础设置线程", (ThreadPoolExecutor) configExecutor, 30);

        orchestratorMonitor.start();
        userSweepMonitor.start();
        leagueMonitor.start();
        eventMonitor.start();
        oddsMonitor.start();
        configMonitor.start();
    }

    public ExecutorService getSweepOrchestratorExecutor() {
        return sweepOrchestratorExecutor;
    }

    public ExecutorService getUserSweepExecutor() {
        return userSweepExecutor;
    }

    public ExecutorService getLeagueExecutor() {
        return leagueExecutor;
    }

    public ExecutorService getEventExecutor() {
        return eventExecutor;
    }

    public ExecutorService getTeamOddsExecutor() {
        return teamOddsExecutor;
    }

    public ExecutorService getConfigExecutor() {
        return configExecutor;
    }

    // 优雅关闭线程池示例方法（可在容器关闭时调用）
    @PreDestroy
    public void shutdown() {

        // 关闭监控
        orchestratorMonitor.stop();
        userSweepMonitor.stop();
        leagueMonitor.stop();
        eventMonitor.stop();
        oddsMonitor.stop();
        configMonitor.stop();

        // 关闭线程池
        shutdownExecutor(sweepOrchestratorExecutor);
        shutdownExecutor(userSweepExecutor);
        shutdownExecutor(leagueExecutor);
        shutdownExecutor(eventExecutor);
        shutdownExecutor(teamOddsExecutor);
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

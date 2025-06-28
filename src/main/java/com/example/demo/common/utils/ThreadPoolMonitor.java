package com.example.demo.common.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程池状态监控工具类
 * 支持定时输出线程池的关键指标，方便监控线程池健康状态
 */
@Slf4j
public class ThreadPoolMonitor {

    private final ThreadPoolExecutor executor;

    private final ScheduledExecutorService scheduler;

    // 拒绝任务计数
    private final AtomicLong rejectedCount = new AtomicLong(0);
    private final String name;

    /**
     * 构造时传入线程池及监控间隔
     * @param executor 需要监控的线程池
     * @param periodSeconds 监控间隔秒数
     */
    public ThreadPoolMonitor(String name, ThreadPoolExecutor executor, long periodSeconds) {
        this.name = name;
        this.executor = executor;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r ->
                new Thread(r, "ThreadPoolMonitor-" + name));
        // 如果线程池拒绝策略是自定义的，可以在拒绝处理里调用incrementRejectedCount()
    }

    /**
     * 启动监控
     */
    public void start() {
        scheduler.scheduleAtFixedRate(this::printStatus, 0, 30, TimeUnit.SECONDS);
    }

    /**
     * 停止监控
     */
    public void stop() {
        scheduler.shutdownNow();
    }

    /**
     * 拒绝任务计数+1，需在自定义拒绝策略调用
     */
    public void incrementRejectedCount() {
        rejectedCount.incrementAndGet();
    }

    /**
     * 打印线程池状态
     */
    private void printStatus() {
        int poolSize = executor.getPoolSize();                 // 当前线程池线程数
        int activeCount = executor.getActiveCount();           // 活跃线程数
        long completedTaskCount = executor.getCompletedTaskCount();  // 已完成任务数
        int queueSize = executor.getQueue().size();            // 队列长度
        long totalTaskCount = executor.getTaskCount();         // 总任务数（包括已完成）
        long rejectedTasks = rejectedCount.get();

        log.info("线程池状态监控 - [{}] 池大小: {}, 活跃线程: {}, 队列长度: {}, 完成任务数: {}, 总任务数: {}, 拒绝任务数: {}",
                name, poolSize, activeCount, queueSize, completedTaskCount, totalTaskCount, rejectedTasks);
    }
}


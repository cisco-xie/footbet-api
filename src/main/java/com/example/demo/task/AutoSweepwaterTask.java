package com.example.demo.task;

import cn.hutool.core.thread.ThreadUtil;
import com.example.demo.api.AdminService;
import com.example.demo.api.BetService;
import com.example.demo.api.SweepwaterService;
import com.example.demo.config.PriorityTaskExecutor;
import com.example.demo.model.dto.AdminLoginDTO;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;


/**
 * @Description:
 * @Author: 谢诗宏
 * @Date: 2024年11月27日
 * @Version 1.0
 */
@Slf4j
@Component
public class AutoSweepwaterTask {

    @Resource
    private AdminService adminService;

    @Resource
    private SweepwaterService sweepwaterService;

    @Resource
    private BetService betService;

    private static final int MAX_CONCURRENT_SWEEPS = 600;
    // 使用双端队列记录正在处理的任务，先进先出
    private final ConcurrentLinkedDeque<CompletableFuture<Void>> taskQueue =
            new ConcurrentLinkedDeque<>();
    // 高性能并发控制
    private final LongAdder activeTasks = new LongAdder();

    @Async("sweepTaskExecutor") // ✅ 独立线程池执行
    @Scheduled(fixedRate = 500)
    public void autoSweepwater() {
        // 清理已完成的任务
        while (!taskQueue.isEmpty() && (taskQueue.peekFirst().isDone() || Objects.requireNonNull(taskQueue.peekFirst()).isCancelled())) {
            taskQueue.pollFirst();
        }

        // 如果活跃任务过多，移除最老的任务
        if (activeTasks.sum() >= MAX_CONCURRENT_SWEEPS) {
            CompletableFuture<Void> oldestTask = taskQueue.pollFirst();
            if (oldestTask != null && !oldestTask.isDone()) {
                boolean canceled = oldestTask.cancel(true);
                if (canceled) {
                    activeTasks.decrement();
                    log.info("取消最老任务以接收新任务");
                } else {
                    log.info("取消最老任务失败，该任务可能已完成或正在执行");
                }
            }
        }

        CompletableFuture<Void> currentTask = CompletableFuture.runAsync(() -> {
            activeTasks.increment();
            long startTime = System.currentTimeMillis();
            try {
                executeSweepwater();
            } catch (Exception e) {
                log.error("自动扫水执行异常", e);
            } finally {
                activeTasks.decrement();
                long costTime = System.currentTimeMillis() - startTime;
                log.info("本轮扫水耗时: {}ms，当前活跃任务: {}", costTime, activeTasks.sum());
            }
        });

        taskQueue.addLast(currentTask);
    }

    private void executeSweepwater() {
        log.info("开始执行自动扫水...");

        List<AdminLoginDTO> adminUsers = adminService.getUsers(null);
        adminUsers.removeIf(adminUser -> adminUser.getStatus() == 0);
        if (adminUsers.isEmpty()) {
            log.info("没有开启投注的平台用户!!!");
            return;
        }

        List<AdminLoginDTO> sweepwaterUsers = adminUsers.stream()
                .filter(adminUser -> adminUser.getRoles().contains("sweepwater"))
                .toList();
        if (sweepwaterUsers.isEmpty()) {
            log.info("没有扫水的平台用户!!!");
            return;
        }

        adminUsers.removeIf(adminUser -> adminUser.getRoles().contains("sweepwater"));
        if (adminUsers.isEmpty()) {
            log.info("没有开启投注的平台用户!!!");
            return;
        }

        int cpuCoreCount = Runtime.getRuntime().availableProcessors();

        // 使用工作窃取线程池替代固定线程池
        ExecutorService executorAdminUserService = new ForkJoinPool(
                Math.min(adminUsers.size(), cpuCoreCount * 4),
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                null, true
        );

        try {
            List<CompletableFuture<Void>> adminFutures = adminUsers.stream()
                    .map(adminUser -> CompletableFuture.runAsync(() -> {
                        sweepwaterService.sweepwater(adminUser.getUsername(), sweepwaterUsers);
                    }, executorAdminUserService))
                    .toList();

            CompletableFuture.allOf(adminFutures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("自动扫水 执行异常", e);
        } finally {
            PriorityTaskExecutor.shutdownExecutor(executorAdminUserService);
        }
    }

    @Async("unsettledBetTaskExecutor")
    @Scheduled(fixedRate = 3000) // 每 3 秒处理一次所有账户的注单
    public void processUnsettledBets() {
        List<AdminLoginDTO> adminUsers = adminService.getUsers(null);
        adminUsers.removeIf(adminUser -> adminUser.getStatus() == 0);
        // 剔除专门的扫水账号,因为扫水账号不进行投注
        adminUsers.removeIf(adminUser -> adminUser.getRoles().contains("sweepwater"));
        if (adminUsers.isEmpty()) {
            log.info("没有投注的平台用户!!!");
            return;
        }

        int cpuCoreCount = Runtime.getRuntime().availableProcessors();

        // 创建管理所有任务的线程池
        ExecutorService executorAdminUserService = Executors.newFixedThreadPool(Math.min(adminUsers.size(), cpuCoreCount * 2));

        // 存储所有 CompletableFuture
        List<CompletableFuture<Void>> adminFutures = new ArrayList<>();

        for (AdminLoginDTO user : adminUsers) {
            String username = user.getUsername();
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    betService.unsettledBet(username, true);
                } catch (Exception e) {
                    log.warn("【实时】未结注单处理失败 [{}]", username, e);
                }
                try {
                    betService.unsettledBet(username, false);
                } catch (Exception e) {
                    log.warn("【历史】未结注单处理失败 [{}]", username, e);
                }
            }, executorAdminUserService);
            adminFutures.add(future);
        }
        // **等待所有管理员任务完成**
        CompletableFuture.allOf(adminFutures.toArray(new CompletableFuture[0])).join();
        // **确保 executorAdminUserService 正确关闭**
        PriorityTaskExecutor.shutdownExecutor(executorAdminUserService);
    }
}

package com.example.demo.task;

import cn.hutool.core.thread.ThreadUtil;
import com.example.demo.api.AdminService;
import com.example.demo.api.BetService;
import com.example.demo.api.SweepwaterService;
import com.example.demo.config.PriorityTaskExecutor;
import com.example.demo.core.holder.SweepWaterThreadPoolHolder;
import com.example.demo.model.dto.AdminLoginDTO;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
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

    @Resource
    private SweepWaterThreadPoolHolder threadPoolHolder;

    private static final int MAX_CONCURRENT_SWEEPS = 240;
    private final Semaphore sweepPermits = new Semaphore(MAX_CONCURRENT_SWEEPS);

    // 使用双端队列记录正在处理的任务，先进先出
    // private final ConcurrentLinkedDeque<CompletableFuture<Void>> taskQueue = new ConcurrentLinkedDeque<>();
    Deque<Future<?>> taskQueue = new ConcurrentLinkedDeque<>();

    // 高性能并发控制
    private final LongAdder activeTasks = new LongAdder();

    // @Async("sweepTaskExecutor") // ✅ 独立线程池执行
    @Scheduled(fixedRate = 500)
    public void autoSweepwater() {
        // 非阻塞式尝试获取信号量，获取不到就跳过（不再执行新任务）
        if (!sweepPermits.tryAcquire()) {
            log.info("本轮扫水-扫水任务达到最大并发限制，跳过本轮");
            return;
        }
        /*if (activeTasks.sum() >= MAX_CONCURRENT_SWEEPS) {
            log.info("扫水任务达到最大并发限制（{}），跳过本轮提交", MAX_CONCURRENT_SWEEPS);
            return;
        }*/

        // 清理已完成的任务
        /*while (!taskQueue.isEmpty()) {
            Future<?> first = taskQueue.peekFirst();
            if (first == null || first.isDone() || first.isCancelled()) {
                taskQueue.pollFirst(); // 安全移除
            } else {
                break; // 队首是未完成的任务，停止清理
            }
        }

        // 如果活跃任务过多，取消最老的任务
        if (activeTasks.sum() >= MAX_CONCURRENT_SWEEPS) {
            Future<?> oldest = taskQueue.pollFirst();
            if (oldest != null && !oldest.isDone()) {
                oldest.cancel(true);
                activeTasks.decrement();
                log.info("主动取消最老任务");
            }
        }*/
        long submitTime = System.currentTimeMillis();
        threadPoolHolder.getSweepOrchestratorExecutor().submit(() -> {
            long delay = System.currentTimeMillis() - submitTime;
            log.info("本轮扫水-任务正式开始执行，延迟了: {}ms", delay);
            // 可中断
            if (Thread.currentThread().isInterrupted()) {
                log.info("本轮扫水-任务还未开始就被中断，直接退出");
                return;
            }
            long startTime = System.currentTimeMillis();
            try {
                activeTasks.increment(); // ✅ 真正执行前再加
                executeSweepwater();
            } catch (Exception e) {
                log.info("本轮扫水-执行异常", e);
            } finally {
                activeTasks.decrement();
                sweepPermits.release();
                long costTime = System.currentTimeMillis() - startTime;
                log.info("本轮扫水-耗时: {}ms，当前活跃任务: {}", costTime, activeTasks.sum());
            }
        });

        //taskQueue.addLast(future);
    }

    private void executeSweepwater() {
        log.info("开始执行自动扫水...");

        if (Thread.currentThread().isInterrupted()) {
            log.info("executeSweepwater检测到中断，提前退出");
            return;
        }

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

        // 使用工作窃取线程池替代固定线程池
        ExecutorService executorAdminUserService = threadPoolHolder.getUserSweepExecutor();

        try {
            List<CompletableFuture<Void>> adminFutures = adminUsers.stream()
                    .map(adminUser -> {
                        long submitTime = System.currentTimeMillis(); // 提交时记录
                        return CompletableFuture.runAsync(() -> {
                            if (Thread.currentThread().isInterrupted()) {
                                log.info("子任务检测到中断，提前返回");
                                return;
                            }
                            long startTime = System.currentTimeMillis(); // 正式开始执行时间
                            long delay = startTime - submitTime; // 延迟时间

                            try {
                                log.info("本轮扫水-用户:{} 任务延迟启动: {}ms", adminUser.getUsername(), delay);

                                long sweepStart = System.currentTimeMillis();
                                sweepwaterService.sweepwater(adminUser.getUsername(), sweepwaterUsers);
                                long cost = System.currentTimeMillis() - sweepStart;

                                log.info("本轮扫水-用户:{} 扫水任务耗时: {}ms", adminUser.getUsername(), cost);
                            } catch (Exception e) {
                                log.error("本轮扫水-用户:{} 执行 sweepwater 异常", adminUser.getUsername(), e);
                            }
                        }, executorAdminUserService);
                    })
                    .toList();

            CompletableFuture.allOf(adminFutures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("自动扫水 执行异常", e);
        } finally {
            // 复用线程池无需关闭
            // PriorityTaskExecutor.shutdownExecutor(executorAdminUserService);
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

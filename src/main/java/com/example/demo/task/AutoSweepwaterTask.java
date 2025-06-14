package com.example.demo.task;

import cn.hutool.core.thread.ThreadUtil;
import com.example.demo.api.AdminService;
import com.example.demo.api.BetService;
import com.example.demo.api.SweepwaterService;
import com.example.demo.config.PriorityTaskExecutor;
import com.example.demo.model.dto.AdminLoginDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    private final AtomicInteger concurrentTasks = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Async("sweepTaskExecutor") // ✅ 独立线程池执行
    // 上一次任务完成后再延迟 x 秒执行
    @Scheduled(fixedDelay = 500)
    public void autoSweepwater() {
        /*if (!running.compareAndSet(false, true)) {
            log.warn("autoSweepwater 上次任务未完成，跳过本次");
            return;
        }*/

        if (concurrentTasks.get() > 60) {
            log.warn("当前并发任务过多({})，跳过本次触发", concurrentTasks.get());
            return;
        }
        concurrentTasks.incrementAndGet();
        long startTime = System.currentTimeMillis();
        try {
            log.info("开始执行 自动扫水...");

            List<AdminLoginDTO> adminUsers = adminService.getUsers(null);
            adminUsers.removeIf(adminUser -> adminUser.getStatus() == 0);
            if (adminUsers.isEmpty()) {
                log.info("没有开启投注的平台用户!!!");
                return;
            }
            // 单独提取扫水账号列表
            List<AdminLoginDTO> sweepwaterUsers = adminUsers.stream()
                    .filter(adminUser -> adminUser.getRoles().contains("sweepwater"))
                    .toList();
            if (sweepwaterUsers.isEmpty()) {
                log.info("没有扫水的平台用户!!!");
                return;
            }
            // 剔除专门的扫水账号
            adminUsers.removeIf(adminUser -> adminUser.getRoles().contains("sweepwater"));
            if (adminUsers.isEmpty()) {
                log.info("没有开启投注的平台用户!!!");
                return;
            }

            int cpuCoreCount = Runtime.getRuntime().availableProcessors();

            // 创建管理所有任务的线程池
            ExecutorService executorAdminUserService = Executors.newFixedThreadPool(Math.min(adminUsers.size(), cpuCoreCount * 2));

            // 存储所有 CompletableFuture
            List<CompletableFuture<Void>> adminFutures = new ArrayList<>();
            List<CompletableFuture<Void>> unsettledFutures = new CopyOnWriteArrayList<>();

            for (AdminLoginDTO adminUser : adminUsers) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    // 执行扫水
                    sweepwaterService.sweepwater(adminUser.getUsername(), sweepwaterUsers);
                    // 执行下注
                    // betService.bet(adminUser.getUsername());
                    // 获取盘口实时未结注单并加以保存
                    unsettledFutures.add(CompletableFuture.runAsync(() ->
                        betService.unsettledBet(adminUser.getUsername(), true)
                    ));
                    // 获取盘口历史未结注单并加以保存
                    unsettledFutures.add(CompletableFuture.runAsync(() ->
                        betService.unsettledBet(adminUser.getUsername(), false)
                    ));
                }, executorAdminUserService);
                adminFutures.add(future);
            }

            // **等待所有管理员任务完成**
            CompletableFuture.allOf(adminFutures.toArray(new CompletableFuture[0])).join();
            // **确保 executorAdminUserService 正确关闭**
            PriorityTaskExecutor.shutdownExecutor(executorAdminUserService);

            // 不阻塞扫水主流程打印注单任务完成情况
            CompletableFuture.allOf(unsettledFutures.toArray(new CompletableFuture[0]))
                    .whenComplete((v, e) -> {
                        if (e != null) {
                            log.warn("注单任务部分执行失败", e);
                        } else {
                            log.info("所有注单任务执行完毕");
                        }
                    });
        } catch (Exception e) {
            log.error("自动扫水 执行异常", e);
        } finally {
            long endTime = System.currentTimeMillis();
            long costTime = endTime - startTime;
            log.info("此轮自动扫水任务执行花费 {}ms", costTime);
            running.set(false);
            // **释放并发任务数**
            concurrentTasks.decrementAndGet();
        }
    }
}

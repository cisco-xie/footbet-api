package com.example.demo.task;

import com.example.demo.api.AdminService;
import com.example.demo.api.BetService;
import com.example.demo.api.SweepwaterService;
import com.example.demo.config.PriorityTaskExecutor;
import com.example.demo.model.dto.AdminLoginDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


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

    // 上一次任务完成后再延迟 5 秒执行
    @Scheduled(fixedDelay = 4000)
    public void autoSweepwater() {
        long startTime = System.currentTimeMillis();
        try {
            log.info("开始执行 自动扫水...");

            List<AdminLoginDTO> adminUsers = adminService.getUsers(null);
            adminUsers.removeIf(adminUser -> adminUser.getStatus() == 0);
            if (adminUsers.isEmpty()) {
                log.info("没有开启投注的平台用户!!!");
                return;
            }
            int cpuCoreCount = Runtime.getRuntime().availableProcessors();

            // 创建管理所有任务的线程池
            ExecutorService executorAdminUserService = Executors.newFixedThreadPool(Math.min(adminUsers.size(), cpuCoreCount * 2));

            // 存储所有 CompletableFuture
            List<CompletableFuture<Void>> adminFutures = new ArrayList<>();

            for (AdminLoginDTO adminUser : adminUsers) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    // 执行扫水
                    sweepwaterService.sweepwater(adminUser.getUsername());
                    // 执行下注
                    // betService.bet(adminUser.getUsername());
                    // 获取盘口实时未结注单并加以保存
                    betService.unsettledBet(adminUser.getUsername(), true);
                    // 获取盘口历史未结注单并加以保存
                    betService.unsettledBet(adminUser.getUsername(), false);
                }, executorAdminUserService);
                adminFutures.add(future);
            }

            // **等待所有管理员任务完成**
            CompletableFuture.allOf(adminFutures.toArray(new CompletableFuture[0])).join();
            // **确保 executorAdminUserService 正确关闭**
            PriorityTaskExecutor.shutdownExecutor(executorAdminUserService);
        } catch (Exception e) {
            log.error("自动扫水 执行异常", e);
        } finally {
            long endTime = System.currentTimeMillis();
            long costTime = (endTime - startTime) / 1000;
            log.info("此轮自动扫水任务执行花费 {}s", costTime);
            if (costTime > 20) {
                log.warn("自动扫水 执行时间过长");
            }
        }
    }
}

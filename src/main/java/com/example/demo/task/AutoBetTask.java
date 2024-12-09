package com.example.demo.task;

import com.example.demo.api.FalaliApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;


/**
 * @Description:
 * @Author: 谢诗宏
 * @Date: 2024年11月27日
 * @Version 1.0
 */
@Slf4j
@Component
public class AutoBetTask {

    private boolean isRunning = false;
    @Resource
    private FalaliApi falaliApi;

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);  // 设置线程池大小
        scheduler.setThreadNamePrefix("autoBet-task-");
        scheduler.initialize();
        return scheduler;
    }

    // 上一次任务完成后再延迟 10 秒执行
    @Scheduled(fixedDelay = 10000)
    public void bet() {
        long startTime = System.currentTimeMillis();

        // 获取当前小时
        int currentHour = java.time.LocalTime.now().getHour();
        // 如果当前时间在 6 点到 7 点之间，跳过执行
        if (currentHour == 6) {
            log.info("当前时间在 6 点到 7 点之间，跳过本次执行...");
            return;
        }

        if (isRunning) {
            log.info("上一轮任务还在执行，跳过...");
            return;
        }
        isRunning = true;
        try {
            log.info("开始执行 自动下注...");
            falaliApi.autoBetCompletableFuture();
        } catch (Exception e) {
            log.error("autoBet 执行异常", e);
        } finally {
            long endTime = System.currentTimeMillis();
            long costTime = (endTime - startTime) / 1000;
            log.info("此轮下注任务执行花费{}s", costTime);
            if (costTime > 10) {
                log.warn("autoBet 执行时间过长，可能导致任务重叠");
            }
            isRunning = false;  // 任务结束后重置标志位
        }
    }


}

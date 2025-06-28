package com.example.demo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig implements SchedulingConfigurer {

    @Bean("loginTaskExecutor")
    public Executor loginTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("login-task-");
        executor.initialize();
        return executor;
    }

    @Bean("sweepTaskExecutor")
    public Executor sweepTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int cores = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(cores * 10);       // 480
        executor.setMaxPoolSize(cores * 20);        // 960
        executor.setQueueCapacity(2000);           // 防止任务排队阻塞
        executor.setThreadNamePrefix("sweep-task-");
        // ✅ 丢弃最旧任务，接受新任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setAllowCoreThreadTimeOut(true);
        // 线程空闲60秒后回收
        executor.setKeepAliveSeconds(60);
        executor.initialize();
        return executor;
    }

    @Bean("unsettledBetTaskExecutor")
    public Executor unsettledBetTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(64);
        executor.setMaxPoolSize(128);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("unsettled-task-");
        // 线程空闲60秒后回收
        executor.setKeepAliveSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * ✅ 替换 Spring 默认的 Scheduled 任务线程池
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        int cores = Runtime.getRuntime().availableProcessors();
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(cores * 10);       // 480
        scheduler.setThreadNamePrefix("scheduled-task-");
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        scheduler.initialize();

        registrar.setTaskScheduler(scheduler);
    }
}



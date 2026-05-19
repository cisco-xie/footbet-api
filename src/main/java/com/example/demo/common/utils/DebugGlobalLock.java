package com.example.demo.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 自定义全局锁
 */
@Slf4j
@Component
public class DebugGlobalLock {
    private static final ReentrantLock GLOBAL_LOCK = new ReentrantLock();
    private static final Condition CONDITION = GLOBAL_LOCK.newCondition();
    private static volatile boolean debugMode = false;
    private static volatile int waitingThreads = 0;

    /**
     * 进入调试模式 - 阻塞所有线程
     */
    public static void enterDebugMode() {
        debugMode = true;
        log.warn("========== 进入全局调试模式，所有请求将被阻塞 ==========");
    }

    /**
     * 退出调试模式 - 唤醒所有线程
     */
    public static void exitDebugMode() {
        GLOBAL_LOCK.lock();
        try {
            debugMode = false;
            CONDITION.signalAll();
            log.warn("========== 退出全局调试模式，恢复所有请求 ==========，共唤醒 {} 个线程", waitingThreads);
            waitingThreads = 0;
        } finally {
            GLOBAL_LOCK.unlock();
        }
    }

    /**
     * 检查是否需要等待（在需要全局暂停处调用）
     */
    public static void waitIfDebugMode() {
        if (!debugMode) {
            return;
        }

        GLOBAL_LOCK.lock();
        try {
            waitingThreads++;
            log.info("线程 [{}] 进入调试等待队列，当前等待线程数: {}",
                    Thread.currentThread().getName(), waitingThreads);

            while (debugMode) {
                try {
                    CONDITION.await(10, TimeUnit.MINUTES); // 最多等待10分钟
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("线程 [{}] 等待被中断", Thread.currentThread().getName());
                    break;
                }
            }
        } finally {
            waitingThreads--;
            GLOBAL_LOCK.unlock();
        }
    }
}
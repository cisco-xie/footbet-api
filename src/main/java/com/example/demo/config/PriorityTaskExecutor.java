package com.example.demo.config;

import java.util.concurrent.*;

public class PriorityTaskExecutor {
    // 定义优先级的任务
    public static class PriorityTask implements Runnable, Comparable<PriorityTask> {
        private final Runnable task;
        private final int priority; // 优先级，数字越小优先级越高

        public PriorityTask(Runnable task, int priority) {
            this.task = task;
            this.priority = priority;
        }

        @Override
        public void run() {
            task.run();
        }

        @Override
        public int compareTo(PriorityTask o) {
            // 按优先级进行排序
            return Integer.compare(this.priority, o.priority);
        }
    }

    // 使用优先级队列的线程池
    public static ExecutorService createPriorityThreadPool(int poolSize) {
        // 创建带优先级队列的线程池
        BlockingQueue<Runnable> queue = new PriorityBlockingQueue<>();
        return new ThreadPoolExecutor(
                poolSize, poolSize, 60L, TimeUnit.SECONDS, queue, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public static void main(String[] args) {
        ExecutorService executorService = createPriorityThreadPool(10);

        // 创建高优先级的任务
        Runnable task1 = () -> {
            System.out.println("Task 1 - High Priority");
        };

        // 创建低优先级的任务
        Runnable task2 = () -> {
            System.out.println("Task 2 - Low Priority");
        };

        // 提交任务到线程池
        executorService.submit(new PriorityTask(task1, 1)); // 高优先级任务
        executorService.submit(new PriorityTask(task2, 2)); // 低优先级任务
    }
}


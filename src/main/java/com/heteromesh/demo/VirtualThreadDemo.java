package com.heteromesh.demo;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.stream.IntStream;

public class VirtualThreadDemo {
    public static void main(String[] args) {
        System.out.println("虚拟线程Demo");

        int taskCount = 100000;
        try(ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            runTest(virtualExecutor, taskCount);
        }

        // 底层挂载卸载原理
        virtualThreadUnderTheHood();
    }

    private static void runTest(ExecutorService executor, int taskCount) {
        Instant start = Instant.now();

        IntStream.range(0, taskCount).forEach(i -> {
            executor.submit(() -> {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        });

        // 关闭线程池并等待所有任务完成
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        Instant finish = Instant.now();
        long timeElapsed = Duration.between(start, finish).toMillis();
        System.out.println("✅ 执行 " + taskCount + " 个任务总耗时: " + timeElapsed + " 毫秒 (" + (timeElapsed / 1000.0) + " 秒)");
    }

    private static void virtualThreadUnderTheHood() {
        // 准备5个虚拟线程来观察
        int threadCount = 5;
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int taskId = i;

            Thread.ofVirtual().name("VT-轮子-" + taskId)
                    .start(() -> {
                        Thread current = Thread.currentThread();
                        System.out.println("[阻塞前]" + current.getName() + "寄生在载体线程 -> "
                                + getCarrierThreadName(current));
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        // 3. 阻塞后的状态（触发Mount重新挂载）
                        // 睡眠结束后，JVM会把对立的调用栈拿出来，随便找个有空的载体线程挂载回去继续跑
                        Thread afterWakeup = Thread.currentThread();
                        System.out.println("[唤醒后]" + afterWakeup.getName() +
                        "漂移到了载体线程 -> " + getCarrierThreadName(afterWakeup));
                        latch.countDown();
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("--------------实验结束-------------------");
    }

    private static String getCarrierThreadName(Thread t) {
        String threadStr = t.toString();
        int atIndex = threadStr.indexOf('@');
        if (atIndex != -1) {
            return threadStr.substring(atIndex + 1);
        }

        return "Unknown";
    }
}

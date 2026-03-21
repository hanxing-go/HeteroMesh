package com.heteromesh.demo;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPoolBestPracticeDemo {
    public static void main(String[] args) {
        // 自定义线程工厂, 线程工厂是java并发包中的一个核心接口，核心作用是统一创建线程的逻辑，让线程的创建。命名、优先级、是否守护线程等配置可定制化。
        // 这样代替了new Thread()的硬编码方式。
        ThreadFactory testThreadFactory = new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "Restaurant-Worker-" + threadNumber.getAndIncrement());
            }
        };

        // 2. 手动配置7大核心参数创建线程池
        ThreadPoolExecutor restaurantPool = new ThreadPoolExecutor(
                200,                                      // 1. corePoolSize: 一直存在的线程数
                300,                                                 // 2. maximumPoolSiz: 线程池允许的最大线程数
                10,                                                // 3. keepAliveTime: 非核心线程的存在时间
                TimeUnit.SECONDS,                                  // 4. unit: 单位，秒
                new ArrayBlockingQueue<>(10),              // 5. workQueue: 工作队列，存放通过execute方法提交的Runnable类型任务
                testThreadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()          // 6. handler: 拒绝策略，线程数达到上线且队列已满，导致任务执行被阻塞时采用的策略.
        );

        // 3. 模拟餐厅迎客
        // 模拟 16 个客人同时涌入餐厅 (任务提交)
        //  2个正式工 + 10把椅子 = 12。剩下4个会触发创建临时工。
        //  因为 16 > (最大线程数5 + 队列10)，所以会有一个被main线程服务。
        System.out.println("开门迎客");
        for (int i = 1; i <= 10000; i++) {
            int customerId = i;

            restaurantPool.execute(() -> {
                System.out.println(Thread.currentThread().getName() + "正在服务客人:" + customerId);
                // 模拟客人吃饭时间
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        // 如何优雅的关闭线程池，如果只运行上面的代码我们发现程序没有运行结束
        // restaurantPool.shutdown();

        // 给线程时间去完成任务
        try {
            if (!restaurantPool.awaitTermination(60, TimeUnit.SECONDS)) {
                System.out.println("打烊了，赶人");
                restaurantPool.shutdown();
            }
        } catch (InterruptedException e) {
            restaurantPool.shutdown();
        }
    }
}

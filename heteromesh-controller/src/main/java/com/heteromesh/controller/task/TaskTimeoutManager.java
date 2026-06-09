package com.heteromesh.controller.task;

import com.heteromesh.task.TaskMetadata;
import com.heteromesh.task.TaskStore;

import java.util.Map;
import java.util.concurrent.*;

public class TaskTimeoutManager implements AutoCloseable{
    private final TaskStore taskStore;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();

    public TaskTimeoutManager(TaskStore taskStore) {
        this.taskStore = taskStore;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void registerTimeout(TaskMetadata task) {
        // 1. 读取 task.getRequest().getTimeoutMs()
        String taskId = task.getTaskId();
        long timeoutMs = task.getRequest().getTimeoutMs();
        // 2. timeoutMs <= 0 时直接返回
        if (timeoutMs <= 0) {
            return;
        }
        cancelTimeout(taskId);
        //到点后调用 taskStore.timeout(...)
        ScheduledFuture<?> future = scheduler.schedule(() -> {
                    try {
                        taskStore.timeout(taskId, "Task timed out after " + timeoutMs + " ms");
                    } catch (IllegalStateException e) {
                        // 任务可能已经 SUCCEEDED / FAILED / CANCELED 了
                        // 这种情况说明超时任务晚到了，安全忽略即可
                    } finally {
                        timeoutTasks.remove(taskId);
                    }
                }, timeoutMs, TimeUnit.MILLISECONDS);
        //如果同一个 taskId 已经注册过，先取消旧的
        timeoutTasks.put(taskId, future);
    }

    public boolean cancelTimeout(String taskId) {
        // 取消超时任务，如果没有取消，这个future还会在后台跑
        ScheduledFuture<?> future = timeoutTasks.remove(taskId);

        if (future == null) {
            return false;
        }

        return future.cancel(false);
    }

    @Override
    public void close() throws Exception {
        // 取消所有未触发的 timeout 任务
        for (ScheduledFuture<?> future : timeoutTasks.values()) {
            future.cancel(false);
        }

        timeoutTasks.clear();
        // 关闭 scheduler
        scheduler.shutdownNow();
    }
}

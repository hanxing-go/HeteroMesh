package com.heteromesh.controller.scheduler;

import com.heteromesh.task.TaskMetadata;
import com.heteromesh.task.TaskResult;
import com.heteromesh.task.TaskStatus;

public class TaskRetryPolicy {
    private final int maxAttempts;

    public TaskRetryPolicy(int maxAttempts) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public boolean canRetry(TaskMetadata metadata, TaskResult failedResult) {
        TaskStatus status = metadata.getStatus();
//        1. task 还不是终态
        //        2. result 是可重试失败
//        3. task.retryCount < maxAttempts
        if (! status.isTerminal() && isRetryable(failedResult) && metadata.getRetryCount() < maxAttempts
        && metadata.getStatus() == TaskStatus.RUNNING) {
            return true;
        }

        return false;
    }

    public boolean isRetryable(TaskResult result) {
        // TODO: 只有 FAILED 结果可以触发重试, SUCCEEDED / TIMEOUT / CANCELED 都不触发重试
        if (result != null && result.getStatus() == TaskStatus.FAILED) {
            return true;
        }
        return false;
    }
}

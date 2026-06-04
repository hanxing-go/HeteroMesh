package com.heteromesh.task;


import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller 维护的任务元数据。
 *
 * TaskRequest 是用户输入，TaskMetadata 是系统内部对任务生命周期的记录。
 * TaskMetadata 用来记录系统怎么调度、执行、记录这个任务
 */
@Data
public class TaskMetadata {
    private final String taskId;
    private final TaskRequest request;

    private TaskStatus status = TaskStatus.SUBMITTED;
    private String assignedWorkerId;

    private int retryCount = 0;
    private long createdAt = System.currentTimeMillis();
    private long updatedAt = createdAt;

    private TaskResult result;

    private final List<String> attemptedWorkers = new ArrayList<>();

    public TaskMetadata(String taskId, TaskRequest request) {
        this.taskId = taskId;
        this.request = request;
    }

    public void markUpdated() {
        this.updatedAt = System.currentTimeMillis();
    }
}

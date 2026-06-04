package com.heteromesh.worker;

import com.heteromesh.task.TaskRequest;
import com.heteromesh.task.TaskResult;

public interface TaskExecutor {
    TaskResult execute(TaskRequest request);
}

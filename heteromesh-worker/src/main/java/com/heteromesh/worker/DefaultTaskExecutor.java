package com.heteromesh.worker;

import com.heteromesh.task.TaskRequest;
import com.heteromesh.task.TaskResult;
import com.heteromesh.task.TaskStatus;

/*
* TODO: 后续待实现
*  TaskExecutor
    ↑
    * TaskExecutorRouter
        ├── ImageProcessExecutor
        ├── TextGenerationExecutor
        └── VideoTranscodeExecutor
*/

public class DefaultTaskExecutor implements TaskExecutor{
    private final String nodeId;

    public DefaultTaskExecutor(String nodeId) {
        this.nodeId = nodeId;
    }

    @Override
    public TaskResult execute(TaskRequest request) {
        long startedAt = System.currentTimeMillis();

        try {
            String output = "Task" + request.getTaskId()
                    + "executed by" + nodeId
                    + ", type = " + request.getTaskType()
                    + ", payload = " + request.getPayload();

            return new TaskResult(request.getTaskId(),
                    TaskStatus.SUCCEEDED,
                    output,
                    null,
                    startedAt,
                    System.currentTimeMillis(),
                    this.nodeId);
        } catch (Exception e) {
            return new TaskResult(request.getTaskId(),
                    TaskStatus.FAILED,
                    null,
                    e.getMessage(),
                    startedAt,
                    System.currentTimeMillis(),
                    this.nodeId);
        }


    }
}

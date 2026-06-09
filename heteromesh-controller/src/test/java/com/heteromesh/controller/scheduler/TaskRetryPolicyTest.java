package com.heteromesh.controller.scheduler;

import com.heteromesh.task.TaskMetadata;
import com.heteromesh.task.TaskRequest;
import com.heteromesh.task.TaskResult;
import com.heteromesh.task.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskRetryPolicyTest {

    @Test
    void shouldRetryFailedResultBeforeMaxAttempts() {
        TaskRetryPolicy policy = new TaskRetryPolicy(3);
        TaskMetadata task = task("task-retry", TaskStatus.RUNNING, 1);
        TaskResult failed = result("task-retry", TaskStatus.FAILED);

        assertTrue(policy.canRetry(task, failed));
    }

    @Test
    void shouldNotRetryWhenMaxAttemptsReached() {
        TaskRetryPolicy policy = new TaskRetryPolicy(3);
        TaskMetadata task = task("task-max", TaskStatus.RUNNING, 3);
        TaskResult failed = result("task-max", TaskStatus.FAILED);

        assertFalse(policy.canRetry(task, failed));
    }

    @Test
    void shouldNotRetrySuccessfulResult() {
        TaskRetryPolicy policy = new TaskRetryPolicy(3);
        TaskMetadata task = task("task-success", TaskStatus.RUNNING, 1);
        TaskResult success = result("task-success", TaskStatus.SUCCEEDED);

        assertFalse(policy.canRetry(task, success));
    }

    @Test
    void shouldNotRetryNonRunningTask() {
        TaskRetryPolicy policy = new TaskRetryPolicy(3);
        TaskMetadata task = task("task-dispatching", TaskStatus.DISPATCHING, 1);
        TaskResult failed = result("task-dispatching", TaskStatus.FAILED);

        assertFalse(policy.canRetry(task, failed));
    }

    @Test
    void shouldRejectInvalidMaxAttempts() {
        assertThrows(IllegalArgumentException.class, () -> new TaskRetryPolicy(0));
        assertThrows(IllegalArgumentException.class, () -> new TaskRetryPolicy(-1));
    }

    private TaskMetadata task(String taskId, TaskStatus status, int retryCount) {
        TaskMetadata metadata = new TaskMetadata(taskId, request(taskId));
        metadata.setStatus(status);
        metadata.setRetryCount(retryCount);
        return metadata;
    }

    private TaskRequest request(String taskId) {
        return new TaskRequest(
                taskId,
                "TEXT_GENERATION",
                "hello",
                Map.of("gpuType", "RTX 4090"),
                30_000
        );
    }

    private TaskResult result(String taskId, TaskStatus status) {
        return new TaskResult(
                taskId,
                status,
                status == TaskStatus.SUCCEEDED ? "ok" : null,
                status == TaskStatus.FAILED ? "worker failed" : null,
                100,
                200,
                "worker-a"
        );
    }
}

package com.heteromesh.task;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryTaskStore 的任务超时 / 取消测试。
 *
 * 这组测试只关注仓库层的生命周期规则：
 * 1. timeout 应该把未完成任务推进到 TIMEOUT
 * 2. cancel 应该把未完成任务推进到 CANCELED
 * 3. 已经进入终态的任务不能被后到的 Worker 结果覆盖
 */
class InMemoryTaskStoreTimeoutCancelTest {

    @Test
    void shouldMarkRunningTaskAsTimeout() {
        /*
         * 场景：
         * 任务已经被派发，并且处于 RUNNING 状态；
         * 此时 Controller 判定它超过了任务级 timeoutMs。
         *
         * 验证点：
         * 任务状态应该变成 TIMEOUT，并写入一个由系统生成的 TaskResult，
         * 这样后续查询接口才能展示超时原因。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.create(request("task-timeout"));
        store.assignWorker("task-timeout", "worker-a");
        store.updateStatus("task-timeout", TaskStatus.DISPATCHING);
        store.updateStatus("task-timeout", TaskStatus.RUNNING);

        store.timeout("task-timeout", "deadline exceeded");

        TaskMetadata task = store.get("task-timeout").orElseThrow();
        assertEquals(TaskStatus.TIMEOUT, task.getStatus());
        assertNotNull(task.getResult());
        assertEquals(TaskStatus.TIMEOUT, task.getResult().getStatus());
        assertEquals("deadline exceeded", task.getResult().getErrorMessage());
        assertEquals("worker-a", task.getResult().getWorkerId());
    }

    @Test
    void shouldCancelSubmittedTask() {
        /*
         * 场景：
         * 任务刚创建，还没有被派发到 Worker。
         *
         * 验证点：
         * SUBMITTED -> CANCELED 是合法状态流转；
         * 取消时也要写入 TaskResult，记录取消原因。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.create(request("task-cancel-submitted"));

        store.cancel("task-cancel-submitted", "user canceled");

        TaskMetadata task = store.get("task-cancel-submitted").orElseThrow();
        assertEquals(TaskStatus.CANCELED, task.getStatus());
        assertNotNull(task.getResult());
        assertEquals(TaskStatus.CANCELED, task.getResult().getStatus());
        assertEquals("user canceled", task.getResult().getErrorMessage());
    }

    @Test
    void shouldCancelRunningTask() {
        /*
         * 场景：
         * 任务已经在 Worker 上运行，但用户从 Controller 侧取消了它。
         *
         * 验证点：
         * RUNNING -> CANCELED 是合法状态流转；
         * 系统生成的 TaskResult 里应该保留 assignedWorkerId，
         * 方便后续查询和排查。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.create(request("task-cancel-running"));
        store.assignWorker("task-cancel-running", "worker-b");
        store.updateStatus("task-cancel-running", TaskStatus.DISPATCHING);
        store.updateStatus("task-cancel-running", TaskStatus.RUNNING);

        store.cancel("task-cancel-running", "cancel requested");

        TaskMetadata task = store.get("task-cancel-running").orElseThrow();
        assertEquals(TaskStatus.CANCELED, task.getStatus());
        assertEquals(TaskStatus.CANCELED, task.getResult().getStatus());
        assertEquals("worker-b", task.getResult().getWorkerId());
    }

    @Test
    void shouldRejectCompleteAfterTimeout() {
        /*
         * 场景：
         * Controller 已经把任务标记为 TIMEOUT，
         * 但 Worker 后来又返回了一个迟到的 SUCCEEDED 结果。
         *
         * 验证点：
         * 后到结果应该被拒绝，原本的 TIMEOUT 状态和结果不能被覆盖。
         * 这是本课最核心的终态保护。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.create(request("task-late-after-timeout"));
        store.updateStatus("task-late-after-timeout", TaskStatus.DISPATCHING);
        store.updateStatus("task-late-after-timeout", TaskStatus.RUNNING);
        store.timeout("task-late-after-timeout", "timeout first");

        TaskResult lateSuccess = result("task-late-after-timeout", TaskStatus.SUCCEEDED, "ok", null);

        assertThrows(IllegalStateException.class,
                () -> store.complete("task-late-after-timeout", lateSuccess));

        TaskMetadata task = store.get("task-late-after-timeout").orElseThrow();
        assertEquals(TaskStatus.TIMEOUT, task.getStatus());
        assertEquals(TaskStatus.TIMEOUT, task.getResult().getStatus());
        assertEquals("timeout first", task.getResult().getErrorMessage());
    }

    @Test
    void shouldRejectCompleteAfterCancel() {
        /*
         * 场景：
         * Controller 已经取消了任务，
         * 但 Worker 后来又返回了成功结果。
         *
         * 验证点：
         * CANCELED 必须保持终态，不能被后到的成功结果覆盖。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.create(request("task-late-after-cancel"));
        store.cancel("task-late-after-cancel", "canceled first");

        TaskResult lateSuccess = result("task-late-after-cancel", TaskStatus.SUCCEEDED, "ok", null);

        assertThrows(IllegalStateException.class,
                () -> store.complete("task-late-after-cancel", lateSuccess));

        TaskMetadata task = store.get("task-late-after-cancel").orElseThrow();
        assertEquals(TaskStatus.CANCELED, task.getStatus());
        assertEquals(TaskStatus.CANCELED, task.getResult().getStatus());
        assertEquals("canceled first", task.getResult().getErrorMessage());
    }

    @Test
    void shouldRejectNonTerminalCompleteResult() {
        /*
         * 场景：
         * 调用方错误地用 RUNNING 这种过程状态调用 complete(...)。
         *
         * 验证点：
         * complete(...) 只接受最终任务结果。
         * RUNNING 虽然可以作为 DISPATCHING 的下一个状态，
         * 但它不是一个合法的最终结果。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.create(request("task-non-terminal-result"));
        store.updateStatus("task-non-terminal-result", TaskStatus.DISPATCHING);

        TaskResult runningResult = result("task-non-terminal-result", TaskStatus.RUNNING, null, null);

        assertThrows(IllegalArgumentException.class,
                () -> store.complete("task-non-terminal-result", runningResult));

        TaskMetadata task = store.get("task-non-terminal-result").orElseThrow();
        assertEquals(TaskStatus.DISPATCHING, task.getStatus());
        assertNull(task.getResult());
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

    private TaskResult result(String taskId, TaskStatus status, String output, String errorMessage) {
        return new TaskResult(
                taskId,
                status,
                output,
                errorMessage,
                100,
                200,
                "worker-a"
        );
    }
}

package com.heteromesh.task;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryTaskStore 的单元测试。
 *
 * 本测试只验证任务仓库的内存行为，不启动 Controller、Worker 或 Netty。
 * TaskStore 后续会被 TaskScheduler 依赖，所以这里重点验证：
 * 1. 任务能不能可靠创建和查询
 * 2. 重复 taskId 会不会覆盖已有任务
 * 3. 状态更新是否经过 TaskStateMachine
 * 4. Worker 分配、尝试记录和结果写入是否能被保存
 */
class InMemoryTaskStoreTest {

    @Test
    void shouldCreateTaskWithProvidedId() {
        /*
         * 客户端传入 taskId 时，TaskStore 应该沿用这个 ID。
         * 这种模式后续可用于幂等提交：客户端重试时仍然带同一个 taskId。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        TaskRequest request = request("task-001");

        TaskMetadata metadata = store.create(request);

        assertEquals("task-001", metadata.getTaskId());
        assertEquals(TaskStatus.SUBMITTED, metadata.getStatus());
        assertSame(request, metadata.getRequest());
        assertTrue(store.get("task-001").isPresent());
    }

    @Test
    void shouldGenerateTaskIdWhenMissing() {
        /*
         * 简单提交模式下，客户端可以不传 taskId。
         * Controller/TaskStore 会生成一个 taskId，并回写到 request 中，方便调用方后续查询。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        TaskRequest request = request(null);

        TaskMetadata metadata = store.create(request);

        assertNotNull(metadata.getTaskId());
        assertFalse(metadata.getTaskId().isBlank());
        assertEquals(metadata.getTaskId(), request.getTaskId());
        assertTrue(store.get(metadata.getTaskId()).isPresent());
    }

    @Test
    void shouldRejectDuplicateTaskId() {
        /*
         * 同一个 taskId 不能被第二次 create 覆盖。
         * 否则一个 RUNNING 的任务可能被新的 SUBMITTED 元数据覆盖，导致 Controller 丢失执行状态。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.create(request("task-dup"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> store.create(request("task-dup")));

        assertTrue(ex.getMessage().contains("task-dup"));
        assertEquals(1, store.listAll().size());
    }

    @Test
    void shouldUpdateStatusThroughStateMachine() {
        /*
         * updateStatus 不能直接 setStatus，必须走 TaskStateMachine。
         * 这里先验证合法流转可以成功，再验证非法流转会被拒绝。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.create(request("task-state"));

        store.updateStatus("task-state", TaskStatus.DISPATCHING);
        assertEquals(TaskStatus.DISPATCHING, store.get("task-state").orElseThrow().getStatus());

        assertThrows(IllegalStateException.class,
                () -> store.updateStatus("task-state", TaskStatus.SUCCEEDED));
    }

    @Test
    void shouldAssignWorkerAndRecordAttempt() {
        /*
         * 调度器选择 Worker 后，需要记录 assignedWorkerId。
         * 失败重试时，还需要记录尝试过的 Worker 和 retryCount，
         * 后续可以用 attemptedWorkers 避免重复选择失败节点。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.create(request("task-worker"));

        store.assignWorker("task-worker", "worker-a");
        store.recordAttempt("task-worker", "worker-a");
        store.recordAttempt("task-worker", "worker-b");

        TaskMetadata task = store.get("task-worker").orElseThrow();
        assertEquals("worker-a", task.getAssignedWorkerId());
        assertEquals(2, task.getRetryCount());
        assertEquals(List.of("worker-a", "worker-b"), task.getAttemptedWorkers());
    }

    @Test
    void shouldCompleteTaskWithResult() {
        /*
         * complete 用于写入 Worker 回传的最终结果。
         * 任务必须先进入 RUNNING，然后才能流转到 SUCCEEDED。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.create(request("task-complete"));
        store.updateStatus("task-complete", TaskStatus.DISPATCHING);
        store.updateStatus("task-complete", TaskStatus.RUNNING);

        TaskResult result = new TaskResult(
                "task-complete",
                TaskStatus.SUCCEEDED,
                "ok",
                null,
                100,
                200,
                "worker-a"
        );
        store.complete("task-complete", result);

        TaskMetadata task = store.get("task-complete").orElseThrow();
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
        assertSame(result, task.getResult());
    }

    @Test
    void shouldListByStatus() {
        /*
         * HTTP API 和监控面板会需要按状态查询任务。
         * 这里验证 listByStatus 只返回目标状态的任务。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.create(request("task-a"));
        store.create(request("task-b"));
        store.create(request("task-c"));

        store.updateStatus("task-a", TaskStatus.DISPATCHING);
        store.updateStatus("task-b", TaskStatus.DISPATCHING);

        List<TaskMetadata> submitted = store.listByStatus(TaskStatus.SUBMITTED);
        List<TaskMetadata> dispatching = store.listByStatus(TaskStatus.DISPATCHING);

        assertEquals(1, submitted.size());
        assertEquals("task-c", submitted.get(0).getTaskId());
        assertEquals(2, dispatching.size());
        assertTrue(dispatching.stream().allMatch(task -> task.getStatus() == TaskStatus.DISPATCHING));
    }

    @Test
    void shouldRejectMissingTaskUpdate() {
        /*
         * 更新不存在的任务说明调用流程有问题。
         * TaskStore 应该明确抛异常，而不是静默忽略。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();

        assertThrows(IllegalArgumentException.class,
                () -> store.updateStatus("missing-task", TaskStatus.DISPATCHING));
        assertThrows(IllegalArgumentException.class,
                () -> store.assignWorker("missing-task", "worker-a"));
        assertThrows(IllegalArgumentException.class,
                () -> store.recordAttempt("missing-task", "worker-a"));
    }

    @Test
    void getShouldReturnEmptyWhenTaskDoesNotExist() {
        /*
         * get 是查询语义：任务不存在是正常情况，所以返回 Optional.empty。
         * 这和 update/complete 的 requireTask 语义不同。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();

        Optional<TaskMetadata> found = store.get("not-exist");

        assertTrue(found.isEmpty());
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
}

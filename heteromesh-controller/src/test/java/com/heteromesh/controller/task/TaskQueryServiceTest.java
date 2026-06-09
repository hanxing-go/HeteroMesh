package com.heteromesh.controller.task;

import com.heteromesh.task.InMemoryTaskStore;
import com.heteromesh.task.TaskMetadata;
import com.heteromesh.task.TaskRequest;
import com.heteromesh.task.TaskResult;
import com.heteromesh.task.TaskStatus;
import com.heteromesh.task.TaskStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskQueryService 的单元测试。
 *
 * 这组测试不启动 Controller、Worker 或 Netty，只验证查询服务本身：
 * 1. 能不能从 TaskStore 中读取任务
 * 2. 能不能把内部模型 TaskMetadata 转成对外视图 TaskSummaryView / TaskDetailView
 * 3. 能不能正确处理未完成、成功、失败这些不同任务状态
 * 4. 能不能保护内部可变集合，不把 TaskMetadata 的 List / Map 直接暴露出去
 */
class TaskQueryServiceTest {

    @Test
    void shouldThrowWhenTaskDoesNotExist() {
        /*
         * 场景：调用方用一个不存在的 taskId 查询任务详情。
         *
         * 设计原因：
         * TaskStore.get(taskId) 本身返回 Optional.empty 是正常查询语义；
         * 但 TaskQueryService.getTask(taskId) 是“查详情”的业务入口，
         * 调用方明确指定了一个 taskId，如果不存在，应该给出清晰异常。
         *
         * 验证点：
         * 1. 抛出 IllegalArgumentException
         * 2. 异常信息里包含原始 taskId，方便排查是哪一个任务不存在
         */
        TaskQueryService service = new TaskQueryService(new InMemoryTaskStore());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getTask("missing-task"));

        assertTrue(ex.getMessage().contains("missing-task"));
    }

    @Test
    void shouldReturnDetailForTaskWithoutResult() {
        /*
         * 场景：任务刚创建，还没有被 Worker 执行完成。
         *
         * 此时 TaskMetadata 中有 TaskRequest，但没有 TaskResult。
         * 也就是说：
         * - 输入信息已经存在：taskId、taskType、payload、resourceRequirements
         * - 执行结果还不存在：output、errorMessage、startedAt、finishedAt
         *
         * 验证点：
         * 1. 查询详情时仍然能返回 TaskDetailView
         * 2. hasResult=false
         * 3. output/errorMessage 为 null
         * 4. startedAt/finishedAt/durationMs 为 0
         * 5. 输入 payload 和资源需求能正常展示
         */
        TaskStore store = new InMemoryTaskStore();
        store.create(request("task-pending"));
        TaskQueryService service = new TaskQueryService(store);

        TaskDetailView view = service.getTask("task-pending");

        assertEquals("task-pending", view.getTaskId());
        assertEquals("TEXT_GENERATION", view.getTaskType());
        assertEquals(TaskStatus.SUBMITTED, view.getStatus());
        assertFalse(view.isHasResult());
        assertEquals("hello", view.getPayload());
        assertEquals(Map.of("gpuType", "RTX 4090"), view.getResourceRequirements());
        assertTrue(view.getAttemptedWorkers().isEmpty());
        assertNull(view.getOutput());
        assertNull(view.getErrorMessage());
        assertEquals(0, view.getStartedAt());
        assertEquals(0, view.getFinishedAt());
        assertEquals(0, view.getDurationMs());
    }

    @Test
    void shouldReturnDetailForSucceededTask() {
        /*
         * 场景：任务已经被调度到 Worker，并且 Worker 返回了成功结果。
         *
         * 这里手动模拟完整状态流转：
         * SUBMITTED -> DISPATCHING -> RUNNING -> SUCCEEDED
         *
         * 设计原因：
         * InMemoryTaskStore.complete(...) 会经过 TaskStateMachine，
         * 所以测试里不能直接从 SUBMITTED 跳到 SUCCEEDED，
         * 必须先推进到 RUNNING。
         *
         * 验证点：
         * 1. Detail 里能看到最终状态 SUCCEEDED
         * 2. 能看到 assignedWorkerId、retryCount、attemptedWorkers
         * 3. hasResult=true
         * 4. output 来自 TaskResult
         * 5. durationMs = finishedAt - startedAt
         */
        TaskStore store = new InMemoryTaskStore();
        store.create(request("task-done"));
        store.assignWorker("task-done", "worker-a");
        store.recordAttempt("task-done", "worker-a");
        store.updateStatus("task-done", TaskStatus.DISPATCHING);
        store.updateStatus("task-done", TaskStatus.RUNNING);
        store.complete("task-done", new TaskResult(
                "task-done",
                TaskStatus.SUCCEEDED,
                "ok",
                null,
                100,
                250,
                "worker-a"
        ));
        TaskQueryService service = new TaskQueryService(store);

        TaskDetailView view = service.getTask("task-done");

        assertEquals(TaskStatus.SUCCEEDED, view.getStatus());
        assertEquals("worker-a", view.getAssignedWorkerId());
        assertEquals(1, view.getRetryCount());
        assertEquals(List.of("worker-a"), view.getAttemptedWorkers());
        assertTrue(view.isHasResult());
        assertEquals("ok", view.getOutput());
        assertNull(view.getErrorMessage());
        assertEquals(100, view.getStartedAt());
        assertEquals(250, view.getFinishedAt());
        assertEquals(150, view.getDurationMs());
    }

    @Test
    void shouldReturnDetailForFailedTask() {
        /*
         * 场景：Worker 执行任务失败，并返回 FAILED 结果。
         *
         * 失败任务和成功任务一样，也应该被 TaskQueryService 转成详情视图。
         * 区别在于：
         * - output 通常为空
         * - errorMessage 应该展示失败原因
         *
         * 验证点：
         * 1. Detail 状态是 FAILED
         * 2. hasResult=true，因为 Worker 已经返回了结果
         * 3. output 为 null
         * 4. errorMessage 来自 TaskResult
         * 5. 失败任务同样能计算 durationMs
         */
        TaskStore store = new InMemoryTaskStore();
        store.create(request("task-failed"));
        store.assignWorker("task-failed", "worker-b");
        store.recordAttempt("task-failed", "worker-b");
        store.updateStatus("task-failed", TaskStatus.DISPATCHING);
        store.updateStatus("task-failed", TaskStatus.RUNNING);
        store.complete("task-failed", new TaskResult(
                "task-failed",
                TaskStatus.FAILED,
                null,
                "executor error",
                300,
                450,
                "worker-b"
        ));
        TaskQueryService service = new TaskQueryService(store);

        TaskDetailView view = service.getTask("task-failed");

        assertEquals(TaskStatus.FAILED, view.getStatus());
        assertTrue(view.isHasResult());
        assertNull(view.getOutput());
        assertEquals("executor error", view.getErrorMessage());
        assertEquals(150, view.getDurationMs());
    }

    @Test
    void shouldListAllTasksAsSummaryViews() {
        /*
         * 场景：查询任务列表页。
         *
         * listAll 返回的是 TaskSummaryView，而不是 TaskMetadata。
         * SummaryView 是轻量摘要，只展示列表页需要快速扫一眼的信息，
         * 不暴露 payload、resourceRequirements、result 等详情字段。
         *
         * 验证点：
         * 1. 创建了两个任务，列表返回两个摘要
         * 2. 返回结果中包含这两个 taskId
         * 3. 每个摘要都能正确展示 taskType
         */
        TaskStore store = new InMemoryTaskStore();
        store.create(request("task-a"));
        store.create(request("task-b"));
        TaskQueryService service = new TaskQueryService(store);

        List<TaskSummaryView> views = service.listAll();

        assertEquals(2, views.size());
        assertTrue(views.stream().anyMatch(view -> "task-a".equals(view.getTaskId())));
        assertTrue(views.stream().anyMatch(view -> "task-b".equals(view.getTaskId())));
        assertTrue(views.stream().allMatch(view -> "TEXT_GENERATION".equals(view.getTaskType())));
    }

    @Test
    void shouldListTasksByStatus() {
        /*
         * 场景：按任务状态过滤列表。
         *
         * 后续 HTTP API 可能会对应：
         * GET /api/tasks?status=RUNNING
         *
         * 这里准备三个任务：
         * - task-submitted 保持 SUBMITTED
         * - task-dispatching 推进到 DISPATCHING
         * - task-running 推进到 RUNNING
         *
         * 验证点：
         * 1. 查询 RUNNING 时只返回 task-running
         * 2. 查询 SUBMITTED 时只返回 task-submitted
         * 3. 返回对象仍然是 TaskSummaryView
         */
        TaskStore store = new InMemoryTaskStore();
        store.create(request("task-submitted"));
        store.create(request("task-dispatching"));
        store.create(request("task-running"));
        store.updateStatus("task-dispatching", TaskStatus.DISPATCHING);
        store.updateStatus("task-running", TaskStatus.DISPATCHING);
        store.updateStatus("task-running", TaskStatus.RUNNING);
        TaskQueryService service = new TaskQueryService(store);

        List<TaskSummaryView> running = service.listByStatus(TaskStatus.RUNNING);
        List<TaskSummaryView> submitted = service.listByStatus(TaskStatus.SUBMITTED);

        assertEquals(1, running.size());
        assertEquals("task-running", running.get(0).getTaskId());
        assertEquals(TaskStatus.RUNNING, running.get(0).getStatus());

        assertEquals(1, submitted.size());
        assertEquals("task-submitted", submitted.get(0).getTaskId());
        assertEquals(TaskStatus.SUBMITTED, submitted.get(0).getStatus());
    }

    @Test
    void shouldCountTasksByStatusAndIncludeZeroStatuses() {
        /*
         * 场景：统计各状态任务数量。
         *
         * 后续 Dashboard 或 HTTP API 可能会展示：
         * SUBMITTED: 1
         * RUNNING: 1
         * SUCCEEDED: 1
         * FAILED: 0
         *
         * 设计原因：
         * countByStatus 不应该只返回“出现过的状态”，
         * 否则页面端还要自己补 0，使用体验会变差。
         *
         * 验证点：
         * 1. 已出现的状态计数正确
         * 2. 没出现过的状态也存在，并且值为 0
         */
        TaskStore store = new InMemoryTaskStore();
        store.create(request("task-submitted"));
        store.create(request("task-running"));
        store.create(request("task-succeeded"));
        store.updateStatus("task-running", TaskStatus.DISPATCHING);
        store.updateStatus("task-running", TaskStatus.RUNNING);
        store.updateStatus("task-succeeded", TaskStatus.DISPATCHING);
        store.updateStatus("task-succeeded", TaskStatus.RUNNING);
        store.complete("task-succeeded", new TaskResult(
                "task-succeeded",
                TaskStatus.SUCCEEDED,
                "ok",
                null,
                10,
                20,
                "worker-a"
        ));
        TaskQueryService service = new TaskQueryService(store);

        Map<TaskStatus, Long> counts = service.countByStatus();

        assertEquals(1, counts.get(TaskStatus.SUBMITTED));
        assertEquals(1, counts.get(TaskStatus.RUNNING));
        assertEquals(1, counts.get(TaskStatus.SUCCEEDED));
        assertEquals(0, counts.get(TaskStatus.DISPATCHING));
        assertEquals(0, counts.get(TaskStatus.FAILED));
        assertEquals(0, counts.get(TaskStatus.TIMEOUT));
        assertEquals(0, counts.get(TaskStatus.CANCELED));
    }

    @Test
    void shouldReturnCopiesOfMutableDetailFields() {
        /*
         * 场景：调用方拿到 TaskDetailView 后，修改里面的 List / Map。
         *
         * 设计原因：
         * TaskMetadata 是 Controller 内部生命周期记录。
         * attemptedWorkers 和 resourceRequirements 都是可变集合，
         * 如果 TaskDetailView 直接引用内部集合，外部代码就可能污染内部状态。
         *
         * 所以 TaskQueryService.toDetailView(...) 应该做副本：
         * - new ArrayList<>(metadata.getAttemptedWorkers())
         * - new HashMap<>(request.getResourceRequirements())
         *
         * 验证点：
         * 1. 修改 view.getAttemptedWorkers() 不影响 metadata.getAttemptedWorkers()
         * 2. 修改 view.getResourceRequirements() 不影响 request.getResourceRequirements()
         */
        TaskStore store = new InMemoryTaskStore();
        TaskRequest request = request("task-copy");
        store.create(request);
        store.assignWorker("task-copy", "worker-a");
        store.recordAttempt("task-copy", "worker-a");
        TaskQueryService service = new TaskQueryService(store);

        TaskDetailView view = service.getTask("task-copy");
        view.getAttemptedWorkers().add("worker-b");
        view.getResourceRequirements().put("gpuType", "RTX 5090");

        TaskMetadata metadata = store.get("task-copy").orElseThrow();
        assertEquals(List.of("worker-a"), metadata.getAttemptedWorkers());
        assertEquals("RTX 4090", request.getResourceRequirements().get("gpuType"));
    }

    private TaskRequest request(String taskId) {
        /*
         * 测试辅助方法：构造一个标准 TaskRequest。
         *
         * 每个测试只关心 taskId 和任务状态变化，
         * 所以 taskType、payload、resourceRequirements、timeoutMs 使用固定值，
         * 避免每个测试重复写一大段构造代码。
         */
        return new TaskRequest(
                taskId,
                "TEXT_GENERATION",
                "hello",
                Map.of("gpuType", "RTX 4090"),
                30_000
        );
    }
}

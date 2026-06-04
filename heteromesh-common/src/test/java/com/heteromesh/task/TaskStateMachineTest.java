package com.heteromesh.task;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskStateMachine 的单元测试。
 *
 * 本测试只验证任务状态机本身，不启动 Controller、Worker 或 Netty。
 * 这样可以把“任务状态流转规则”从网络链路中独立出来测试，后续接入 TaskScheduler
 * 时如果出了问题，也能判断到底是状态机规则错了，还是调度链路错了。
 */
class TaskStateMachineTest {

    private final TaskStateMachine stateMachine = new TaskStateMachine();

    @Test
    void shouldAllowValidTransitions() {
        /*
         * 验证一条最典型的成功链路：
         * SUBMITTED -> DISPATCHING -> RUNNING -> SUCCEEDED
         *
         * 这对应用户提交任务、Controller 派发任务、Worker 执行任务、最终成功完成。
         */
        TaskMetadata task = newTask();

        stateMachine.transit(task, TaskStatus.DISPATCHING);
        assertEquals(TaskStatus.DISPATCHING, task.getStatus());

        stateMachine.transit(task, TaskStatus.RUNNING);
        assertEquals(TaskStatus.RUNNING, task.getStatus());

        stateMachine.transit(task, TaskStatus.SUCCEEDED);
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
    }

    @Test
    void shouldRejectInvalidTransition() {
        /*
         * SUBMITTED 不能直接跳到 SUCCEEDED。
         * 如果允许这种跳转，说明任务没有经过派发和执行就被标记成功，调度语义会失真。
         */
        TaskMetadata task = newTask();

        assertFalse(stateMachine.canTransit(TaskStatus.SUBMITTED, TaskStatus.SUCCEEDED));
        assertThrows(IllegalStateException.class,
                () -> stateMachine.transit(task, TaskStatus.SUCCEEDED));

        assertEquals(TaskStatus.SUBMITTED, task.getStatus(),
                "非法流转失败后，原状态不应该被修改");
    }

    @Test
    void terminalStateShouldNotTransit() {
        /*
         * SUCCEEDED / FAILED / TIMEOUT / CANCELED 都是终态。
         * 终态任务不应该再回到 RUNNING 或 DISPATCHING，否则任务结果会变得不可追踪。
         */
        assertFalse(stateMachine.canTransit(TaskStatus.SUCCEEDED, TaskStatus.RUNNING));
        assertFalse(stateMachine.canTransit(TaskStatus.FAILED, TaskStatus.RUNNING));
        assertFalse(stateMachine.canTransit(TaskStatus.TIMEOUT, TaskStatus.DISPATCHING));
        assertFalse(stateMachine.canTransit(TaskStatus.CANCELED, TaskStatus.DISPATCHING));

        TaskMetadata task = newTask();
        stateMachine.transit(task, TaskStatus.CANCELED);

        assertThrows(IllegalStateException.class,
                () -> stateMachine.transit(task, TaskStatus.DISPATCHING));
    }

    @Test
    void shouldAllowFailureAndTimeoutFromRunning() {
        /*
         * RUNNING 后不一定成功，也可能失败或超时。
         * 这里分别验证 FAILED 和 TIMEOUT 都是从 RUNNING 出发的合法终态。
         */
        TaskMetadata failedTask = runningTask();
        stateMachine.transit(failedTask, TaskStatus.FAILED);
        assertEquals(TaskStatus.FAILED, failedTask.getStatus());

        TaskMetadata timeoutTask = runningTask();
        stateMachine.transit(timeoutTask, TaskStatus.TIMEOUT);
        assertEquals(TaskStatus.TIMEOUT, timeoutTask.getStatus());
    }

    @Test
    void transitShouldUpdateMetadataTimestamp() throws InterruptedException {
        /*
         * 状态变化时，TaskMetadata.updatedAt 应该刷新。
         * 后续 Controller 查询任务状态时，可以用 updatedAt 判断任务最近一次变化时间。
         */
        TaskMetadata task = newTask();
        long before = task.getUpdatedAt();

        Thread.sleep(5);
        stateMachine.transit(task, TaskStatus.DISPATCHING);

        assertTrue(task.getUpdatedAt() > before,
                "状态流转后 updatedAt 应该晚于原始时间");
    }

    @Test
    void taskMetadataShouldTrackAssignedWorkerAndAttempts() {
        /*
         * 这个测试不验证状态机，而是验证 TaskMetadata 的调度追踪字段可用。
         * 后续故障转移时，attemptedWorkers 可以记录已经尝试过的 Worker，
         * 避免重试时反复选择同一个失败节点。
         */
        TaskMetadata task = newTask();

        task.setAssignedWorkerId("worker-a");
        task.getAttemptedWorkers().add("worker-a");
        task.setRetryCount(1);

        assertEquals("worker-a", task.getAssignedWorkerId());
        assertEquals(1, task.getRetryCount());
        assertTrue(task.getAttemptedWorkers().contains("worker-a"));
    }

    private TaskMetadata newTask() {
        TaskRequest request = new TaskRequest(
                "task-001",
                "TEXT_GENERATION",
                "hello",
                Map.of("gpuType", "RTX 4090"),
                30_000
        );
        return new TaskMetadata(request.getTaskId(), request);
    }

    private TaskMetadata runningTask() {
        TaskMetadata task = newTask();
        stateMachine.transit(task, TaskStatus.DISPATCHING);
        stateMachine.transit(task, TaskStatus.RUNNING);
        return task;
    }
}

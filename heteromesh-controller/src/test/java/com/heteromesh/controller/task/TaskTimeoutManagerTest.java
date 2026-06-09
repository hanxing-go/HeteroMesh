package com.heteromesh.controller.task;

import com.heteromesh.task.InMemoryTaskStore;
import com.heteromesh.task.TaskMetadata;
import com.heteromesh.task.TaskRequest;
import com.heteromesh.task.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskTimeoutManager 的单元测试。
 *
 * 这组测试验证 Controller 侧的任务超时闹钟：
 * 任务注册超时后，timeoutMs 到点会触发 timeout；
 * 如果提前取消这个闹钟，任务就不应该被标记为 TIMEOUT。
 */
class TaskTimeoutManagerTest {

    @Test
    void shouldMarkTaskTimeoutWhenDeadlineArrives() throws Exception {
        /*
         * 场景：
         * 一个已派发任务注册了很短的任务级超时。
         *
         * 验证点：
         * 到达 deadline 后，TaskTimeoutManager 会调用 taskStore.timeout(...)，
         * 任务状态最终变成 TIMEOUT。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        TaskMetadata task = store.create(request("task-timeout", 30));
        store.updateStatus("task-timeout", TaskStatus.DISPATCHING);

        try (TaskTimeoutManager manager = new TaskTimeoutManager(store)) {
            manager.registerTimeout(task);

            assertEventuallyStatus(store, "task-timeout", TaskStatus.TIMEOUT);
        }

        TaskMetadata timedOut = store.get("task-timeout").orElseThrow();
        assertNotNull(timedOut.getResult());
        assertEquals(TaskStatus.TIMEOUT, timedOut.getResult().getStatus());
        assertTrue(timedOut.getResult().getErrorMessage().contains("30 ms"));
    }

    @Test
    void shouldNotRegisterTimeoutWhenTimeoutMsIsZero() throws Exception {
        /*
         * 场景：
         * 任务的 timeoutMs = 0。
         *
         * 验证点：
         * timeoutMs <= 0 表示不注册任务级超时，
         * 所以短暂等待后任务仍然保持 DISPATCHING。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        TaskMetadata task = store.create(request("task-no-timeout", 0));
        store.updateStatus("task-no-timeout", TaskStatus.DISPATCHING);

        try (TaskTimeoutManager manager = new TaskTimeoutManager(store)) {
            manager.registerTimeout(task);
            Thread.sleep(80);
        }

        assertEquals(TaskStatus.DISPATCHING,
                store.get("task-no-timeout").orElseThrow().getStatus());
    }

    @Test
    void shouldCancelRegisteredTimeout() throws Exception {
        /*
         * 场景：
         * 任务注册了超时闹钟，但在 deadline 之前被提前清理。
         *
         * 验证点：
         * cancelTimeout 会移除并取消已经注册的定时任务，
         * 因此任务不会被自动推进到 TIMEOUT。
         */
        InMemoryTaskStore store = new InMemoryTaskStore();
        TaskMetadata task = store.create(request("task-cancel-timeout", 100));
        store.updateStatus("task-cancel-timeout", TaskStatus.DISPATCHING);

        try (TaskTimeoutManager manager = new TaskTimeoutManager(store)) {
            manager.registerTimeout(task);

            assertTrue(manager.cancelTimeout("task-cancel-timeout"));
            assertFalse(manager.cancelTimeout("task-cancel-timeout"));

            Thread.sleep(150);
        }

        assertEquals(TaskStatus.DISPATCHING,
                store.get("task-cancel-timeout").orElseThrow().getStatus());
    }

    private void assertEventuallyStatus(InMemoryTaskStore store,
                                        String taskId,
                                        TaskStatus expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1_000;
        while (System.currentTimeMillis() < deadline) {
            if (store.get(taskId).orElseThrow().getStatus() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(expected, store.get(taskId).orElseThrow().getStatus());
    }

    private TaskRequest request(String taskId, long timeoutMs) {
        return new TaskRequest(
                taskId,
                "TEXT_GENERATION",
                "hello",
                Map.of("gpuType", "RTX 4090"),
                timeoutMs
        );
    }
}

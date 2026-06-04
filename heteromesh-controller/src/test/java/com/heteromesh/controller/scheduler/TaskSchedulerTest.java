package com.heteromesh.controller.scheduler;

import com.heteromesh.loadbalancer.ConsistentHashLoadBalancer;
import com.heteromesh.loadbalancer.LoadBalancer;
import com.heteromesh.registry.InMemoryServiceRegistry;
import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.registry.ServiceRegistry;
import com.heteromesh.task.InMemoryTaskStore;
import com.heteromesh.task.TaskMetadata;
import com.heteromesh.task.TaskRequest;
import com.heteromesh.task.TaskStatus;
import com.heteromesh.task.TaskStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskScheduler 的单元测试。
 *
 * 这组测试不启动 Netty，也不真实转发 TASK_REQUEST。
 * 它只验证 Controller 调度器的核心职责：
 * 1. 创建任务元数据
 * 2. 在有 Worker 时选择节点
 * 3. 记录 assignedWorkerId / attemptedWorkers
 * 4. 正确更新任务状态
 * 5. 没有 Worker 或负载均衡选不到 Worker 时返回失败结果
 */
class TaskSchedulerTest {

    @Test
    void shouldScheduleTaskToWorker() {
        /*
         * 正常调度路径：
         * registry 和 loadBalancer 里都有 worker，TaskScheduler 应该选中一个 Worker，
         * 并把任务状态推进到 DISPATCHING。
         */
        TaskStore taskStore = new InMemoryTaskStore();
        ServiceRegistry registry = new InMemoryServiceRegistry();
        LoadBalancer loadBalancer = new ConsistentHashLoadBalancer();
        ServiceInstance worker = worker("worker-a");
        registry.register(worker);
        loadBalancer.addNode(worker);

        TaskScheduler scheduler = new TaskScheduler(taskStore, registry, loadBalancer);

        ScheduleResult result = scheduler.schedule(request("task-001"));

        assertTrue(result.isSuccess());
        assertEquals("worker-a", result.getWorker().getNodeId());
        assertNull(result.getErrorMessage());

        TaskMetadata task = taskStore.get("task-001").orElseThrow();
        assertEquals(TaskStatus.DISPATCHING, task.getStatus());
        assertEquals("worker-a", task.getAssignedWorkerId());
        assertEquals(1, task.getRetryCount());
        assertEquals("worker-a", task.getAttemptedWorkers().get(0));
    }

    @Test
    void shouldGenerateTaskIdWhenMissing() {
        /*
         * 如果客户端不传 taskId，TaskStore 会生成一个。
         * Scheduler 不应该关心 ID 是客户端传的还是服务端生成的，只要能继续调度即可。
         */
        TaskStore taskStore = new InMemoryTaskStore();
        ServiceRegistry registry = new InMemoryServiceRegistry();
        LoadBalancer loadBalancer = new ConsistentHashLoadBalancer();
        ServiceInstance worker = worker("worker-a");
        registry.register(worker);
        loadBalancer.addNode(worker);

        TaskRequest request = request(null);
        TaskScheduler scheduler = new TaskScheduler(taskStore, registry, loadBalancer);

        ScheduleResult result = scheduler.schedule(request);

        assertTrue(result.isSuccess());
        assertNotNull(result.getTask().getTaskId());
        assertFalse(result.getTask().getTaskId().isBlank());
        assertEquals(result.getTask().getTaskId(), request.getTaskId());
        assertTrue(taskStore.get(result.getTask().getTaskId()).isPresent());
    }

    @Test
    void shouldFailWhenNoWorkerAvailable() {
        /*
         * registry 为空时，说明当前没有任何在线 Worker。
         * Scheduler 应该返回失败结果，并把任务状态标记为 FAILED。
         *
         * 注意：为了让这个测试通过，Scheduler 不能直接 SUBMITTED -> FAILED。
         * 合理做法是先进入 DISPATCHING，再在调度失败时进入 FAILED。
         */
        TaskStore taskStore = new InMemoryTaskStore();
        ServiceRegistry registry = new InMemoryServiceRegistry();
        LoadBalancer loadBalancer = new ConsistentHashLoadBalancer();
        TaskScheduler scheduler = new TaskScheduler(taskStore, registry, loadBalancer);

        ScheduleResult result = scheduler.schedule(request("task-no-worker"));

        assertFalse(result.isSuccess());
        assertNull(result.getWorker());
        assertTrue(result.getErrorMessage().contains("No available worker"));

        TaskMetadata task = taskStore.get("task-no-worker").orElseThrow();
        assertEquals(TaskStatus.FAILED, task.getStatus());
    }

    @Test
    void shouldFailWhenLoadBalancerSelectsNoWorker() {
        /*
         * registry 有 Worker，但 loadBalancer 选不到节点。
         * 这个场景模拟负载均衡器内部没有同步到节点、或策略无法选择节点。
         */
        TaskStore taskStore = new InMemoryTaskStore();
        ServiceRegistry registry = new InMemoryServiceRegistry();
        LoadBalancer loadBalancer = new ConsistentHashLoadBalancer();
        registry.register(worker("worker-a"));
        TaskScheduler scheduler = new TaskScheduler(taskStore, registry, loadBalancer);

        ScheduleResult result = scheduler.schedule(request("task-no-selected-worker"));

        assertFalse(result.isSuccess());
        assertNull(result.getWorker());
        assertTrue(result.getErrorMessage().contains("No worker selected"));

        TaskMetadata task = taskStore.get("task-no-selected-worker").orElseThrow();
        assertEquals(TaskStatus.FAILED, task.getStatus());
    }

    @Test
    void shouldRejectDuplicateTaskId() {
        /*
         * Scheduler 通过 TaskStore 创建任务。
         * 如果 taskId 重复，TaskStore 应该拒绝覆盖，Scheduler 不应该吞掉这个错误。
         */
        TaskStore taskStore = new InMemoryTaskStore();
        ServiceRegistry registry = new InMemoryServiceRegistry();
        LoadBalancer loadBalancer = new ConsistentHashLoadBalancer();
        ServiceInstance worker = worker("worker-a");
        registry.register(worker);
        loadBalancer.addNode(worker);

        TaskScheduler scheduler = new TaskScheduler(taskStore, registry, loadBalancer);
        scheduler.schedule(request("task-dup"));

        assertThrows(IllegalArgumentException.class,
                () -> scheduler.schedule(request("task-dup")));
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

    private ServiceInstance worker(String nodeId) {
        long now = System.currentTimeMillis();
        return new ServiceInstance(
                nodeId,
                "127.0.0.1",
                9000,
                "RTX 4090",
                24 * 1024,
                100,
                now,
                now
        );
    }
}

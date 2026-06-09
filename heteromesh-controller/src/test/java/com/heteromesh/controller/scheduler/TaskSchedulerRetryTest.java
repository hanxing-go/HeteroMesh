package com.heteromesh.controller.scheduler;

import com.heteromesh.loadbalancer.LoadBalancer;
import com.heteromesh.registry.InMemoryServiceRegistry;
import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.registry.ServiceRegistry;
import com.heteromesh.task.InMemoryTaskStore;
import com.heteromesh.task.TaskMetadata;
import com.heteromesh.task.TaskRequest;
import com.heteromesh.task.TaskResult;
import com.heteromesh.task.TaskStatus;
import com.heteromesh.task.TaskStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TaskSchedulerRetryTest {

    @Test
    void shouldRetryToUnattemptedWorker() {
        TaskStore taskStore = new InMemoryTaskStore();
        ServiceRegistry registry = new InMemoryServiceRegistry();
        RecordingLoadBalancer loadBalancer = new RecordingLoadBalancer();
        register(registry, loadBalancer, worker("worker-a"), worker("worker-b"));

        TaskScheduler scheduler = new TaskScheduler(taskStore, registry, loadBalancer);
        scheduler.schedule(request("task-retry"));
        taskStore.updateStatus("task-retry", TaskStatus.RUNNING);

        ScheduleResult retry = scheduler.retry("task-retry");

        assertTrue(retry.isSuccess());
        assertEquals("worker-b", retry.getWorker().getNodeId());

        TaskMetadata task = taskStore.get("task-retry").orElseThrow();
        assertEquals(TaskStatus.DISPATCHING, task.getStatus());
        assertEquals("worker-b", task.getAssignedWorkerId());
        assertEquals(2, task.getRetryCount());
        assertEquals(List.of("worker-a", "worker-b"), task.getAttemptedWorkers());
    }

    @Test
    void shouldFailRetryWhenAllWorkersAttempted() {
        TaskStore taskStore = new InMemoryTaskStore();
        ServiceRegistry registry = new InMemoryServiceRegistry();
        RecordingLoadBalancer loadBalancer = new RecordingLoadBalancer();
        register(registry, loadBalancer, worker("worker-a"));

        TaskScheduler scheduler = new TaskScheduler(taskStore, registry, loadBalancer);
        scheduler.schedule(request("task-no-candidate"));
        taskStore.updateStatus("task-no-candidate", TaskStatus.RUNNING);

        ScheduleResult retry = scheduler.retry("task-no-candidate");

        assertFalse(retry.isSuccess());
        assertTrue(retry.getErrorMessage().contains("No available retry worker"));

        TaskMetadata task = taskStore.get("task-no-candidate").orElseThrow();
        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals("worker-a", task.getAssignedWorkerId());
        assertEquals(1, task.getRetryCount());
        assertEquals(List.of("worker-a"), task.getAttemptedWorkers());
    }

    @Test
    void shouldRejectRetryForTerminalTask() {
        TaskStore taskStore = new InMemoryTaskStore();
        ServiceRegistry registry = new InMemoryServiceRegistry();
        RecordingLoadBalancer loadBalancer = new RecordingLoadBalancer();
        register(registry, loadBalancer, worker("worker-a"), worker("worker-b"));

        TaskScheduler scheduler = new TaskScheduler(taskStore, registry, loadBalancer);
        scheduler.schedule(request("task-terminal"));
        taskStore.updateStatus("task-terminal", TaskStatus.RUNNING);
        taskStore.complete("task-terminal",
                result("task-terminal", TaskStatus.FAILED, "worker-a"));

        ScheduleResult retry = scheduler.retry("task-terminal");

        assertFalse(retry.isSuccess());
        assertTrue(retry.getErrorMessage().contains("already terminal"));
        assertEquals(TaskStatus.FAILED,
                taskStore.get("task-terminal").orElseThrow().getStatus());
    }

    @Test
    void shouldRejectRetryForNonRunningTask() {
        TaskStore taskStore = new InMemoryTaskStore();
        ServiceRegistry registry = new InMemoryServiceRegistry();
        RecordingLoadBalancer loadBalancer = new RecordingLoadBalancer();
        register(registry, loadBalancer, worker("worker-a"), worker("worker-b"));

        TaskScheduler scheduler = new TaskScheduler(taskStore, registry, loadBalancer);
        scheduler.schedule(request("task-dispatching"));

        ScheduleResult retry = scheduler.retry("task-dispatching");

        assertFalse(retry.isSuccess());
        assertTrue(retry.getErrorMessage().contains("not running"));

        TaskMetadata task = taskStore.get("task-dispatching").orElseThrow();
        assertEquals(TaskStatus.DISPATCHING, task.getStatus());
        assertEquals(1, task.getRetryCount());
    }

    private void register(ServiceRegistry registry,
                          RecordingLoadBalancer loadBalancer,
                          ServiceInstance... workers) {
        for (ServiceInstance worker : workers) {
            registry.register(worker);
            loadBalancer.addNode(worker);
        }
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

    private TaskResult result(String taskId, TaskStatus status, String workerId) {
        return new TaskResult(
                taskId,
                status,
                null,
                "worker failed",
                100,
                200,
                workerId
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

    private static final class RecordingLoadBalancer implements LoadBalancer {
        private final List<ServiceInstance> nodes = new ArrayList<>();

        @Override
        public void addNode(ServiceInstance instance) {
            removeNode(instance.getNodeId());
            nodes.add(instance);
        }

        @Override
        public void removeNode(String nodeId) {
            nodes.removeIf(node -> node.getNodeId().equals(nodeId));
        }

        @Override
        public ServiceInstance select(String key) {
            return nodes.isEmpty() ? null : nodes.get(0);
        }

        @Override
        public int size() {
            return nodes.size();
        }

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public ServiceInstance select(String key, Set<String> failedNodes) {
            for (ServiceInstance node : nodes) {
                if (failedNodes == null || !failedNodes.contains(node.getNodeId())) {
                    return node;
                }
            }
            return null;
        }
    }
}

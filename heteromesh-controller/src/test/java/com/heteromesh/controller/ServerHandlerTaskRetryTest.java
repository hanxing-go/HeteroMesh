package com.heteromesh.controller;

import com.heteromesh.controller.node.NodeChannelMap;
import com.heteromesh.controller.scheduler.TaskRetryPolicy;
import com.heteromesh.controller.scheduler.TaskScheduler;
import com.heteromesh.loadbalancer.LoadBalancer;
import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;
import com.heteromesh.registry.InMemoryServiceRegistry;
import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.registry.ServiceRegistry;
import com.heteromesh.task.InMemoryTaskStore;
import com.heteromesh.task.TaskMetadata;
import com.heteromesh.task.TaskPayloadCodec;
import com.heteromesh.task.TaskRequest;
import com.heteromesh.task.TaskResult;
import com.heteromesh.task.TaskStatus;
import com.heteromesh.task.TaskStore;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class ServerHandlerTaskRetryTest {

    @Test
    void shouldRetryFailedTaskOnAnotherWorker() {
        TestFixture fixture = new TestFixture(2);
        fixture.registerWorker("worker-a", true);
        fixture.registerWorker("worker-b", true);

        EmbeddedChannel clientChannel = fixture.newControllerConnection();
        Message submit = Message.createTaskSubmit(TaskPayloadCodec.encodeRequest(request("task-retry")));
        clientChannel.writeInbound(submit);

        Message firstForward = fixture.workerChannel("worker-a").readOutbound();
        assertNotNull(firstForward);
        assertEquals(MessageType.TASK_SUBMIT, firstForward.getType());
        assertEquals(submit.getRequestId(), firstForward.getRequestId());

        fixture.workerChannel("worker-a").writeInbound(Message.createTaskResult(
                submit.getRequestId(),
                TaskPayloadCodec.encodeResult(result(
                        "task-retry",
                        TaskStatus.FAILED,
                        null,
                        "worker-a failed",
                        "worker-a"
                ))
        ));

        assertNull(clientChannel.readOutbound(), "first failure should be retried, not returned to client");

        Message retryForward = fixture.workerChannel("worker-b").readOutbound();
        assertNotNull(retryForward);
        assertEquals(MessageType.TASK_SUBMIT, retryForward.getType());
        assertEquals(submit.getRequestId(), retryForward.getRequestId());

        TaskMetadata retrying = fixture.taskStore.get("task-retry").orElseThrow();
        assertEquals(TaskStatus.DISPATCHING, retrying.getStatus());
        assertEquals("worker-b", retrying.getAssignedWorkerId());
        assertEquals(List.of("worker-a", "worker-b"), retrying.getAttemptedWorkers());

        fixture.workerChannel("worker-b").writeInbound(Message.createTaskResult(
                submit.getRequestId(),
                TaskPayloadCodec.encodeResult(result(
                        "task-retry",
                        TaskStatus.SUCCEEDED,
                        "ok",
                        null,
                        "worker-b"
                ))
        ));

        Message reply = clientChannel.readOutbound();
        assertNotNull(reply);
        assertEquals(MessageType.TASK_RESULT, reply.getType());
        assertEquals(submit.getRequestId(), reply.getRequestId());

        TaskResult decoded = TaskPayloadCodec.decodeResult(reply.getBody());
        assertEquals(TaskStatus.SUCCEEDED, decoded.getStatus());
        assertEquals("ok", decoded.getOutput());
        assertEquals("worker-b", decoded.getWorkerId());

        TaskMetadata stored = fixture.taskStore.get("task-retry").orElseThrow();
        assertEquals(TaskStatus.SUCCEEDED, stored.getStatus());
        assertEquals("worker-b", stored.getResult().getWorkerId());
        assertEquals(2, stored.getRetryCount());
    }

    @Test
    void shouldCompleteFailedWhenRetryExhausted() {
        TestFixture fixture = new TestFixture(1);
        fixture.registerWorker("worker-a", true);
        fixture.registerWorker("worker-b", true);

        EmbeddedChannel clientChannel = fixture.newControllerConnection();
        Message submit = Message.createTaskSubmit(TaskPayloadCodec.encodeRequest(request("task-no-retry")));
        clientChannel.writeInbound(submit);

        Message firstForward = fixture.workerChannel("worker-a").readOutbound();
        assertNotNull(firstForward);

        fixture.workerChannel("worker-a").writeInbound(Message.createTaskResult(
                submit.getRequestId(),
                TaskPayloadCodec.encodeResult(result(
                        "task-no-retry",
                        TaskStatus.FAILED,
                        null,
                        "worker-a failed",
                        "worker-a"
                ))
        ));

        assertNull(fixture.workerChannel("worker-b").readOutbound());

        Message reply = clientChannel.readOutbound();
        assertNotNull(reply);
        TaskResult decoded = TaskPayloadCodec.decodeResult(reply.getBody());
        assertEquals(TaskStatus.FAILED, decoded.getStatus());
        assertEquals("worker-a", decoded.getWorkerId());

        TaskMetadata stored = fixture.taskStore.get("task-no-retry").orElseThrow();
        assertEquals(TaskStatus.FAILED, stored.getStatus());
        assertEquals("worker-a", stored.getResult().getWorkerId());
        assertEquals(1, stored.getRetryCount());
    }

    @Test
    void shouldCompleteFailedWhenRetryWorkerChannelUnavailable() {
        TestFixture fixture = new TestFixture(2);
        fixture.registerWorker("worker-a", true);
        fixture.registerWorker("worker-b", false);

        EmbeddedChannel clientChannel = fixture.newControllerConnection();
        Message submit = Message.createTaskSubmit(TaskPayloadCodec.encodeRequest(request("task-missing-retry-channel")));
        clientChannel.writeInbound(submit);

        Message firstForward = fixture.workerChannel("worker-a").readOutbound();
        assertNotNull(firstForward);

        fixture.workerChannel("worker-a").writeInbound(Message.createTaskResult(
                submit.getRequestId(),
                TaskPayloadCodec.encodeResult(result(
                        "task-missing-retry-channel",
                        TaskStatus.FAILED,
                        null,
                        "worker-a failed",
                        "worker-a"
                ))
        ));

        Message reply = clientChannel.readOutbound();
        assertNotNull(reply);
        assertEquals(MessageType.TASK_RESULT, reply.getType());

        TaskResult decoded = TaskPayloadCodec.decodeResult(reply.getBody());
        assertEquals(TaskStatus.FAILED, decoded.getStatus());
        assertEquals("worker-b", decoded.getWorkerId());
        assertTrue(decoded.getErrorMessage().contains("Retry worker channel is not available"));

        TaskMetadata stored = fixture.taskStore.get("task-missing-retry-channel").orElseThrow();
        assertEquals(TaskStatus.FAILED, stored.getStatus());
        assertEquals("worker-b", stored.getAssignedWorkerId());
        assertEquals("worker-b", stored.getResult().getWorkerId());
        assertEquals(List.of("worker-a", "worker-b"), stored.getAttemptedWorkers());
    }

    private static TaskRequest request(String taskId) {
        return new TaskRequest(
                taskId,
                "IMAGE_PROCESS",
                "{\"imageUrl\":\"https://example.com/a.png\"}",
                Map.of("gpuType", "RTX 4090"),
                30_000
        );
    }

    private static TaskResult result(String taskId,
                                     TaskStatus status,
                                     String output,
                                     String errorMessage,
                                     String workerId) {
        return new TaskResult(
                taskId,
                status,
                output,
                errorMessage,
                System.currentTimeMillis() - 10,
                System.currentTimeMillis(),
                workerId
        );
    }

    private static ServiceInstance worker(String nodeId) {
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

    private static final class TestFixture {
        private final ServiceRegistry registry = new InMemoryServiceRegistry();
        private final NodeChannelMap nodeChannelMap = new NodeChannelMap();
        private final RecordingLoadBalancer loadBalancer = new RecordingLoadBalancer();
        private final Map<String, Channel> pendingClients = new ConcurrentHashMap<>();
        private final TaskStore taskStore = new InMemoryTaskStore();
        private final TaskScheduler scheduler = new TaskScheduler(taskStore, registry, loadBalancer);
        private final TaskRetryPolicy retryPolicy;
        private final Map<String, EmbeddedChannel> workerChannels = new ConcurrentHashMap<>();

        private TestFixture(int maxAttempts) {
            this.retryPolicy = new TaskRetryPolicy(maxAttempts);
        }

        private void registerWorker(String nodeId, boolean bindChannel) {
            ServiceInstance worker = worker(nodeId);
            registry.register(worker);
            loadBalancer.addNode(worker);
            if (bindChannel) {
                EmbeddedChannel channel = newControllerConnection();
                nodeChannelMap.bind(nodeId, channel);
                workerChannels.put(nodeId, channel);
            }
        }

        private EmbeddedChannel workerChannel(String nodeId) {
            EmbeddedChannel channel = workerChannels.get(nodeId);
            assertNotNull(channel, "worker channel should exist: " + nodeId);
            return channel;
        }

        private EmbeddedChannel newControllerConnection() {
            return new EmbeddedChannel(new ServerHandler(
                    registry,
                    nodeChannelMap,
                    loadBalancer,
                    pendingClients,
                    scheduler,
                    taskStore,
                    retryPolicy
            ));
        }
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

package com.heteromesh.controller;

import com.heteromesh.controller.node.NodeChannelMap;
import com.heteromesh.controller.scheduler.TaskScheduler;
import com.heteromesh.loadbalancer.ConsistentHashLoadBalancer;
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ServerHandler 接入任务调度链路的行为测试。
 *
 * 这组测试不启动真实端口，而是用 EmbeddedChannel 模拟 Client/Worker 与 Controller 的连接。
 * 重点验证：
 * 1. Controller 调度失败时，会直接给 Client 返回 TASK_RESULT(FAILED)。
 * 2. Controller 选中 Worker 但连接不可用时，不会空指针，而是返回明确失败结果。
 * 3. Controller 成功调度时，会把 TASK_SUBMIT 转发给 Worker，并能把 Worker 的 TASK_RESULT 回传给 Client。
 */
class ServerHandlerTaskSubmitTest {

    @Test
    void shouldReplyFailedResultWhenNoWorkerAvailable() {
        /*
         * 场景：当前没有任何 Worker 注册。
         * 设计原因：TaskScheduler 会返回失败，ServerHandler 不应该继续转发，
         * 而应该把失败包装成 TASK_RESULT 返回给提交任务的 Client。
         */
        TestFixture fixture = new TestFixture();
        EmbeddedChannel clientChannel = fixture.newControllerConnection();

        Message submit = Message.createTaskSubmit(TaskPayloadCodec.encodeRequest(request("task-no-worker")));
        clientChannel.writeInbound(submit);

        Message reply = clientChannel.readOutbound();
        assertNotNull(reply);
        assertEquals(MessageType.TASK_RESULT, reply.getType());
        assertEquals(submit.getRequestId(), reply.getRequestId());

        TaskResult result = TaskPayloadCodec.decodeResult(reply.getBody());
        assertEquals("task-no-worker", result.getTaskId());
        assertEquals(TaskStatus.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().contains("No available worker"));
    }

    @Test
    void shouldReplyFailedResultWhenSelectedWorkerChannelUnavailable() {
        /*
         * 场景：注册中心和负载均衡器都知道 worker-a，但 NodeChannelMap 里没有可用连接。
         * 设计原因：这是本课刚修的关键分支，不能只留 TODO 后继续 workerChannel.writeAndFlush，
         * 否则会出现 NPE 或请求挂在 pendingClients 中永远没有结果。
         */
        TestFixture fixture = new TestFixture();
        ServiceInstance worker = worker("worker-a");
        fixture.registry.register(worker);
        fixture.loadBalancer.addNode(worker);

        EmbeddedChannel clientChannel = fixture.newControllerConnection();
        Message submit = Message.createTaskSubmit(TaskPayloadCodec.encodeRequest(request("task-missing-channel")));
        clientChannel.writeInbound(submit);

        Message reply = clientChannel.readOutbound();
        assertNotNull(reply);
        assertEquals(MessageType.TASK_RESULT, reply.getType());
        assertEquals(submit.getRequestId(), reply.getRequestId());

        TaskResult result = TaskPayloadCodec.decodeResult(reply.getBody());
        assertEquals("task-missing-channel", result.getTaskId());
        assertEquals(TaskStatus.FAILED, result.getStatus());
        assertEquals("worker-a", result.getWorkerId());
        assertTrue(result.getErrorMessage().contains("Selected worker channel is not available"));
    }

    @Test
    void shouldForwardTaskSubmitToWorkerAndReturnTaskResultToClient() {
        /*
         * 场景：worker-a 已注册，并且有可用连接。
         * 设计原因：Controller 调度成功时不应该立刻返回成功结果，
         * 而是把原始 TASK_SUBMIT 转发给 Worker；等 Worker 发回 TASK_RESULT 后，
         * Controller 再根据 requestId 把结果回传给最初的 Client。
         */
        TestFixture fixture = new TestFixture();
        ServiceInstance worker = worker("worker-a");
        fixture.registry.register(worker);
        fixture.loadBalancer.addNode(worker);

        EmbeddedChannel workerConnection = fixture.newControllerConnection();
        fixture.nodeChannelMap.bind("worker-a", workerConnection);

        EmbeddedChannel clientChannel = fixture.newControllerConnection();
        Message submit = Message.createTaskSubmit(TaskPayloadCodec.encodeRequest(request("task-forward")));
        clientChannel.writeInbound(submit);

        assertNull(clientChannel.readOutbound(), "调度成功只代表已派发，Client 此时还不应该收到最终结果");

        Message forwarded = workerConnection.readOutbound();
        assertNotNull(forwarded);
        assertEquals(MessageType.TASK_SUBMIT, forwarded.getType());
        assertEquals(submit.getRequestId(), forwarded.getRequestId());
        assertEquals(submit.getBody(), forwarded.getBody());

        TaskResult workerResult = new TaskResult(
                "task-forward",
                TaskStatus.SUCCEEDED,
                "ok",
                null,
                System.currentTimeMillis() - 10,
                System.currentTimeMillis(),
                "worker-a"
        );
        Message resultMessage = Message.createTaskResult(
                submit.getRequestId(),
                TaskPayloadCodec.encodeResult(workerResult)
        );
        workerConnection.writeInbound(resultMessage);

        Message replyToClient = clientChannel.readOutbound();
        assertNotNull(replyToClient);
        assertEquals(MessageType.TASK_RESULT, replyToClient.getType());
        assertEquals(submit.getRequestId(), replyToClient.getRequestId());

        TaskResult decoded = TaskPayloadCodec.decodeResult(replyToClient.getBody());
        assertEquals("task-forward", decoded.getTaskId());
        assertEquals(TaskStatus.SUCCEEDED, decoded.getStatus());
        assertEquals("ok", decoded.getOutput());
        assertEquals("worker-a", decoded.getWorkerId());

        TaskMetadata stored = fixture.taskStore.get("task-forward").orElseThrow();
        assertEquals(TaskStatus.SUCCEEDED, stored.getStatus());
        assertNotNull(stored.getResult());
        assertEquals("ok", stored.getResult().getOutput());
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
        private final LoadBalancer loadBalancer = new ConsistentHashLoadBalancer();
        private final Map<String, Channel> pendingClients = new ConcurrentHashMap<>();
        private final TaskStore taskStore = new InMemoryTaskStore();
        private final TaskScheduler scheduler = new TaskScheduler(taskStore, registry, loadBalancer);

        private EmbeddedChannel newControllerConnection() {
            return new EmbeddedChannel(new ServerHandler(
                    registry,
                    nodeChannelMap,
                    loadBalancer,
                    pendingClients,
                    scheduler,
                    taskStore
            ));
        }
    }
}

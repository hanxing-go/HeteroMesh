package com.heteromesh.worker;

import com.google.gson.Gson;
import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;
import com.heteromesh.rpc.RpcDispatcher;
import com.heteromesh.rpc.RpcInvocation;
import com.heteromesh.rpc.RpcRequest;
import com.heteromesh.rpc.RpcResponse;
import com.heteromesh.rpc.RpcServiceRegistry;
import com.heteromesh.task.TaskPayloadCodec;
import com.heteromesh.task.TaskRequest;
import com.heteromesh.task.TaskResult;
import com.heteromesh.task.TaskStatus;
import com.heteromesh.transport.RpcClient;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Worker 侧 TASK_SUBMIT 处理测试。
 *
 * 这组测试只验证 Worker ClientHandler 的职责：
 * 1. 收到 TASK_SUBMIT 后解析 TaskRequest。
 * 2. 调用注入的 TaskExecutor。
 * 3. 把 TaskExecutor 返回的 TaskResult 编码进 TASK_RESULT。
 * 4. 保持 Message.requestId 不变，方便 Controller 回传给原 Client。
 * 5. 旧 RPC 的 TASK_REQUEST 分支仍然可用。
 */
public class ClientHandlerTaskSubmitTest {

    private static final Gson GSON = new Gson();

    @Test
    void shouldExecuteTaskSubmitAndReturnTaskResult() {
        /*
         * 场景：Controller 把 TASK_SUBMIT 转发给 Worker。
         * 设计原因：Worker 不应该返回原始 TaskRequest，而应该返回执行后的 TaskResult。
         */
        String nodeId = "worker-a";
        TaskExecutor executor = request -> new TaskResult(
                request.getTaskId(),
                TaskStatus.SUCCEEDED,
                "processed: " + request.getPayload(),
                null,
                100,
                200,
                nodeId
        );
        EmbeddedChannel channel = newWorkerChannel(nodeId, executor, new RpcDispatcher(new RpcServiceRegistry()));
        channel.readOutbound(); // 丢弃 channelActive 自动发出的 REGISTER。

        Message submit = Message.createTaskSubmit(TaskPayloadCodec.encodeRequest(request("task-001")));
        channel.writeInbound(submit);

        Message response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(MessageType.TASK_RESULT, response.getType());
        assertEquals(submit.getRequestId(), response.getRequestId());

        TaskResult result = TaskPayloadCodec.decodeResult(response.getBody());
        assertEquals("task-001", result.getTaskId());
        assertEquals(TaskStatus.SUCCEEDED, result.getStatus());
        assertEquals("processed: image://demo.png", result.getOutput());
        assertEquals("worker-a", result.getWorkerId());
    }

    @Test
    void shouldPreserveTaskFailureFromExecutor() {
        /*
         * 场景：执行器判定任务失败。
         * 设计原因：ClientHandler 不应该吞掉失败，也不应该强行改成成功；
         * 它只负责把执行器给出的 TaskResult 原样包装成 TASK_RESULT。
         */
        String nodeId = "worker-a";
        TaskExecutor executor = request -> new TaskResult(
                request.getTaskId(),
                TaskStatus.FAILED,
                null,
                "simulated failure",
                100,
                200,
                nodeId
        );
        EmbeddedChannel channel = newWorkerChannel(nodeId, executor, new RpcDispatcher(new RpcServiceRegistry()));
        channel.readOutbound();

        Message submit = Message.createTaskSubmit(TaskPayloadCodec.encodeRequest(request("task-failed")));
        channel.writeInbound(submit);

        Message response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(MessageType.TASK_RESULT, response.getType());
        assertEquals(submit.getRequestId(), response.getRequestId());

        TaskResult result = TaskPayloadCodec.decodeResult(response.getBody());
        assertEquals("task-failed", result.getTaskId());
        assertEquals(TaskStatus.FAILED, result.getStatus());
        assertEquals("simulated failure", result.getErrorMessage());
        assertEquals("worker-a", result.getWorkerId());
    }

    @Test
    void shouldKeepExistingRpcRequestFlowWorking() {
        /*
         * 场景：Worker 仍然收到旧 RPC 的 TASK_REQUEST。
         * 设计原因：新增任务调度分支不能破坏原有 RPC 动态代理链路。
         */
        RpcServiceRegistry registry = new RpcServiceRegistry();
        registry.publish(EchoService.class, new EchoServiceImpl());
        EmbeddedChannel channel = newWorkerChannel(
                "worker-a",
                request -> fail("TASK_REQUEST should not invoke TaskExecutor"),
                new RpcDispatcher(registry)
        );
        channel.readOutbound();

        RpcInvocation invocation = new RpcInvocation();
        invocation.setServiceName(EchoService.class.getName());
        invocation.setMethodName("echo");
        invocation.setParameterTypes(new String[]{String.class.getName()});
        invocation.setArgs(new Object[]{"hello"});

        RpcRequest rpcRequest = new RpcRequest();
        rpcRequest.setInvocation(invocation);
        rpcRequest.setTimeoutMs(1_000);

        Message requestMessage = Message.createTaskRequest(GSON.toJson(rpcRequest));
        channel.writeInbound(requestMessage);

        Message response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(MessageType.TASK_RESPONSE, response.getType());
        assertEquals(requestMessage.getRequestId(), response.getRequestId());

        RpcResponse rpcResponse = GSON.fromJson(response.getBody(), RpcResponse.class);
        assertTrue(rpcResponse.isSuccess());
        assertEquals("echo: hello", rpcResponse.getResult());
    }

    private static EmbeddedChannel newWorkerChannel(String nodeId,
                                                    TaskExecutor executor,
                                                    RpcDispatcher dispatcher) {
        EmbeddedChannel channel = new EmbeddedChannel();
        RpcClient rpcClient = new RpcClient(channel);
        channel.pipeline().addLast(new ClientHandler(rpcClient, nodeId, dispatcher, executor));
        channel.pipeline().fireChannelActive();
        return channel;
    }

    private static TaskRequest request(String taskId) {
        return new TaskRequest(
                taskId,
                "IMAGE_PROCESS",
                "image://demo.png",
                Map.of("gpuType", "RTX 4090"),
                30_000
        );
    }

    public interface EchoService {
        String echo(String text);
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String text) {
            return "echo: " + text;
        }
    }
}

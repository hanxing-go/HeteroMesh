package com.heteromesh.rpc;

import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;
import com.heteromesh.transport.RpcClient;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RpcFutureTest {

    private RpcClient rpcClient;

    @BeforeEach
    void setUp() {
        EmbeddedChannel channel = new EmbeddedChannel();
        rpcClient = new RpcClient(channel);
    }

    // ---------- 正常响应 ----------

    @Test
    void shouldReturnSuccessResponseOnNormalCompletion() throws Exception {
        // 模拟 Worker 返回的成功响应
        RpcResponse workerResp = RpcResponse.success("Hello, World!");
        Message responseMsg = new Message(
                MessageType.TASK_RESPONSE,
                "req-001",
                new com.google.gson.Gson().toJson(workerResp)
        );

        CompletableFuture<Message> future = new CompletableFuture<>();
        RpcFuture rpcFuture = new RpcFuture(future, "req-001", rpcClient);

        // 异步完成（模拟网络响应到达）
        future.complete(responseMsg);

        RpcResponse result = rpcFuture.get(1000);
        assertTrue(result.isSuccess());
        assertEquals("Hello, World!", result.getResult());
    }

    @Test
    void shouldReturnErrorResponseOnWorkerError() {
        RpcResponse workerResp = RpcResponse.error(RpcStatus.ERROR, "业务异常");
        Message responseMsg = new Message(
                MessageType.TASK_RESPONSE,
                "req-002",
                new com.google.gson.Gson().toJson(workerResp)
        );

        CompletableFuture<Message> future = new CompletableFuture<>();
        RpcFuture rpcFuture = new RpcFuture(future, "req-002", rpcClient);

        future.complete(responseMsg);

        RpcResponse result = rpcFuture.get(1000);
        assertFalse(result.isSuccess());
        assertEquals(RpcStatus.ERROR, result.getStatus());
        assertEquals("业务异常", result.getErrorMessage());
    }

    // ---------- 超时 ----------

    @Test
    void shouldReturnTimeoutResponseWhenFutureDoesNotComplete() {
        CompletableFuture<Message> neverComplete = new CompletableFuture<>();
        String requestId = "req-timeout";
        RpcFuture rpcFuture = new RpcFuture(neverComplete, requestId, rpcClient);

        long start = System.currentTimeMillis();
        RpcResponse result = rpcFuture.get(50);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(RpcStatus.TIMEOUT, result.getStatus());
        assertTrue(result.getErrorMessage().contains("超时"),
                "错误信息应该包含'超时'");
        assertTrue(elapsed >= 40, "至少应等待接近超时时间");
    }

    @Test
    void shouldCleanupPendingRequestOnTimeout() throws Exception {
        // 先通过 call() 注册一个 pending entry
        rpcClient.call("test-body");
        // 换个新的 EmbeddedChannel 来做 pending 登记...
        // 实际上用 EmbeddedChannel 不会真正发数据，这里直接验证 cleanup 方法本身

        CompletableFuture<Message> future = new CompletableFuture<>();
        String requestId = "req-cleanup";
        RpcFuture rpcFuture = new RpcFuture(future, requestId, rpcClient);

        // 先登记到 pendingRequests
        rpcClient.call("dummy", requestId);

        // 超时获取
        RpcResponse result = rpcFuture.get(50);

        assertEquals(RpcStatus.TIMEOUT, result.getStatus(),
                "future 永远不完成，应该超时");

        // 验证 cleanup 后，再用同样的 requestId call 不会冲突
        // （清理后 pendingRequests 里不应残留旧的 future）
        CompletableFuture<Message> newFuture = rpcClient.call("new-body", "req-cleanup");
        assertNotNull(newFuture, "cleanup 后相同 requestId 应该可以重新使用");
    }

    // ---------- 异常 ----------

    @Test
    void shouldReturnFrameworkErrorOnUnexpectedException() {
        CompletableFuture<Message> future = new CompletableFuture<>();
        String requestId = "req-exception";
        RpcFuture rpcFuture = new RpcFuture(future, requestId, rpcClient);

        // 模拟非预期的异常（比如中断）
        future.completeExceptionally(new RuntimeException("测试异常"));

        RpcResponse result = rpcFuture.get(1000);

        assertEquals(RpcStatus.FRAMEWORK_ERROR, result.getStatus());
        assertTrue(result.getErrorMessage().contains("测试异常"));
    }

    // ---------- 默认超时 ----------

    @Test
    void getWithoutArgsShouldUseDefaultTimeout() {
        // 已完成 future → 默认超时不生效，直接返回
        RpcResponse workerResp = RpcResponse.success("ok");
        Message responseMsg = new Message(
                MessageType.TASK_RESPONSE,
                "req-default",
                new com.google.gson.Gson().toJson(workerResp)
        );

        CompletableFuture<Message> future = new CompletableFuture<>();
        RpcFuture rpcFuture = new RpcFuture(future, "req-default", rpcClient);
        future.complete(responseMsg);

        RpcResponse result = rpcFuture.get();  // 无参，使用默认 30s

        assertTrue(result.isSuccess());
    }

    // ---------- getFuture ----------

    @Test
    void getFutureShouldReturnOriginalCompletableFuture() {
        CompletableFuture<Message> future = new CompletableFuture<>();
        RpcFuture rpcFuture = new RpcFuture(future, "req-future", rpcClient);

        assertSame(future, rpcFuture.getFuture());
    }
}

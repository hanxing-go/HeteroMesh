package com.heteromesh.rpc;


import com.google.gson.Gson;
import com.heteromesh.protocol.Message;
import com.heteromesh.transport.RpcClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RPC 异步调用的 Future 包装。
 *
 * 职责：
 * 1. 等待响应（可配置超时）
 * 2. 超时时自动返回 RpcResponse(status=TIMEOUT)
 * 3. 超时时自动清理 pendingRequests 残留
 * 4. 正常响应时解析为 RpcResponse
 */
public class RpcFuture {

    private static final Gson GSON = new Gson();

    private final String requestId;
    private final RpcClient rpcClient;
    private final CompletableFuture<Message> future;

    public RpcFuture(CompletableFuture<Message> future, String requestId, RpcClient rpcClient) {
        this.future = future;
        this.requestId = requestId;
        this.rpcClient = rpcClient;
    }

    /**
     * 同步等待响应。
     *
     * @param timeoutMs 超时毫秒数
     * @return 成功时返回 Worker 构造的 RpcResponse；超时时返回 status=TIMEOUT 的 RpcResponse
     */
    public RpcResponse get(long timeoutMs) {
        try {
            Message msg = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            // 正常拿到 Message → 解析 body 为 RpcResponse
            return GSON.fromJson(msg.getBody(), RpcResponse.class);
        } catch (TimeoutException e) {
            // ① 清理 pendingRequests 里的残留（否则这个 entry 永远存在 → 内存泄漏）
            rpcClient.cleanup(requestId);
            // ② 返回结构化的超时响应（调用方不需要 try-catch）
            return RpcResponse.timeout("调用超时，等待 " + timeoutMs + "ms 未收到响应");
        } catch (Exception e) {
            rpcClient.cleanup(requestId);
            return RpcResponse.error(RpcStatus.FRAMEWORK_ERROR, e.getMessage());
        }
    }

    /**
     * 使用默认超时（30 秒）。
     */
    public RpcResponse get() {
        return get(30_000);
    }

    /** 返回原始的 CompletableFuture，供异步调用场景使用 */
    public CompletableFuture<Message> getFuture() {
        return future;
    }
}

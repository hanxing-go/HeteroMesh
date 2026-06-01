package com.heteromesh.transport;

import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;
import com.heteromesh.rpc.RpcFuture;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RpcClient {

    // 「待取餐」登记本：requestId → 对应的空盒子
    private final ConcurrentHashMap<String, CompletableFuture<Message>> pendingRequests
            = new ConcurrentHashMap<>();

    private final Channel channel;

    public RpcClient(Channel channel) {
        this.channel = channel;
    }

    /**
     * 发请求，返回一个「承诺以后给你结果」的盒子。
     */
    public CompletableFuture<Message> call(String body) {
        Message request = Message.createTaskRequest(body);
        return call(body, request.getRequestId());
    }

    /**
     * 发请求，使用外部传入的 requestId（由 RpcRequest 提供），
     * 保证 pendingRequests 的 key 和 RpcFuture 清理用的是同一个 ID。
     */
    public CompletableFuture<Message> call(String body, String requestId) {
        Message request = new Message(MessageType.TASK_REQUEST, requestId, body);
        CompletableFuture<Message> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        channel.writeAndFlush(request);
        return future;
    }

    /**
     * 发请求并返回 RpcFuture。requestId 由 RpcClient 内部生成，
     * 不存在 RpcRequest 上，保证线路上和内存中都只有一份 requestId。
     */
    public RpcFuture call(String body, long timeoutMs) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Message> future = call(body, requestId);
        return new RpcFuture(future, requestId, this, timeoutMs);
    }

    /**
     * 收到响应时由 ClientHandler 调用。按 requestId 找到盒子，把结果放进去。
     */
    public void onResponse(Message response) {
        CompletableFuture<Message> future = pendingRequests.remove(response.getRequestId());
        if (future != null) {
            future.complete(response);
        } else {
//            System.err.println("[RpcClient] 收到未知响应，requestId=" + response.getRequestId());
            log.warn("[RpcClient] 收到未知响应，requestId={}", response.getRequestId());
        }
    }

    public void cleanup(String requestId) {
        pendingRequests.remove(requestId);
    }
}

package com.heteromesh.transport;

import com.heteromesh.protocol.Message;
import io.netty.channel.Channel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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
        // 1. 创建请求消息（requestId 自动生成）
        Message request = Message.createTaskRequest(body);

        // 2. 创建一个空盒子
        CompletableFuture<Message> future = new CompletableFuture<>();

        // 3. 登记：requestId → 盒子
        pendingRequests.put(request.getRequestId(), future);

        // 4. 异步发出去，不阻塞
        channel.writeAndFlush(request);

        // 5. 立刻返回盒子
        return future;
    }

    /**
     * 收到响应时由 ClientHandler 调用。按 requestId 找到盒子，把结果放进去。
     */
    public void onResponse(Message response) {
        CompletableFuture<Message> future = pendingRequests.remove(response.getRequestId());
        if (future != null) {
            future.complete(response);
        } else {
            System.err.println("[RpcClient] 收到未知响应，requestId=" + response.getRequestId());
        }
    }
}

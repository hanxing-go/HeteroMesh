package com.heteromesh.transport;

import com.heteromesh.protocol.*;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RpcClientTest {

    @Test
    void shouldReturnFutureWhenCallIsMade() {
        EmbeddedChannel channel = new EmbeddedChannel();
        RpcClient rpcClient = new RpcClient(channel);

        CompletableFuture<Message> future = rpcClient.call("你好");

        assertNotNull(future, "call() 应该立刻返回一个非 null 的 Future");
        assertFalse(future.isDone(), "响应还没到，Future 不应该完成");
    }

    @Test
    void shouldCompleteFutureWhenResponseArrives() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        RpcClient rpcClient = new RpcClient(channel);

        // 发请求
        CompletableFuture<Message> future = rpcClient.call("你好");

        // 从 Channel 读出刚刚发出的请求消息，拿到它的 requestId
        Message sentRequest = channel.readOutbound();
        assertNotNull(sentRequest, "应该有一条消息发出去");
        assertEquals(MessageType.TASK_REQUEST, sentRequest.getType());

        // 模拟 Controller 回复：用相同的 requestId 创建响应
        Message response = Message.createTaskResponse(
                sentRequest.getRequestId(), "你好，收到！");

        // 响应到达 → 调 onResponse
        rpcClient.onResponse(response);

        // Future 应该完成了
        assertTrue(future.isDone(), "收到响应后 Future 应该完成");
        assertEquals("你好，收到！", future.get().getBody());
    }

    @Test
    void shouldMatchMultipleConcurrentCalls() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        RpcClient rpcClient = new RpcClient(channel);

        // 发 3 个请求
        CompletableFuture<Message> f1 = rpcClient.call("请求1");
        CompletableFuture<Message> f2 = rpcClient.call("请求2");
        CompletableFuture<Message> f3 = rpcClient.call("请求3");

        // 读出 3 条发出的消息
        Message req1 = channel.readOutbound();
        Message req2 = channel.readOutbound();
        Message req3 = channel.readOutbound();

        // 乱序回复（模拟网络延迟不一致）
        rpcClient.onResponse(Message.createTaskResponse(req3.getRequestId(), "回复3"));
        rpcClient.onResponse(Message.createTaskResponse(req1.getRequestId(), "回复1"));
        rpcClient.onResponse(Message.createTaskResponse(req2.getRequestId(), "回复2"));

        // 每个 Future 对号入座
        assertEquals("回复1", f1.get().getBody());
        assertEquals("回复2", f2.get().getBody());
        assertEquals("回复3", f3.get().getBody());
    }

    @Test
    void shouldCompleteFutureViaPipelineIntegration() throws Exception {
        // 先创建 Channel（只有编解码器）
        EmbeddedChannel channel = new EmbeddedChannel(
                new MessageDecoder(),
                new MessageEncoder()
        );

        RpcClient rpcClient = new RpcClient(channel);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();

        // 再加入业务 Handler（此时 rpcClient 已存在）
        channel.pipeline().addLast(new SimpleChannelInboundHandler<Message>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                if (msg.getType() == MessageType.TASK_REQUEST) {
                    ctx.writeAndFlush(Message.createTaskResponse(
                            msg.getRequestId(), "pipeline 回复"));
                } else if (msg.getType() == MessageType.TASK_RESPONSE) {
                    rpcClient.onResponse(msg);
                }
            }
        });

        rpcClient.call("hello").thenAccept(response -> {
            result.set(response.getBody());
            latch.countDown();
        });

        // 发送端：写出到 Channel → 经过 Encoder → 到网络（字节）
        // 模拟网络回环：把编码后的字节直接灌回 Channel 的入站方向
        Object encoded = channel.readOutbound();        // Encoder 产生的字节
        channel.writeInbound(encoded);                   // 灌回解码

        // 解码后的 Message 被上面那个 SimpleChannelInboundHandler 收到
        // 它回复了一个 TASK_RESPONSE
        Object replyEncoded = channel.readOutbound();    // Encoder 产生的回复字节
        channel.writeInbound(replyEncoded);              // 灌回

        // onResponse 应该被触发
        assertTrue(latch.await(3, TimeUnit.SECONDS), "回调应该被触发");
        assertEquals("pipeline 回复", result.get());
    }
}

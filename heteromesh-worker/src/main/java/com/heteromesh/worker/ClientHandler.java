package com.heteromesh.worker;

import com.heteromesh.protocol.Message;

import com.heteromesh.registry.ServiceInstance;
import com.heteromesh.transport.RpcClient;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientHandler extends SimpleChannelInboundHandler<Message> {

    private final RpcClient rpcClient;
    private final String nodeId;
    public ClientHandler(RpcClient client, String nodeId) {
        this.rpcClient = client;
        this.nodeId = nodeId;
    }
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 连接建立后，主动发送一条消息
//        ctx.writeAndFlush(Message.createTaskRequest("你好，服务器！"));
        // 用rpcClient.call()
//        rpcClient.call("你好")
//                .thenAccept(response -> {
////                    System.out.println(response.getBody());
//                    log.info("RPC回复:{}", response.getBody());
//                });
        // 连接建立后，发送注册消息
        ServiceInstance self = new ServiceInstance(
                this.nodeId, "127.0.0.1", 9090,
                "RTX 5090", 16 * 1024,
                System.currentTimeMillis(), System.currentTimeMillis()
        );

        Message registerMsg = Message.createRegister(self);
        ctx.writeAndFlush(registerMsg);
        // 注册成功后用rpcClient.call()
                rpcClient.call("你好")
                .thenAccept(response -> {
                    log.info("RPC回复:{}", response.getBody());
                });
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
//        if (msg.getType() == MessageType.TASK_RESPONSE) {
//            rpcClient.onResponse(msg);
//        } else {
////            System.out.println("收到回复:" + msg.getBody());
//            log.debug("收到回复: {}",msg.getBody());
//        }

        switch (msg.getType()) {
            case TASK_RESPONSE -> rpcClient.onResponse(msg);
            case TASK_REQUEST -> handleTaskRequest(ctx, msg);
            case REGISTER_ACK -> log.info("注册确认: {}", msg.getBody());
            default -> log.debug("收到回复: {}", msg.getBody());
        }
    }

    private void handleTaskRequest(ChannelHandlerContext ctx, Message msg) {
        // 处理请求
        log.info("收到转发任务: requestId = {}, body = {}", msg.getRequestId(), msg.getBody());

        // 暂时设计为模拟请求
        String result = "[" + this.nodeId + "] 已处理: " + msg.getBody();

        // 回复
        Message response = Message.createTaskResponse(msg.getRequestId(), result);
        ctx.writeAndFlush(response);
    }
}

package com.heteromesh.worker;

import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;
import com.heteromesh.transport.RpcClient;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;


public class ClientHandler extends SimpleChannelInboundHandler<Message> {

    private final RpcClient rpcClient;
    public ClientHandler(RpcClient client ) {
        this.rpcClient = client;
    }
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 连接建立后，主动发送一条消息
//        ctx.writeAndFlush(Message.createTaskRequest("你好，服务器！"));
        // 用rpcClient.call()
        rpcClient.call("你好")
                .thenAccept(response -> {
                    System.out.println(response.getBody());
                });
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
        if (msg.getType() == MessageType.TASK_RESPONSE) {
            rpcClient.onResponse(msg);
        } else {
            System.out.println("收到回复:" + msg.getBody());
        }
    }
}

package com.heteromesh.worker;

import com.heteromesh.protocol.Message;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

public class ClientHandler extends SimpleChannelInboundHandler<Message> {

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 连接建立后，主动发送一条消息
        ctx.writeAndFlush(Message.createTaskRequest("你好，服务器！"));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
        // 收到服务器回复
        System.out.println("收到回复: " + msg.getBody());
    }
}

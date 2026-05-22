package com.heteromesh.controller;

import com.heteromesh.protocol.Message;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j

public class ServerHandler extends SimpleChannelInboundHandler<Message> {
    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, Message message) throws Exception {
//        System.out.println("收到消息: "+ message.getBody());
        log.info("收到消息: body={}", message.getBody());
        Message reply = Message.createTaskResponse(message.getRequestId(),"成功收到消息");
        channelHandlerContext.writeAndFlush(reply);
    }

}

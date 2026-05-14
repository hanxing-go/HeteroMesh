package com.heteromesh.transport;

import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

import java.util.concurrent.atomic.AtomicInteger;

public class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    private AtomicInteger beatCnt = new AtomicInteger(0);
    private static final int MAX_MISSED = 3;
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt){
        // 捕获事件
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.ALL_IDLE) {
                // 连续三次心跳失活
                if (beatCnt.incrementAndGet() > MAX_MISSED) {
                    ctx.close();
                    return;
                }

                // 发送心跳
                ctx.writeAndFlush(Message.createPing());
            }
        }

    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object o) {
        // PING PONG不能污染上层业务，所以要处理掉
        Message msg = (Message) o;
        if (msg.getType() == MessageType.PING) {
            // 发送回应
            ctx.writeAndFlush(Message.createPong());
            // 计数器重置
            beatCnt.set(0);
        } else if (msg.getType() == MessageType.PONG) {
            // 计数器重置
            beatCnt.set(0);
        } else {
            // 如果不是PING PONG则传到上层业务
            ctx.fireChannelRead(msg);
        }
    }
}

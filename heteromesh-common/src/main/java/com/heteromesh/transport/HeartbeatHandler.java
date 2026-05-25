package com.heteromesh.transport;

import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
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
                    log.warn("连续三次心跳失活, 链接断开");
                    ctx.close();
                    return;
                }

                // 发送心跳
                ctx.writeAndFlush(Message.createPing());
                log.debug("发送心跳");
            }
        }

    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object o) {
        Message msg = (Message) o;
        if (msg.getType() == MessageType.PING) {
            // 发送回应
            ctx.writeAndFlush(Message.createPong());
            log.debug("发送回应");
            // 计数器重置
            beatCnt.set(0);
            ctx.fireChannelRead(msg);
        } else if (msg.getType() == MessageType.PONG) {
            // 计数器重置
            beatCnt.set(0);
            ctx.fireChannelRead(msg);
        } else {
            // 如果不是PING PONG则传到上层业务
            ctx.fireChannelRead(msg);
        }
    }
}

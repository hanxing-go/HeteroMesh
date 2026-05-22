package com.heteromesh.transport;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class ExceptionHandler extends ChannelInboundHandlerAdapter {
//    收到异常 →
//            ├─ IOException → 只打印消息：log.warn("连接断开: channel={}", ctx.channel().id().asShortText(),cause);
//            └─ 其他异常   → 打印完整堆栈：log.error("未预期的异常: channel={}", ctx.channel().id().asShortText(), cause);
//    最后一步（无论什么异常）：ctx.close() 关闭连接
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof IOException) {
//            System.err.println("链接断开" + ctx.channel() + cause.getMessage());
            log.warn("连接断开: channel={}", ctx.channel().id().asShortText(),cause);
        } else {
            log.error("未预期的异常: channel={}", ctx.channel().id().asShortText(), cause);
//            cause.printStackTrace();
        }
        ctx.close();
    }
}

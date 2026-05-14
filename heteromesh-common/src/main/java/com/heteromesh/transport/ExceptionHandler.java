package com.heteromesh.transport;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.io.IOException;

public class ExceptionHandler extends ChannelInboundHandlerAdapter {
//    收到异常 →
//            ├─ IOException → 只打印消息：System.err.println("连接断开: " + ctx.channel())
//            └─ 其他异常   → 打印完整堆栈：e.printStackTrace()
//    最后一步（无论什么异常）：ctx.close() 关闭连接
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof IOException) {
            System.err.println("链接断开" + ctx.channel() + cause.getMessage());
        } else {
            cause.printStackTrace();
        }
        ctx.close();
    }
}

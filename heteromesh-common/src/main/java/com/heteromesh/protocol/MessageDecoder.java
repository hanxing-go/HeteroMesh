package com.heteromesh.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import java.nio.ByteBuffer;

public class MessageDecoder extends LengthFieldBasedFrameDecoder {
    public MessageDecoder() {
        super(1024 * 1024, 6, 4, 0, 10);
    }
    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        // 1. 调用父类，切出一条完整的消息
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            // 未接收到完整消息，继续等待
            return null;
        }
        // 转换bytebuf为ByteBuffer
        byte[] bytes = new byte[frame.readableBytes()];//可读字节数
        frame.readBytes(bytes);// 读取内容
        ByteBuffer buffer = ByteBuffer.wrap(bytes);// 把字节数组包装成 Java NIO 标准的 ByteBuffer

        return MessageSerializer.decode(buffer);

    }
}

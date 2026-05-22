package com.heteromesh.protocol;

import com.heteromesh.serializer.Serializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;


public class MessageDecoder extends LengthFieldBasedFrameDecoder {
    private final Serializer serializer;
    public MessageDecoder(Serializer serializer) {
        super(1024 * 1024, 6, 4, 0, 0);
        this.serializer = serializer;
    }
    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {


        // 1. 调用父类，切出一条完整的消息
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            // 未接收到完整消息，继续等待
            return null;
        }
        //2. 手动读取字节头
        frame.readInt();// 魔数
        frame.readByte();// 版本
        // 读取类型
        frame.readByte();
        int length = frame.readInt();
        // 读取内容
        byte[] bytes = new byte[length];
        frame.readBytes(bytes);
        return serializer.deserialize(bytes);

    }
}

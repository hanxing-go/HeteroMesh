package com.heteromesh.protocol;

import com.heteromesh.serializer.Serializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;


public class MessageEncoder extends MessageToByteEncoder<Message> {
    private static final int MAGIC_NUMBER = 0xCAFEBABE;
    private static final byte VERSION = 0X01;
    private final Serializer serializer;
    // 协议头长度
    public static final int HEADER_LENGTH = 10;// 4（魔数）+1（版本）+1（类型）+4（长度）

    public MessageEncoder(Serializer serializer) {
        this.serializer = serializer;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Message msg, ByteBuf out) {
        // 接受一个 Serializer（从构造函数传入）
        byte[] bytes = serializer.serialize(msg);
        // 手动写入协议头（Magic + Version + Type + Length）...
        out.writeInt(MAGIC_NUMBER);
        out.writeByte(VERSION);
        out.writeByte(msg.getType().getCode());
        out.writeInt(bytes.length);

        out.writeBytes(bytes);
    }
}

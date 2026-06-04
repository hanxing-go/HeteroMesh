package com.heteromesh.protocol;

import com.heteromesh.serializer.SerializerRouter;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;


public class MessageEncoder extends MessageToByteEncoder<Message> {
    public static final int MAGIC_NUMBER = 0xCAFEBABE;
    public static final byte VERSION = 0X01;
    // 协议头长度
    public static final int HEADER_LENGTH = 11;// 4（魔数）+1（版本）+ 1（序列化标识）+1（类型）+4（长度）
    // 最大长度
    public static final int MAX_FRAME_LENGTH = 1024 * 1024;


    @Override
    protected void encode(ChannelHandlerContext ctx, Message msg, ByteBuf out) {
        // 接受一个 Serializer（从构造函数传入）
        byte[] bytes = SerializerRouter.select(msg).serialize(msg);
        // 手动写入协议头（Magic + Version + Type + Length）...
        out.writeInt(MAGIC_NUMBER);
        out.writeByte(VERSION);
        out.writeByte(SerializerRouter.getCode(msg));
        out.writeByte(msg.getType().getCode());
        out.writeInt(bytes.length);

        out.writeBytes(bytes);
    }
}

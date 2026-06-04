package com.heteromesh.protocol;

import com.heteromesh.serializer.Serializer;
import com.heteromesh.serializer.SerializerRouter;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;


public class MessageDecoder extends LengthFieldBasedFrameDecoder {
    public MessageDecoder() {
        super(1024 * 1024, 7, 4, 0, 0);

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
        int magic = frame.readInt();// 魔数
        if (magic != MessageEncoder.MAGIC_NUMBER) {
            throw new CorruptedFrameException(
                    "Invalid magic number: " + Integer.toHexString(magic));
        }

        int version = frame.readByte();// 版本
        if (version != MessageEncoder.VERSION) {
            throw new CorruptedFrameException(
                    "Unsupported protocol version: " + version);
        }

        byte serializerCode = frame.readByte();//读取序列化标识
        // 读取类型
        byte type = frame.readByte();
        MessageType.fromCode(type); // 调用fromCode判断type是否合法

        int length = frame.readInt();
        if (length < 0 || length > MessageEncoder.MAX_FRAME_LENGTH - MessageEncoder.HEADER_LENGTH) {
            throw new CorruptedFrameException("Invalid body length: " + length);
        }
        // 读取内容  先判断读取是否有问题
        if (frame.readableBytes() < length) {
            throw new CorruptedFrameException(
                    "Frame body is incomplete, expected " + length
                            + " bytes, actual " + frame.readableBytes());
        }
        byte[] bytes = new byte[length];
        frame.readBytes(bytes);

        Serializer s = SerializerRouter.getByCode(serializerCode);
        // 解码
        Message  message = s.deserialize(bytes);
        // 判断消息主体的type 和 协议头的type是否一致
        if (message.getType() != MessageType.fromCode(type)) {
            throw new CorruptedFrameException(
                    "Header type and body type mismatch");
        }
        return message;

    }
}

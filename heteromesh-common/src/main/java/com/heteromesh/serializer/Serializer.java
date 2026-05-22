package com.heteromesh.serializer;


import com.heteromesh.protocol.Message;

public interface Serializer {
    // 编码：Message → 字节数组
    byte[] serialize(Message message);

    // 解码：字节数组 → Message
    Message deserialize(byte[] data);

    // 序列化器名称（如 "json"、"binary"）
    String name();
}

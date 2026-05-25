package com.heteromesh.serializer;

import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;

import java.util.EnumMap;
import java.util.Map;

public class SerializerRouter {

    // code → 序列化器实例（通过 SerializerFactory 加载）
    private static final Map<SerializerCode, Serializer> CODE_MAP =
            new EnumMap<>(SerializerCode.class);

    // 消息类型 → 序列化器 code（从 YAML 读）
    private static final Map<MessageType, SerializerCode> TYPE_MAP =
            new EnumMap<>(MessageType.class);

    static {
        // 初始化 code → serializer（从 SPI 自动发现）
        CODE_MAP.put(SerializerCode.JSON, SerializerFactory.getSerializer("json"));
        CODE_MAP.put(SerializerCode.BINARY, SerializerFactory.getSerializer("binary"));

        // 初始化 type → code（从 YAML 配置读）
        for (MessageType type : MessageType.values()) {
            SerializerCode code = SerializationConfig.getSerializerCodeFor(type);
            TYPE_MAP.put(type, code);
        }
    }

    // 根据消息类型选择序列化器（发送时用）
    public static Serializer select(Message message) {
        SerializerCode code = TYPE_MAP.get(message.getType());
        if (code == null) {
            code = SerializerCode.BINARY;  // 兜底：没配置的消息类型默认用 Binary
        }
        return CODE_MAP.get(code);
    }

    // 根据协议头的 code 获取序列化器（接收时用）
    public static Serializer getByCode(byte code) {
        SerializerCode sc = SerializerCode.fromCode(code);
        return CODE_MAP.get(sc);
    }

    // 根据消息类型获取 code（写入协议头时用）
    public static byte getCode(Message message) {
        SerializerCode code = TYPE_MAP.get(message.getType());
        if (code == null) code = SerializerCode.BINARY;
        return code.getCode();
    }

}

package com.heteromesh.protocol;

/*
需求：
每个消息类型对应一个字节编号，需要两个能力：
1. 给定一个枚举值，能拿到它的编号（getCode)
2. 给定一个编号，能找回对应的枚举值（fromCode())————从网络中收到的是字节
 */
public enum MessageType {
    PING((byte) 0x01),      //心跳请求
    PONG((byte) 0x02),      //心跳应答
    TASK_REQUEST((byte) 0x10),      // 推理任务请求
    TASK_RESPONSE((byte) 0x11);      // 推理任务应答

    private final byte code;

    MessageType(byte code) {
        this.code  = code;
    }

    public byte getCode() {
        return code;
    }

    // 核心方法
    // 根据收到的字节编号，找回对应的枚举值：网络来了一个字节 0x10->TASK_REQUEST
    public static MessageType fromCode(byte code) {
        for (MessageType messageType : values()) {
            // valuse由java自动生成，返回所有枚举值
            if (code == messageType.getCode()) {
                return messageType;
            }
        }
        // 异常处理：非法参数异常
        throw new IllegalArgumentException("未知的消息类型" + code);
    }

}

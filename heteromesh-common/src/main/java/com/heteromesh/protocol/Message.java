package com.heteromesh.protocol;

import java.util.UUID;

/**
 * 统一消息实体
 *
 * 只存「业务相关」的字段，不存协议头字段（版本号、长度等）
 * 协议头由 MessageSerializer 在编码时写入、解码时读取
 */
public class Message {

    private MessageType type;
    private String requestId;  // RPC 请求ID，用于匹配请求和响应
    private String body;       // JSON 字符串，承载具体业务内容

    // 全参构造函数
    public Message(MessageType type, String requestId, String body) {
        this.type = type;
        this.requestId = requestId;
        this.body = body;
    }

    // ========== getter ==========
    public MessageType getType() { return type; }
    public String getRequestId() { return requestId; }
    public String getBody() { return body; }

    // ========== 静态工厂方法 ==========

    public static Message createPing() {
        return new Message(MessageType.PING, UUID.randomUUID().toString(), "");
    }

    public static Message createPong() {
        return new Message(MessageType.PONG, UUID.randomUUID().toString(), "");
    }

    public static Message createTaskRequest(String body) {
        return new Message(MessageType.TASK_REQUEST, UUID.randomUUID().toString(), body);
    }

    public static Message createTaskResponse(String requestId, String body) {
        return new Message(MessageType.TASK_RESPONSE, requestId, body);
    }
}

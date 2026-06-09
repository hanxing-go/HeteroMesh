package com.heteromesh.protocol;

import com.google.gson.Gson;
import com.heteromesh.registry.ServiceInstance;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * 统一消息实体
 *
 * 只存「业务相关」的字段，不存协议头字段（版本号、长度等）
 * 协议头由 MessageSerializer 在编码时写入、解码时读取
 */
@Getter
@Setter
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

    public Message() {

    }

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

    public static Message createTaskSubmit(String body) {
        return new Message(MessageType.TASK_SUBMIT, UUID.randomUUID().toString(),
                body);
    }

    public static Message createTaskSubmit(String requestId, String body) {
        return new Message(MessageType.TASK_SUBMIT, requestId, body);
    }

    public static Message createTaskResult(String requestId, String body) {
        return new Message(MessageType.TASK_RESULT, requestId, body);
    }

    public static Message createRegister(ServiceInstance instance) {
        return new Message(MessageType.REGISTER, UUID.randomUUID().toString(),
                new Gson().toJson(instance));
    }

    public static Message createAck(String requestId, String nodeId) {
        Message msg = new Message();
        msg.setType(MessageType.REGISTER_ACK);
        msg.setBody("{\"nodeId\":\"" + nodeId + "\",\"status\":\"ok\"}");
        msg.setRequestId(requestId);
        return msg;
    }



}

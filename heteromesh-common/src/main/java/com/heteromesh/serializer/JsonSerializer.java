package com.heteromesh.serializer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;

import java.nio.charset.StandardCharsets;

public class JsonSerializer implements Serializer{

    private static final Gson gson = new Gson();

    @Override
    public byte[] serialize(Message message) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", message.getType().getCode());
        envelope.addProperty("requestId", message.getRequestId());
        envelope.addProperty("payload", message.getBody());
        String envelopeJson = gson.toJson(envelope);
        // 2. 转成 UTF-8 字节（网络只能传字节）
        byte[] bodyBytes = envelopeJson.getBytes(StandardCharsets.UTF_8);

        return bodyBytes;
    }

    @Override
    public Message deserialize(byte[] data) {
        // 1. 字节 → JSON 字符串 → 解析
        String json = new String(data, StandardCharsets.UTF_8);
        JsonObject envelope = gson.fromJson(json, JsonObject.class);

        // 2. 从 JSON 里分别取出三个字段
        byte typeCode = envelope.get("type").getAsByte();               // ← 从 JSON 读 type
        MessageType type = MessageType.fromCode(typeCode);
        String requestId = envelope.get("requestId").getAsString();
        String payload = envelope.get("payload").getAsString();

        // 3. 组装
        return new Message(type, requestId, payload);
    }

    @Override
    public String name() {
        return "json";
    }
}

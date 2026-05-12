package com.heteromesh.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 消息序列化工具 —— Message 对象和 ByteBuffer 之间互相转换
 *
 * 协议格式（固定头 10 字节 + 变长消息体）：
 *   Byte 0-3 : 魔数  0xCAFEBABE（4字节，大端序）
 *   Byte 4   : 版本号 0x01
 *   Byte 5   : 消息类型编号
 *   Byte 6-9 : 消息体长度（4字节，大端序，不含头）
 *   Byte 10~ : 消息体（JSON 字符串的 UTF-8 字节）
 */
// 不过区区一百行代码，我自己来写
public class MessageSerializer {
    private static final int MAGIC_NUMBER = 0xCAFEBABE;
    private static final byte VERSION = 0X01;
    // 协议头长度
    public static final int HEADER_LENGTH = 10;// 4（魔数）+1（版本）+1（类型）+4（长度）

    private static final Gson gson = new Gson();
    /**
     * 编码：Message 对象 → 可以发送的 ByteBuffer
     *
     * Message 有三个字段：type、requestId、body
     *   - type → 写在协议头的「类型」字节里（第5字节）
     *   - requestId → 塞进 JSON 里，跟着 body 走（协议头太小了，放不下36位的UUID）
     *   - body → 也塞进 JSON 里
     *
     * 最终线路上传输的 body 是一个 JSON 信封：
     *   {"requestId":"abc-123","payload":"什么是虚拟线程？"}
     */
    public static ByteBuffer encode(Message message) {
        // 将requestId 和body打包成一个json信封
        JsonObject envelope = new JsonObject();
        envelope.addProperty("requestId", message.getRequestId());
        envelope.addProperty("payload", message.getBody());
        String envelopeJson = gson.toJson(envelope);
        // 2. 转成 UTF-8 字节（网络只能传字节）
        byte[] bodyBytes = envelopeJson.getBytes(StandardCharsets.UTF_8);
        // 计算长度
        int envelopeLength = bodyBytes.length;
        int totalLength = envelopeLength + HEADER_LENGTH;
        // 分配bytebuff
        ByteBuffer byteBuffer = ByteBuffer.allocate(totalLength);
        // 填充对应的内容
        byteBuffer.putInt(MAGIC_NUMBER);
        byteBuffer.put(VERSION);
        byteBuffer.put(message.getType().getCode());
        byteBuffer.putInt(envelopeLength);// 消息长度
        byteBuffer.put(bodyBytes);
        byteBuffer.flip();


        return byteBuffer;
    }

    /**
     * 解码：收到的 ByteBuffer → Message 对象
     *
     * 收到的 body 是一个 JSON 信封：{"requestId":"abc-123","payload":"什么是虚拟线程？"}
     * 解码后还原成 Message{type=..., requestId="abc-123", body="什么是虚拟线程？"}
     *
     * 调用前已通过 LengthFieldBasedFrameDecoder 处理粘包/半包，
     * buffer 里恰好是一条完整的消息（第2课会讲）
     */
    public static Message decode(ByteBuffer byteBuffer) {
        // 首先这里我不懂什么是粘包/半包
        // 但不管怎么说，肯定是拆包
        //1. 魔数校验，处理异常
        if (byteBuffer.getInt() != MAGIC_NUMBER) {
            throw new IllegalArgumentException("魔数不对，接收到的魔数为0x" + byteBuffer.getInt());
        }
        // 2. 读版本号（暂存备用，目前只有版本1）
        byte version = byteBuffer.get();
        // 3. 读消息类型
        MessageType type = MessageType.fromCode(byteBuffer.get());
        // 4. 读消息长度
        int length = byteBuffer.getInt();
        // 5. 读消息
        byte[] bodyBytes = new byte[length];
        byteBuffer.get(bodyBytes);
        // utf-8格式读取body
        String envelopeJson = new String(bodyBytes, StandardCharsets.UTF_8);


        // 6. 拆开 JSON 信封，还原 requestId 和原始 body
        JsonObject envelope = gson.fromJson(envelopeJson, JsonObject.class);
        String requestId = envelope.has("requestId")
                ? envelope.get("requestId").getAsString()
                : "";

        String payload = envelope.has("payload")
                ? envelope.get("payload").getAsString()
                : "";

        return new Message(type, requestId, payload);
    }

}
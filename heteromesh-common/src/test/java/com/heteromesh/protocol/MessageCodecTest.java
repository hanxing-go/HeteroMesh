package com.heteromesh.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageCodecTest {

    @Test
    void shouldEncodeAndDecodeTaskRequest() {
        Message original = Message.createTaskRequest("你好，世界！");

        byte[] bytes = MessageCodec.encode(original);
        Message decoded = MessageCodec.decode(bytes);

        assertEquals(original.getType(), decoded.getType());
        assertEquals(original.getRequestId(), decoded.getRequestId());
        assertEquals(original.getBody(), decoded.getBody());
    }

    @Test
    void shouldEncodeAndDecodePing() {
        Message ping = Message.createPing();

        byte[] bytes = MessageCodec.encode(ping);
        Message decoded = MessageCodec.decode(bytes);

        assertEquals(MessageType.PING, decoded.getType());
        assertEquals(ping.getRequestId(), decoded.getRequestId());
        assertEquals("", decoded.getBody());
    }

    @Test
    void shouldEncodeAndDecodePong() {
        Message pong = Message.createPong();

        byte[] bytes = MessageCodec.encode(pong);
        Message decoded = MessageCodec.decode(bytes);

        assertEquals(MessageType.PONG, decoded.getType());
        assertEquals(pong.getRequestId(), decoded.getRequestId());
    }

    @Test
    void shouldBeSmallerThanJson() {
        Message msg = Message.createTaskRequest("测试");

        byte[] binaryBytes = MessageCodec.encode(msg);
        java.nio.ByteBuffer jsonBuf = MessageSerializer.encode(msg);
        byte[] jsonBytes = new byte[jsonBuf.remaining()];
        jsonBuf.get(jsonBytes);

        System.out.println("===== 序列化体积对比 =====");
        System.out.println("JSON    (MessageSerializer): " + jsonBytes.length + " bytes");
        System.out.println("Binary  (MessageCodec):     " + binaryBytes.length + " bytes");
        System.out.println("节省: " + (jsonBytes.length - binaryBytes.length) + " bytes ("
                + String.format("%.1f", (1 - (double) binaryBytes.length / jsonBytes.length) * 100) + "%)");

        assertTrue(binaryBytes.length < jsonBytes.length,
                "二进制编码应该比 JSON 更小");
    }

    @Test
    void shouldHandleEmptyBody() {
        Message msg = new Message(MessageType.TASK_RESPONSE, "req-1", "");

        byte[] bytes = MessageCodec.encode(msg);
        Message decoded = MessageCodec.decode(bytes);

        assertEquals("", decoded.getBody());
        assertEquals("req-1", decoded.getRequestId());
    }

    @Test
    void shouldHandleChineseCharacters() {
        Message msg = Message.createTaskRequest("中文测试 🚀 emoji测试");

        byte[] bytes = MessageCodec.encode(msg);
        Message decoded = MessageCodec.decode(bytes);

        assertEquals(msg.getBody(), decoded.getBody());
    }
}

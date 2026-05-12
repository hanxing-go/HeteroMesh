package com.heteromesh.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MessageSerializer 的单元测试
 *
 * 验证 encode → decode 的完整闭环：编码后再解码，应该和原始消息一致
 */
class MessageSerializerTest {

    @Test
    void testEncodeDecode() {
        // ===== 第一步：创建一条原始消息 =====
        Message original = Message.createTaskRequest("总算完成了第一节课，哎，这才哪到哪啊");

        System.out.println("原始消息: type=" + original.getType()
                + ", requestId=" + original.getRequestId()
                + ", body=" + original.getBody());

        // ===== 第二步：编码（消息 → 字节） =====
        ByteBuffer buffer = MessageSerializer.encode(original);
        System.out.println("编码后总大小: " + buffer.remaining() + " 字节");

        // ===== 第三步：解码（字节 → 消息） =====
        Message decoded = MessageSerializer.decode(buffer);

        System.out.println("解码后消息: type=" + decoded.getType()
                + ", requestId=" + decoded.getRequestId()
                + ", body=" + decoded.getBody());

        // ===== 第四步：断言（验证解码后的消息和原始一致） =====
        assertEquals(original.getType(), decoded.getType(), "消息类型应该一致");
        assertEquals(original.getRequestId(), decoded.getRequestId(), "requestId 应该一致");
        assertEquals(original.getBody(), decoded.getBody(), "body 应该一致");
        assertNotNull(decoded.getType(), "消息类型不应该为空");

        System.out.println("测试通过！");
    }

    @Test
    void testPingMessage() {
        Message ping = Message.createPing();

        ByteBuffer buffer = MessageSerializer.encode(ping);
        Message decoded = MessageSerializer.decode(buffer);

        assertEquals(MessageType.PING, decoded.getType(), "应该是 PING 类型");
        assertEquals(ping.getRequestId(), decoded.getRequestId());
        assertEquals("", decoded.getBody(), "PING 消息的 body 应该为空字符串");

        System.out.println("PING 消息测试通过！");
    }
}

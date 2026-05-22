package com.heteromesh.serializer;

import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BinarySerializerTest {

    private final BinarySerializer serializer = new BinarySerializer();

    @Test
    void shouldSerializeAndDeserializeRoundTrip() {
        Message original = Message.createTaskRequest("你好，世界！");

        byte[] bytes = serializer.serialize(original);
        Message restored = serializer.deserialize(bytes);

        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getRequestId(), restored.getRequestId());
        assertEquals(original.getBody(), restored.getBody());
    }

    @Test
    void shouldHandlePingMessage() {
        Message ping = Message.createPing();

        byte[] bytes = serializer.serialize(ping);
        Message restored = serializer.deserialize(bytes);

        assertEquals(MessageType.PING, restored.getType());
        assertEquals(ping.getRequestId(), restored.getRequestId());
        assertEquals("", restored.getBody());
    }

    @Test
    void shouldBeSmallerThanJson() {
        Message msg = Message.createTaskRequest("hello");

        byte[] jsonBytes = new JsonSerializer().serialize(msg);
        byte[] binaryBytes = serializer.serialize(msg);

        assertTrue(binaryBytes.length < jsonBytes.length,
                "Binary 编码应该比 JSON 小，binary=" + binaryBytes.length + " json=" + jsonBytes.length);
    }

    @Test
    void shouldHandleEmptyBody() {
        Message original = new Message(MessageType.TASK_RESPONSE, "req-001", "");

        byte[] bytes = serializer.serialize(original);
        Message restored = serializer.deserialize(bytes);

        assertEquals("", restored.getBody());
    }

    @Test
    void shouldReturnName() {
        assertEquals("binary", serializer.name());
    }
}

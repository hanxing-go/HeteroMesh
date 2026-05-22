package com.heteromesh.serializer;

import com.heteromesh.protocol.Message;
import com.heteromesh.protocol.MessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonSerializerTest {

    private final JsonSerializer serializer = new JsonSerializer();

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
    void shouldHandleEmptyBody() {
        Message original = new Message(MessageType.TASK_RESPONSE, "req-001", "");

        byte[] bytes = serializer.serialize(original);
        Message restored = serializer.deserialize(bytes);

        assertEquals("", restored.getBody());
        assertEquals("req-001", restored.getRequestId());
    }

    @Test
    void shouldHandleSpecialCharacters() {
        Message original = Message.createTaskRequest("{\"key\":\"中文\", \"emoji\":\"😀\"}");

        byte[] bytes = serializer.serialize(original);
        Message restored = serializer.deserialize(bytes);

        assertEquals(original.getBody(), restored.getBody());
    }

    @Test
    void shouldReturnName() {
        assertEquals("json", serializer.name());
    }
}

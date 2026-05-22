package com.heteromesh.serializer;

import com.heteromesh.protocol.Message;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SerializerFactoryTest {

    @Test
    void shouldGetJsonSerializerByName() {
        Serializer serializer = SerializerFactory.getSerializer("json");

        assertNotNull(serializer);
        assertEquals("json", serializer.name());
    }

    @Test
    void shouldGetBinarySerializerByName() {
        Serializer serializer = SerializerFactory.getSerializer("binary");

        assertNotNull(serializer);
        assertEquals("binary", serializer.name());
    }

    @Test
    void shouldGetDefaultSerializer() {
        Serializer serializer = SerializerFactory.getDefault();

        assertNotNull(serializer);
        assertEquals("json", serializer.name(),
                "@SPI(\"json\") 标记了 json 为默认实现");
    }

    @Test
    void shouldSerializeAndDeserializeRoundTripWithJson() {
        Serializer serializer = SerializerFactory.getSerializer("json");
        Message original = Message.createTaskRequest("你好，SPI！");

        byte[] bytes = serializer.serialize(original);
        Message restored = serializer.deserialize(bytes);

        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getRequestId(), restored.getRequestId());
        assertEquals(original.getBody(), restored.getBody());
    }

    @Test
    void shouldSerializeAndDeserializeRoundTripWithBinary() {
        Serializer serializer = SerializerFactory.getSerializer("binary");
        Message original = Message.createTaskRequest("你好，SPI！");

        byte[] bytes = serializer.serialize(original);
        Message restored = serializer.deserialize(bytes);

        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getRequestId(), restored.getRequestId());
        assertEquals(original.getBody(), restored.getBody());
    }

    @Test
    void shouldThrowForNonExistentSerializer() {
        assertThrows(IllegalArgumentException.class, () -> {
            SerializerFactory.getSerializer("nonexistent");
        }, "不存在的序列化器应该抛出异常");
    }
}

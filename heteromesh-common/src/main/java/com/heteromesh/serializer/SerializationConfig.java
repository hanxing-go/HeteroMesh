package com.heteromesh.serializer;

import com.heteromesh.config.ConfigLoader;
import com.heteromesh.protocol.MessageType;

import java.util.Map;

public class SerializationConfig {
    @SuppressWarnings("unchecked")
    public static SerializerCode getSerializerCodeFor(MessageType type) {
        Map<String, Object> config = ConfigLoader.getConfig();
        Map<String, Object> heteromesh = (Map<String, Object>) config.get("heteromesh");
        Map<String, Object> serializer = (Map<String, Object>) heteromesh.get("serializer");
        Map<String, String> routing = (Map<String, String>) serializer.get("routing");

        String serializerName = routing.get(type.name());
        if (serializerName == null) {
            serializerName = (String) serializer.get("default");
        }
        return SerializerCode.valueOf(serializerName.toUpperCase());
    }
}

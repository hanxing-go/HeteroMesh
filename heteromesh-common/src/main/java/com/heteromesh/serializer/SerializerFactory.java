package com.heteromesh.serializer;

import com.heteromesh.spi.SpiExtensionLoader;

public class SerializerFactory {
    private static final SpiExtensionLoader<Serializer> LOADER =
            SpiExtensionLoader.load(Serializer.class);


    public static Serializer getSerializer(String name) {
        return LOADER.getExtension(name);
    }

    public static Serializer getDefault() {
        return LOADER.getDefaultExtension();
    }
}

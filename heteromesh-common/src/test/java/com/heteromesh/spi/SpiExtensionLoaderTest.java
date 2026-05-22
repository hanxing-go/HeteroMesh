package com.heteromesh.spi;

import com.heteromesh.serializer.Serializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpiExtensionLoaderTest {

    @Test
    void shouldGetExtensionByKey() {
        SpiExtensionLoader<Serializer> loader = SpiExtensionLoader.load(Serializer.class);

        Serializer json = loader.getExtension("json");
        Serializer binary = loader.getExtension("binary");

        assertNotNull(json, "应该能获取 json 实现");
        assertNotNull(binary, "应该能获取 binary 实现");
        assertEquals("json", json.name());
        assertEquals("binary", binary.name());
    }

    @Test
    void shouldGetDefaultExtension() {
        SpiExtensionLoader<Serializer> loader = SpiExtensionLoader.load(Serializer.class);

        Serializer defaultSerializer = loader.getDefaultExtension();

        assertNotNull(defaultSerializer);
        assertEquals("json", defaultSerializer.name(),
                "@SPI(\"json\") 标记了默认值为 json");
    }

    @Test
    void shouldReturnSameInstanceForSameKey() {
        SpiExtensionLoader<Serializer> loader = SpiExtensionLoader.load(Serializer.class);

        Serializer first = loader.getExtension("json");
        Serializer second = loader.getExtension("json");

        assertSame(first, second,
                "同一个 key 应该返回同一个实例（缓存验证）");
    }

    @Test
    void shouldThrowForNonExistentKey() {
        SpiExtensionLoader<Serializer> loader = SpiExtensionLoader.load(Serializer.class);

        assertThrows(IllegalArgumentException.class, () -> {
            loader.getExtension("nonexistent");
        }, "不存在的 key 应该抛出 IllegalArgumentException");
    }

    @Test
    void shouldThrowWhenNoDefaultConfigured() {
        SpiExtensionLoader<NoSpiService> loader = SpiExtensionLoader.load(NoSpiService.class);

        assertThrows(IllegalStateException.class, () -> {
            loader.getDefaultExtension();
        }, "接口没有 @SPI 注解时，getDefaultExtension 应该抛出 IllegalStateException");
    }

    @Test
    void shouldThrowForEmptyName() {
        SpiExtensionLoader<Serializer> loader = SpiExtensionLoader.load(Serializer.class);

        assertThrows(IllegalArgumentException.class, () -> {
            loader.getExtension("");
        }, "空字符串应该抛出异常");
    }

    @Test
    void shouldThrowForNullName() {
        SpiExtensionLoader<Serializer> loader = SpiExtensionLoader.load(Serializer.class);

        assertThrows(IllegalArgumentException.class, () -> {
            loader.getExtension(null);
        }, "null 应该抛出异常");
    }

    // 测试用：不带 @SPI 注解的接口
    interface NoSpiService {
        String name();
    }
}

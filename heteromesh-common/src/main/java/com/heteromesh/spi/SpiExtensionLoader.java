package com.heteromesh.spi;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SpiExtensionLoader<T> {
    private final Class<T> type;
    private final Map<String, T> cache = new ConcurrentHashMap<>();
    private volatile String defaultName;
    private volatile boolean loaded = false;


    private SpiExtensionLoader(Class<T> type) {
        this.type = type;
        SPI spi = type.getAnnotation(SPI.class);
        if (spi != null && !spi.value().isEmpty()) {
            this.defaultName = spi.value();
        }
    }

    public static <T> SpiExtensionLoader<T> load(Class<T> type) {
        return new SpiExtensionLoader<>(type);
    }

    public T getExtension(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("extension name must not be empty");
        }
        // 先查缓存
        T cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        // 触发加载
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    loadExtensions();
                    loaded = true;
                }
            }
        }

        // 加载后一定有吗？不一定，可能用户传了一个不存在的 key
        T result = cache.get(name);
        if (result == null) {
            throw new IllegalArgumentException(
                    "No SPI extension found for " + type.getName() + " with name: " + name);
        }
        return result;

    }

    public T getDefaultExtension() {
        if (defaultName == null || defaultName.isEmpty()) {
            throw new IllegalStateException(
                    "No default extension configured for " + type.getName());
        }
        return getExtension(defaultName);
    }

    private void loadExtensions() {
        String fileName = "META-INF/services/" + type.getName();
        try {
            ClassLoader classLoader = SpiExtensionLoader.class.getClassLoader();
            Enumeration<URL> urls = classLoader.getResources(fileName);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // 跳过空行和注释行（支持 # 注释）
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        // 解析 "key=className"
                        int eqIndex = line.indexOf('=');
                        if (eqIndex <= 0) {
                            continue; // 格式不对，跳过
                        }
                        String key = line.substring(0, eqIndex).trim();
                        String className = line.substring(eqIndex + 1).trim();
                        // 反射创建实例
                        Class<?> clazz = Class.forName(className);
                        @SuppressWarnings("unchecked")
                        T instance = (T) clazz.getDeclaredConstructor().newInstance();
                        cache.put(key, instance);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load SPI extensions for " + type.getName(), e);
        }
    }
}

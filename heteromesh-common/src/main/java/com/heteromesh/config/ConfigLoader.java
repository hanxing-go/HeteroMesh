package com.heteromesh.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

public class ConfigLoader {

    private static final String CONFIG_FILE = "application.yml";
    private static volatile Map<String, Object> config;

    public static Map<String, Object> load() {
        if (config != null) return config;
        synchronized (ConfigLoader.class) {
            if (config != null) return config;
            try (InputStream in = ConfigLoader.class.getClassLoader()
                    .getResourceAsStream(CONFIG_FILE)) {
                if (in == null) {
                    throw new IllegalStateException("Config file not found: " + CONFIG_FILE);
                }
                Yaml yaml = new Yaml();
                config = yaml.load(in);
                return config;
            } catch (Exception e) {
                throw new RuntimeException("Failed to load config", e);
            }
        }
    }

    public static Map<String, Object> getConfig() {
        if (config == null) load();
        return config;
    }
}
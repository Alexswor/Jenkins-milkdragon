package cn.localops.jda.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class EnvConfig {
    private final Map<String, String> values;

    public EnvConfig() {
        this.values = new LinkedHashMap<>();
    }

    public EnvConfig(Map<String, String> values) {
        this.values = new LinkedHashMap<>(values);
    }

    public Optional<String> get(String key) {
        String value = values.get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    public String getOrDefault(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    public void put(String key, String value) {
        if (key == null || !key.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("配置键必须是大写字母、数字和下划线：" + key);
        }
        values.put(key, value == null ? "" : value.strip());
    }

    public void remove(String key) {
        values.remove(key);
    }

    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(values);
    }

    public EnvConfig copy() {
        return new EnvConfig(values);
    }
}


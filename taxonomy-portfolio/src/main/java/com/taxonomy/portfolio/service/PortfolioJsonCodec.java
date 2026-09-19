package com.taxonomy.portfolio.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Central JSON codec for immutable snapshot payloads and extension attributes. */
@Service
public class PortfolioJsonCodec {

    private final ObjectMapper objectMapper;

    public PortfolioJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed("Could not serialize portfolio payload", exception);
        }
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed(
                    "Could not deserialize portfolio payload as " + type.getSimpleName(), exception);
        }
    }

    public List<Long> readLongList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.copyOf(objectMapper.readValue(json, new TypeReference<List<Long>>() {}));
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed("Could not deserialize source fragment IDs", exception);
        }
    }

    public Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, String> values = objectMapper.readValue(
                    json, new TypeReference<Map<String, String>>() {});
            return Map.copyOf(new LinkedHashMap<>(values));
        } catch (Exception exception) {
            throw PortfolioException.analysisFailed("Could not deserialize extension attributes", exception);
        }
    }
}

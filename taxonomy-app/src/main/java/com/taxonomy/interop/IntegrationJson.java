package com.taxonomy.interop;

import com.taxonomy.exchange.ReqifExchangeCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Component
public class IntegrationJson {
    private final ObjectMapper mapper;
    public IntegrationJson(ObjectMapper mapper) { this.mapper = mapper; }
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Cannot encode integration evidence"); }
    }
    public <T> T read(String value, Class<T> type) {
        if (value == null) return null;
        try { return mapper.readValue(value, type); }
        catch (Exception failure) { throw new IllegalStateException("Cannot decode versioned integration evidence"); }
    }
    public String fingerprint(Object value) { return ReqifExchangeCodec.digest(write(canonical(value)).getBytes(StandardCharsets.UTF_8)); }
    private Object canonical(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>(); map.forEach((key, item) -> result.put(key.toString(), canonical(item))); return result;
        }
        if (value instanceof Collection<?> collection) {
            var result = new ArrayList<>(collection.stream().map(this::canonical).toList());
            if (value instanceof Set<?>) result.sort(java.util.Comparator.comparing(this::write)); return result;
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> result = new TreeMap<>();
            try { for (var field : value.getClass().getRecordComponents()) result.put(field.getName(), canonical(field.getAccessor().invoke(value))); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot fingerprint integration value"); }
            return result;
        }
        return value.toString();
    }
}

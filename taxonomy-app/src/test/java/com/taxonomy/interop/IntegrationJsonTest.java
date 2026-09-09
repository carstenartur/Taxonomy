package com.taxonomy.interop;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationJsonTest {
    private final IntegrationJson json = new IntegrationJson(JsonMapper.builder().build());

    @Test
    void decodeFailurePreservesTheOriginalCause() {
        var error = assertThrows(IllegalStateException.class, () -> json.read("{", Map.class));
        assertEquals("Cannot decode versioned integration evidence", error.getMessage());
        assertNotNull(error.getCause());
    }

    @Test
    void encodeFailurePreservesTheOriginalCause() {
        Map<String, Object> recursive = new HashMap<>();
        recursive.put("self", recursive);
        var error = assertThrows(IllegalStateException.class, () -> json.write(recursive));
        assertEquals("Cannot encode integration evidence", error.getMessage());
        assertNotNull(error.getCause());
    }
}

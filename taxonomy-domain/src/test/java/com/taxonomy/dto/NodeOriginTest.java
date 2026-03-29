package com.taxonomy.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class NodeOriginTest {

    @Test
    void hasExpectedNumberOfValues() {
        assertEquals(6, NodeOrigin.values().length);
    }

    @ParameterizedTest
    @EnumSource(NodeOrigin.class)
    void valueOfReturnsEachConstant(NodeOrigin origin) {
        assertEquals(origin, NodeOrigin.valueOf(origin.name()));
    }

    @Test
    void allExpectedValuesExist() {
        assertNotNull(NodeOrigin.valueOf("DIRECT_SCORED"));
        assertNotNull(NodeOrigin.valueOf("TRACE_INTERMEDIATE"));
        assertNotNull(NodeOrigin.valueOf("PROPAGATED"));
        assertNotNull(NodeOrigin.valueOf("SEED_CONTEXT"));
        assertNotNull(NodeOrigin.valueOf("ENRICHED_LEAF"));
        assertNotNull(NodeOrigin.valueOf("IMPACT_SELECTED"));
    }

    @Test
    void invalidValueThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> NodeOrigin.valueOf("INVALID"));
    }

    @ParameterizedTest
    @EnumSource(NodeOrigin.class)
    void messageKeyStartsWithPrefix(NodeOrigin origin) {
        assertNotNull(origin.messageKey());
        assertTrue(origin.messageKey().startsWith("node.origin."),
                "messageKey should start with 'node.origin.' but was: " + origin.messageKey());
    }

    @Test
    void messageKeysAreDistinct() {
        long distinctKeys = java.util.Arrays.stream(NodeOrigin.values())
                .map(NodeOrigin::messageKey)
                .distinct()
                .count();
        assertEquals(NodeOrigin.values().length, distinctKeys);
    }
}

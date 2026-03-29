package com.taxonomy.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class RelationOriginTest {

    @Test
    void hasExpectedNumberOfValues() {
        assertEquals(5, RelationOrigin.values().length);
    }

    @ParameterizedTest
    @EnumSource(RelationOrigin.class)
    void valueOfReturnsEachConstant(RelationOrigin origin) {
        assertEquals(origin, RelationOrigin.valueOf(origin.name()));
    }

    @Test
    void allExpectedValuesExist() {
        assertNotNull(RelationOrigin.valueOf("TAXONOMY_SEED"));
        assertNotNull(RelationOrigin.valueOf("PROPAGATED_TRACE"));
        assertNotNull(RelationOrigin.valueOf("IMPACT_DERIVED"));
        assertNotNull(RelationOrigin.valueOf("SUGGESTED_CANDIDATE"));
        assertNotNull(RelationOrigin.valueOf("LLM_SUPPORTED"));
    }

    @Test
    void invalidValueThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> RelationOrigin.valueOf("INVALID"));
    }

    @ParameterizedTest
    @EnumSource(RelationOrigin.class)
    void messageKeyStartsWithPrefix(RelationOrigin origin) {
        assertNotNull(origin.messageKey());
        assertTrue(origin.messageKey().startsWith("relation.origin."),
                "messageKey should start with 'relation.origin.' but was: " + origin.messageKey());
    }

    @Test
    void messageKeysAreDistinct() {
        long distinctKeys = java.util.Arrays.stream(RelationOrigin.values())
                .map(RelationOrigin::messageKey)
                .distinct()
                .count();
        assertEquals(RelationOrigin.values().length, distinctKeys);
    }
}

package com.taxonomy.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class RelationTypeTest {

    @Test
    void hasExpectedNumberOfValues() {
        assertEquals(12, RelationType.values().length);
    }

    @ParameterizedTest
    @EnumSource(RelationType.class)
    void valueOfReturnsEachConstant(RelationType type) {
        assertEquals(type, RelationType.valueOf(type.name()));
    }

    @Test
    void allExpectedValuesExist() {
        assertNotNull(RelationType.valueOf("REALIZES"));
        assertNotNull(RelationType.valueOf("SUPPORTS"));
        assertNotNull(RelationType.valueOf("CONSUMES"));
        assertNotNull(RelationType.valueOf("USES"));
        assertNotNull(RelationType.valueOf("FULFILLS"));
        assertNotNull(RelationType.valueOf("ASSIGNED_TO"));
        assertNotNull(RelationType.valueOf("DEPENDS_ON"));
        assertNotNull(RelationType.valueOf("PRODUCES"));
        assertNotNull(RelationType.valueOf("COMMUNICATES_WITH"));
        assertNotNull(RelationType.valueOf("CONTAINS"));
        assertNotNull(RelationType.valueOf("REQUIRES"));
        assertNotNull(RelationType.valueOf("RELATED_TO"));
    }

    @Test
    void invalidValueThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> RelationType.valueOf("NONEXISTENT"));
    }
}

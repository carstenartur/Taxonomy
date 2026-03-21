package com.taxonomy.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class SeedTypeTest {

    @Test
    void hasExpectedNumberOfValues() {
        assertEquals(3, SeedType.values().length);
    }

    @ParameterizedTest
    @EnumSource(SeedType.class)
    void valueOfReturnsEachConstant(SeedType type) {
        assertEquals(type, SeedType.valueOf(type.name()));
    }

    @Test
    void allExpectedValuesExist() {
        assertNotNull(SeedType.valueOf("TYPE_DEFAULT"));
        assertNotNull(SeedType.valueOf("FRAMEWORK_SEED"));
        assertNotNull(SeedType.valueOf("SOURCE_DERIVED"));
    }

    @Test
    void invalidValueThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> SeedType.valueOf("INVALID"));
    }
}

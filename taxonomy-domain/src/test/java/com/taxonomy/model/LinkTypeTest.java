package com.taxonomy.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class LinkTypeTest {

    @Test
    void hasExpectedNumberOfValues() {
        assertEquals(6, LinkType.values().length);
    }

    @ParameterizedTest
    @EnumSource(LinkType.class)
    void valueOfReturnsEachConstant(LinkType type) {
        assertEquals(type, LinkType.valueOf(type.name()));
    }

    @Test
    void allExpectedValuesExist() {
        assertNotNull(LinkType.valueOf("IMPORTED_FROM"));
        assertNotNull(LinkType.valueOf("EXTRACTED_FROM"));
        assertNotNull(LinkType.valueOf("QUOTED_FROM"));
        assertNotNull(LinkType.valueOf("DERIVED_FROM"));
        assertNotNull(LinkType.valueOf("CONFIRMED_BY"));
        assertNotNull(LinkType.valueOf("REFERENCES"));
    }

    @Test
    void invalidValueThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> LinkType.valueOf("INVALID"));
    }
}

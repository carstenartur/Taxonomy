package com.taxonomy.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class SourceTypeTest {

    @Test
    void hasExpectedNumberOfValues() {
        assertEquals(9, SourceType.values().length);
    }

    @ParameterizedTest
    @EnumSource(SourceType.class)
    void valueOfReturnsEachConstant(SourceType type) {
        assertEquals(type, SourceType.valueOf(type.name()));
    }

    @Test
    void allExpectedValuesExist() {
        assertNotNull(SourceType.valueOf("BUSINESS_REQUEST"));
        assertNotNull(SourceType.valueOf("REGULATION"));
        assertNotNull(SourceType.valueOf("FIM_ENTRY"));
        assertNotNull(SourceType.valueOf("UPLOADED_DOCUMENT"));
        assertNotNull(SourceType.valueOf("EMAIL"));
        assertNotNull(SourceType.valueOf("MEETING_NOTE"));
        assertNotNull(SourceType.valueOf("WEB_RESOURCE"));
        assertNotNull(SourceType.valueOf("MANUAL_ENTRY"));
        assertNotNull(SourceType.valueOf("LEGACY_IMPORT"));
    }

    @Test
    void invalidValueThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> SourceType.valueOf("INVALID"));
    }
}

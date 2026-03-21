package com.taxonomy.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class HypothesisStatusTest {

    @Test
    void hasExpectedNumberOfValues() {
        assertEquals(4, HypothesisStatus.values().length);
    }

    @ParameterizedTest
    @EnumSource(HypothesisStatus.class)
    void valueOfReturnsEachConstant(HypothesisStatus status) {
        assertEquals(status, HypothesisStatus.valueOf(status.name()));
    }

    @Test
    void allExpectedValuesExist() {
        assertNotNull(HypothesisStatus.valueOf("PROVISIONAL"));
        assertNotNull(HypothesisStatus.valueOf("PROPOSED"));
        assertNotNull(HypothesisStatus.valueOf("ACCEPTED"));
        assertNotNull(HypothesisStatus.valueOf("REJECTED"));
    }

    @Test
    void invalidValueThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> HypothesisStatus.valueOf("INVALID"));
    }
}

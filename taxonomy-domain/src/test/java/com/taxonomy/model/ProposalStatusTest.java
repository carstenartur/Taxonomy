package com.taxonomy.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class ProposalStatusTest {

    @Test
    void hasExpectedNumberOfValues() {
        assertEquals(3, ProposalStatus.values().length);
    }

    @ParameterizedTest
    @EnumSource(ProposalStatus.class)
    void valueOfReturnsEachConstant(ProposalStatus status) {
        assertEquals(status, ProposalStatus.valueOf(status.name()));
    }

    @Test
    void allExpectedValuesExist() {
        assertNotNull(ProposalStatus.valueOf("PENDING"));
        assertNotNull(ProposalStatus.valueOf("ACCEPTED"));
        assertNotNull(ProposalStatus.valueOf("REJECTED"));
    }

    @Test
    void invalidValueThrowsException() {
        assertThrows(IllegalArgumentException.class,
                () -> ProposalStatus.valueOf("INVALID"));
    }
}

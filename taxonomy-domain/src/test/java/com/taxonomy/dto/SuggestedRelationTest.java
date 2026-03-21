package com.taxonomy.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SuggestedRelationTest {

    @Test
    void defaultConstructorSetsNullValues() {
        var dto = new SuggestedRelation();

        assertNull(dto.getSourceCode());
        assertNull(dto.getTargetCode());
        assertNull(dto.getRelationType());
        assertNull(dto.getReasoning());
    }

    @Test
    void fullConstructorSetsAllFields() {
        var dto = new SuggestedRelation("CP-1023", "CR-1047", "REALIZES",
                "Gap identified between capability and service");

        assertEquals("CP-1023", dto.getSourceCode());
        assertEquals("CR-1047", dto.getTargetCode());
        assertEquals("REALIZES", dto.getRelationType());
        assertEquals("Gap identified between capability and service", dto.getReasoning());
    }

    @Test
    void settersWorkCorrectly() {
        var dto = new SuggestedRelation();
        dto.setSourceCode("BP-2001");
        dto.setTargetCode("IP-3001");
        dto.setRelationType("PRODUCES");
        dto.setReasoning("Business process produces information product");

        assertEquals("BP-2001", dto.getSourceCode());
        assertEquals("IP-3001", dto.getTargetCode());
        assertEquals("PRODUCES", dto.getRelationType());
        assertEquals("Business process produces information product", dto.getReasoning());
    }
}

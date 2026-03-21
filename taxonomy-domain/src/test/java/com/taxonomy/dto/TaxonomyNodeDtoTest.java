package com.taxonomy.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaxonomyNodeDtoTest {

    @Test
    void defaultConstructorInitializesEmptyLists() {
        var dto = new TaxonomyNodeDto();

        assertNotNull(dto.getChildren());
        assertTrue(dto.getChildren().isEmpty());
        assertNotNull(dto.getOutgoingRelations());
        assertTrue(dto.getOutgoingRelations().isEmpty());
        assertNotNull(dto.getIncomingRelations());
        assertTrue(dto.getIncomingRelations().isEmpty());
    }

    @Test
    void gettersAndSettersWorkCorrectly() {
        var dto = new TaxonomyNodeDto();
        dto.setId(1L);
        dto.setCode("CP-1023");
        dto.setUuid("abc-123");
        dto.setNameEn("English Name");
        dto.setNameDe("German Name");
        dto.setDescriptionEn("English Desc");
        dto.setDescriptionDe("German Desc");
        dto.setParentCode("CP-1000");
        dto.setTaxonomyRoot("CP");
        dto.setLevel(2);
        dto.setDataset("test-dataset");
        dto.setExternalId("ext-1");
        dto.setSource("source");
        dto.setReference("ref");
        dto.setSortOrder(5);
        dto.setState("active");
        dto.setMatchPercentage(80);

        assertEquals(1L, dto.getId());
        assertEquals("CP-1023", dto.getCode());
        assertEquals("abc-123", dto.getUuid());
        assertEquals("English Name", dto.getNameEn());
        assertEquals("German Name", dto.getNameDe());
        assertEquals("English Desc", dto.getDescriptionEn());
        assertEquals("German Desc", dto.getDescriptionDe());
        assertEquals("CP-1000", dto.getParentCode());
        assertEquals("CP", dto.getTaxonomyRoot());
        assertEquals(2, dto.getLevel());
        assertEquals("test-dataset", dto.getDataset());
        assertEquals("ext-1", dto.getExternalId());
        assertEquals("source", dto.getSource());
        assertEquals("ref", dto.getReference());
        assertEquals(5, dto.getSortOrder());
        assertEquals("active", dto.getState());
        assertEquals(80, dto.getMatchPercentage());
    }

    @Test
    void backwardCompatibleGetNameReturnsEnglishName() {
        var dto = new TaxonomyNodeDto();
        dto.setNameEn("English");
        dto.setNameDe("Deutsch");

        assertEquals("English", dto.getName());
    }

    @Test
    void backwardCompatibleGetDescriptionReturnsEnglishDescription() {
        var dto = new TaxonomyNodeDto();
        dto.setDescriptionEn("English Description");
        dto.setDescriptionDe("Deutsche Beschreibung");

        assertEquals("English Description", dto.getDescription());
    }

    @Test
    void getNameReturnsNullWhenNameEnNotSet() {
        var dto = new TaxonomyNodeDto();

        assertNull(dto.getName());
    }

    @Test
    void childrenCanBeReplacedWithNewList() {
        var dto = new TaxonomyNodeDto();
        var child = new TaxonomyNodeDto();
        child.setCode("CP-1001");

        dto.setChildren(List.of(child));

        assertEquals(1, dto.getChildren().size());
        assertEquals("CP-1001", dto.getChildren().get(0).getCode());
    }

    @Test
    void childrenDefaultListIsMutable() {
        var dto = new TaxonomyNodeDto();
        var child = new TaxonomyNodeDto();
        child.setCode("CP-1001");

        dto.getChildren().add(child);

        assertEquals(1, dto.getChildren().size());
    }

    @Test
    void relationsCanBeSet() {
        var dto = new TaxonomyNodeDto();
        var outRel = new TaxonomyRelationDto();
        outRel.setRelationType("REALIZES");
        var inRel = new TaxonomyRelationDto();
        inRel.setRelationType("SUPPORTS");

        dto.setOutgoingRelations(new ArrayList<>(List.of(outRel)));
        dto.setIncomingRelations(new ArrayList<>(List.of(inRel)));

        assertEquals(1, dto.getOutgoingRelations().size());
        assertEquals("REALIZES", dto.getOutgoingRelations().get(0).getRelationType());
        assertEquals(1, dto.getIncomingRelations().size());
        assertEquals("SUPPORTS", dto.getIncomingRelations().get(0).getRelationType());
    }

    @Test
    void defaultNumericValuesAreZero() {
        var dto = new TaxonomyNodeDto();

        assertEquals(0, dto.getLevel());
        assertNull(dto.getId());
        assertNull(dto.getMatchPercentage());
        assertNull(dto.getSortOrder());
    }
}

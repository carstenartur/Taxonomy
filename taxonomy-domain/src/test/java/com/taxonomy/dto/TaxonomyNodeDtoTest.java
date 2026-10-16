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
        assertEquals(80d, dto.getMatchPercentage());
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

    @Test
    void taxonomyContainerIsDerivedFromRootMetadata() {
        for (String code : List.of("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA")) {
            var dto = taxonomyRoot(code);

            assertTrue(dto.isTaxonomyContainer(), code);
            assertEquals("CATEGORY", dto.getAnalysisRole());
        }
        assertTrue(taxonomyRoot("fixture-navigation-root").isTaxonomyContainer());
    }

    @Test
    void originalTopLevelCatalogueEntriesRemainOrdinaryNodes() {
        for (String rootCode : List.of("BP", "BR")) {
            var root = taxonomyRoot(rootCode);
            root.setNameEn("Same display name");
            var original = new TaxonomyNodeDto();
            original.setCode(rootCode + "-1000");
            original.setTaxonomyRoot(rootCode);
            original.setParentCode(rootCode);
            original.setLevel(1);
            original.setNameEn(root.getNameEn());

            assertFalse(original.isTaxonomyContainer());
            original.setLevel(0);
            original.setParentCode(null);
            assertFalse(original.isTaxonomyContainer());
        }
    }

    @Test
    void taxonomyContainerRequiresMatchingNonblankRootCode() {
        assertFalse(new TaxonomyNodeDto().isTaxonomyContainer());
        for (String invalidCode : new String[]{null, "", " \t"}) {
            var dto = taxonomyRoot(invalidCode);
            assertFalse(dto.isTaxonomyContainer());
        }
        var dto = taxonomyRoot("BP");
        dto.setTaxonomyRoot(null);
        assertFalse(dto.isTaxonomyContainer());
        dto.setTaxonomyRoot("BR");
        assertFalse(dto.isTaxonomyContainer());
    }

    @Test
    void taxonomyContainerRequiresZeroLevelAndNoParent() {
        var dto = taxonomyRoot("BP");
        dto.setLevel(1);
        assertFalse(dto.isTaxonomyContainer());
        dto.setLevel(0);
        dto.setParentCode("BR");
        assertFalse(dto.isTaxonomyContainer());
        dto.setParentCode("");
        assertTrue(dto.isTaxonomyContainer());
        dto.setParentCode(" \t");
        assertTrue(dto.isTaxonomyContainer());
        dto.setParentCode(null);
        assertTrue(dto.isTaxonomyContainer());
    }

    @Test
    void containerPresentationDoesNotChangeIdentityFingerprintsOrScores() {
        var root = taxonomyRoot("BP");
        root.setId(1L);
        root.setUuid("fixture-container-uuid");
        root.setMatchPercentage(64);
        var original = new TaxonomyNodeDto();
        original.setCode("BP-1000");
        original.setTaxonomyRoot("BP");
        original.setParentCode("BP");
        original.setLevel(1);
        original.setMatchPercentage(64);
        root.setChildren(List.of(original));
        var tree = List.of(root);
        String fingerprint = TaxonomyDataFingerprint.sha256(tree);
        String legacyFingerprint = TaxonomyDataFingerprint.legacySha256(tree);

        assertTrue(root.isTaxonomyContainer());
        assertFalse(original.isTaxonomyContainer());

        assertEquals(1L, root.getId());
        assertEquals("fixture-container-uuid", root.getUuid());
        assertEquals("BP", root.getCode());
        assertEquals("CATEGORY", root.getAnalysisRole());
        assertEquals(64d, root.getMatchPercentage());
        assertSame(original, root.getChildren().get(0));
        assertEquals("BP", original.getParentCode());
        assertEquals(64d, original.getMatchPercentage());
        assertEquals(fingerprint, TaxonomyDataFingerprint.sha256(tree));
        assertEquals(legacyFingerprint, TaxonomyDataFingerprint.legacySha256(tree));
    }

    private TaxonomyNodeDto taxonomyRoot(String code) {
        var dto = new TaxonomyNodeDto();
        dto.setCode(code);
        dto.setTaxonomyRoot(code);
        dto.setLevel(0);
        return dto;
    }
}

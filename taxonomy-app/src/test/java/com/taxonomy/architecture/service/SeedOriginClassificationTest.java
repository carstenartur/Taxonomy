package com.taxonomy.architecture.service;

import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.SeedType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for seed-origin relation detection and seed type parsing
 * in {@link RequirementArchitectureViewService}.
 */
class SeedOriginClassificationTest {

    @Test
    void rootToRootRelationIsSeedOrigin() {
        TaxonomyRelationDto rel = createRelation("CP", "CR");
        assertThat(RequirementArchitectureViewService.isSeedOriginRelation(rel)).isTrue();
    }

    @Test
    void leafToLeafRelationIsNotSeedOrigin() {
        TaxonomyRelationDto rel = createRelation("CP-1023", "CR-1047");
        assertThat(RequirementArchitectureViewService.isSeedOriginRelation(rel)).isFalse();
    }

    @Test
    void rootToLeafRelationIsNotSeedOrigin() {
        TaxonomyRelationDto rel = createRelation("CP", "CR-1047");
        assertThat(RequirementArchitectureViewService.isSeedOriginRelation(rel)).isFalse();
    }

    @Test
    void leafToRootRelationIsNotSeedOrigin() {
        TaxonomyRelationDto rel = createRelation("CP-1023", "CR");
        assertThat(RequirementArchitectureViewService.isSeedOriginRelation(rel)).isFalse();
    }

    @Test
    void nullSourceReturnsNotSeed() {
        TaxonomyRelationDto rel = createRelation(null, "CR");
        assertThat(RequirementArchitectureViewService.isSeedOriginRelation(rel)).isFalse();
    }

    @Test
    void nullTargetReturnsNotSeed() {
        TaxonomyRelationDto rel = createRelation("CP", null);
        assertThat(RequirementArchitectureViewService.isSeedOriginRelation(rel)).isFalse();
    }

    @Test
    void parseSeedTypeDefaultsToTypeDefault() {
        assertThat(RequirementArchitectureViewService.parseSeedType(null))
                .isEqualTo(SeedType.TYPE_DEFAULT);
    }

    @Test
    void parseSeedTypeRecognisesFramework() {
        assertThat(RequirementArchitectureViewService.parseSeedType("FRAMEWORK_SEED"))
                .isEqualTo(SeedType.FRAMEWORK_SEED);
        assertThat(RequirementArchitectureViewService.parseSeedType("framework seed"))
                .isEqualTo(SeedType.FRAMEWORK_SEED);
    }

    @Test
    void parseSeedTypeRecognisesSourceDerived() {
        assertThat(RequirementArchitectureViewService.parseSeedType("SOURCE_DERIVED"))
                .isEqualTo(SeedType.SOURCE_DERIVED);
        assertThat(RequirementArchitectureViewService.parseSeedType("derived from regulation"))
                .isEqualTo(SeedType.SOURCE_DERIVED);
    }

    @Test
    void parseSeedTypeUnknownStringDefaultsToTypeDefault() {
        assertThat(RequirementArchitectureViewService.parseSeedType("random text"))
                .isEqualTo(SeedType.TYPE_DEFAULT);
    }

    @Test
    void allEightRootCategoriesDetectedAsSeedOrigin() {
        for (String src : new String[]{"BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA"}) {
            for (String tgt : new String[]{"BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA"}) {
                if (src.equals(tgt)) continue;
                TaxonomyRelationDto rel = createRelation(src, tgt);
                assertThat(RequirementArchitectureViewService.isSeedOriginRelation(rel))
                        .as("Relation %s → %s should be seed-origin", src, tgt)
                        .isTrue();
            }
        }
    }

    private static TaxonomyRelationDto createRelation(String source, String target) {
        TaxonomyRelationDto rel = new TaxonomyRelationDto();
        rel.setSourceCode(source);
        rel.setTargetCode(target);
        rel.setRelationType("SUPPORTS");
        return rel;
    }
}

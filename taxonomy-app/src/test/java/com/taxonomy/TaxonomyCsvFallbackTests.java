package com.taxonomy;

import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the CSV fallback loading works correctly at application startup
 * when no Relations sheet is present in the Excel workbook.
 *
 * Uses a fresh Spring context (via {@link DirtiesContext}) to observe the state
 * directly after {@code @PostConstruct} runs, before any test cleanup.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class TaxonomyCsvFallbackTests {

    @Autowired
    private TaxonomyRelationService relationService;

    @Test
    void csvFallbackLoadsRelationsOnStartup() {
        // 24 original TYPE_DEFAULT + 12 FRAMEWORK_SEED = 36 relations
        assertThat(relationService.countRelations()).isGreaterThanOrEqualTo(36);
    }

    @Test
    void csvFallbackContainsKnownRelationCpRealizesCr() {
        // CP → CR with REALIZES is a required relation from the CSV fallback data
        List<TaxonomyRelationDto> cpRelations = relationService.getRelationsForNode("CP");
        assertThat(cpRelations).isNotEmpty();
        assertThat(cpRelations).anyMatch(r ->
                "CP".equals(r.getSourceCode())
                && "CR".equals(r.getTargetCode())
                && "REALIZES".equals(r.getRelationType()));
    }

    @Test
    void csvFallbackRelationsHaveProvenancePrefix() {
        // All relations loaded from the CSV must have a provenance starting with "csv-"
        List<TaxonomyRelationDto> all = relationService.getAllRelations();
        assertThat(all).isNotEmpty();
        assertThat(all).allMatch(r -> r.getProvenance() != null && r.getProvenance().startsWith("csv-"));
    }

    @Test
    void csvFallbackContainsFrameworkSeedRelations() {
        // The new FRAMEWORK_SEED relations should have csv-framework provenance
        List<TaxonomyRelationDto> all = relationService.getAllRelations();
        assertThat(all).anyMatch(r -> r.getProvenance() != null && r.getProvenance().startsWith("csv-framework"));
    }

    @Test
    void csvFallbackContainsNewRequiresRelation() {
        // CP → IP with REQUIRES is a new FRAMEWORK_SEED relation
        List<TaxonomyRelationDto> cpRelations = relationService.getRelationsForNode("CP");
        assertThat(cpRelations).anyMatch(r ->
                "CP".equals(r.getSourceCode())
                && "IP".equals(r.getTargetCode())
                && "REQUIRES".equals(r.getRelationType()));
    }
}

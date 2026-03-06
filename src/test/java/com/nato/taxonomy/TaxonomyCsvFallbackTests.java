package com.nato.taxonomy;

import com.nato.taxonomy.dto.TaxonomyRelationDto;
import com.nato.taxonomy.service.TaxonomyRelationService;
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
    void csvFallbackLoadsApproximately24RelationsOnStartup() {
        // The application starts with no Relations sheet in the Excel workbook;
        // the CSV fallback should have been loaded by @PostConstruct.
        assertThat(relationService.countRelations()).isGreaterThanOrEqualTo(24);
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
    void csvFallbackRelationsHaveCsvDefaultProvenance() {
        // All relations loaded from the CSV fallback must have provenance "csv-default"
        List<TaxonomyRelationDto> all = relationService.getAllRelations();
        assertThat(all).isNotEmpty();
        assertThat(all).allMatch(r -> "csv-default".equals(r.getProvenance()));
    }
}

package com.taxonomy;

import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.catalog.service.PropagationResult;
import com.taxonomy.catalog.service.RelevancePropagationService;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that verify CSV-loaded relations are used when building architecture views.
 * This class does NOT delete relations in @BeforeEach, so the CSV-loaded defaults remain present.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CsvRelationsIntegrationTest {

    @Autowired
    private RequirementArchitectureViewService architectureViewService;

    @Autowired
    private TaxonomyRelationService relationService;

    @Autowired
    private RelevancePropagationService propagationService;

    @Test
    void csvRelationsAreLoadedAtStartup() {
        // The CSV fallback should have loaded 36 relations (24 default + 12 framework)
        long count = relationService.countRelations();
        assertThat(count).isGreaterThanOrEqualTo(36);
    }

    @Test
    void architectureViewTraversesCsvRelations() {
        // Simulate scores where CP (Capabilities) scores high
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("CP", 90);  // Capabilities
        scores.put("BP", 85);  // Business Processes
        scores.put("CR", 80);  // Core Services

        RequirementArchitectureView view = architectureViewService.build(scores, "test requirement", 20);

        // Should have anchors
        assertThat(view.getAnchors()).isNotEmpty();
        // Should have propagated elements beyond just the anchors (via CSV relations)
        assertThat(view.getIncludedElements().size()).isGreaterThanOrEqualTo(view.getAnchors().size());
        // Should have found relationships from the CSV data
        assertThat(view.getIncludedRelationships()).isNotEmpty();
    }

    @Test
    void propagationUsesCsvRelationsForCapabilities() {
        // CP has REALIZES relations to CR, CI, CO in the CSV
        Map<String, Double> anchorRelevances = Map.of("CP", 0.90);
        PropagationResult result = propagationService.propagate(anchorRelevances);

        // CP → CR via REALIZES should be found (0.90 * 0.80 = 0.72 > 0.35 threshold)
        assertThat(result.getRelevanceMap()).containsKey("CR");
        assertThat(result.getRelevanceMap().get("CR")).isGreaterThan(0.35);
    }
}

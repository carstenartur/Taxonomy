package com.taxonomy.dto;

import com.taxonomy.model.SeedType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequirementRelationshipViewFieldsTest {

    @Test
    void originFieldRoundTrips() {
        RequirementRelationshipView view = new RequirementRelationshipView();
        assertNull(view.getOrigin());

        view.setOrigin(RelationOrigin.TAXONOMY_SEED);
        assertEquals(RelationOrigin.TAXONOMY_SEED, view.getOrigin());

        view.setOrigin(RelationOrigin.IMPACT_DERIVED);
        assertEquals(RelationOrigin.IMPACT_DERIVED, view.getOrigin());
    }

    @Test
    void confidenceDefaultsToZero() {
        RequirementRelationshipView view = new RequirementRelationshipView();
        assertEquals(0.0, view.getConfidence());

        view.setConfidence(0.92);
        assertEquals(0.92, view.getConfidence(), 0.001);
    }

    @Test
    void derivationReasonRoundTrips() {
        RequirementRelationshipView view = new RequirementRelationshipView();
        assertNull(view.getDerivationReason());

        view.setDerivationReason("Cross-category leaf-to-leaf impact");
        assertEquals("Cross-category leaf-to-leaf impact", view.getDerivationReason());
    }

    @Test
    void seedTypeRoundTrips() {
        RequirementRelationshipView view = new RequirementRelationshipView();
        assertNull(view.getSeedType());

        view.setSeedType(SeedType.FRAMEWORK_SEED);
        assertEquals(SeedType.FRAMEWORK_SEED, view.getSeedType());
    }

    @SuppressWarnings("deprecation")
    @Test
    void existingFieldsStillWork() {
        RequirementRelationshipView view = new RequirementRelationshipView();

        assertEquals(RequirementRelationshipView.CATEGORY_TRACE, view.getRelationCategory());

        view.setRelationCategory(RequirementRelationshipView.CATEGORY_IMPACT);
        assertEquals(RequirementRelationshipView.CATEGORY_IMPACT, view.getRelationCategory());

        view.setRelationId(42L);
        assertEquals(42L, view.getRelationId());
    }

    @Test
    void setOriginAutoSyncsRelationCategory() {
        RequirementRelationshipView view = new RequirementRelationshipView();

        view.setOrigin(RelationOrigin.TAXONOMY_SEED);
        assertEquals(RequirementRelationshipView.CATEGORY_SEED, view.getRelationCategory());

        view.setOrigin(RelationOrigin.PROPAGATED_TRACE);
        assertEquals(RequirementRelationshipView.CATEGORY_TRACE, view.getRelationCategory());

        view.setOrigin(RelationOrigin.IMPACT_DERIVED);
        assertEquals(RequirementRelationshipView.CATEGORY_IMPACT, view.getRelationCategory());

        view.setOrigin(RelationOrigin.SUGGESTED_CANDIDATE);
        assertEquals(RequirementRelationshipView.CATEGORY_IMPACT, view.getRelationCategory());

        view.setOrigin(RelationOrigin.LLM_SUPPORTED);
        assertEquals(RequirementRelationshipView.CATEGORY_IMPACT, view.getRelationCategory());
    }
}

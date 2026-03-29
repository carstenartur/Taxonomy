package com.taxonomy.dto;

import com.taxonomy.model.SeedType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequirementElementViewFieldsTest {

    @Test
    void originFieldRoundTrips() {
        RequirementElementView view = new RequirementElementView();
        assertNull(view.getOrigin());

        view.setOrigin(NodeOrigin.DIRECT_SCORED);
        assertEquals(NodeOrigin.DIRECT_SCORED, view.getOrigin());

        view.setOrigin(NodeOrigin.PROPAGATED);
        assertEquals(NodeOrigin.PROPAGATED, view.getOrigin());
    }

    @Test
    void taxonomyDepthDefaultsToZero() {
        RequirementElementView view = new RequirementElementView();
        assertEquals(0, view.getTaxonomyDepth());

        view.setTaxonomyDepth(3);
        assertEquals(3, view.getTaxonomyDepth());
    }

    @Test
    void specificityScoreDefaultsToZero() {
        RequirementElementView view = new RequirementElementView();
        assertEquals(0.0, view.getSpecificityScore());

        view.setSpecificityScore(0.85);
        assertEquals(0.85, view.getSpecificityScore(), 0.001);
    }

    @Test
    void scoringPathRoundTrips() {
        RequirementElementView view = new RequirementElementView();
        assertNull(view.getScoringPath());

        view.setScoringPath("CP(92%) > CP-1000(90%) > CP-1023(85%)");
        assertEquals("CP(92%) > CP-1000(90%) > CP-1023(85%)", view.getScoringPath());
    }

    @Test
    void directLlmScoreDefaultsToZero() {
        RequirementElementView view = new RequirementElementView();
        assertEquals(0, view.getDirectLlmScore());

        view.setDirectLlmScore(85);
        assertEquals(85, view.getDirectLlmScore());
    }

    @Test
    void selectedForImpactDefaultsToFalse() {
        RequirementElementView view = new RequirementElementView();
        assertFalse(view.isSelectedForImpact());

        view.setSelectedForImpact(true);
        assertTrue(view.isSelectedForImpact());
    }
}

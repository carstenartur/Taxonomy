package com.taxonomy.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipelineConstantsTest {

    @Test
    void anchorThresholdsAreConsistent() {
        assertTrue(PipelineConstants.ANCHOR_THRESHOLD_HIGH > PipelineConstants.ANCHOR_THRESHOLD_LOW,
                "high threshold must be greater than low threshold");
        assertTrue(PipelineConstants.ANCHOR_THRESHOLD_LOW > 0,
                "low threshold must be positive");
        assertTrue(PipelineConstants.MIN_ANCHORS >= 1,
                "must require at least one anchor");
    }

    @Test
    void leafEnrichmentConstants() {
        assertTrue(PipelineConstants.MAX_LEAF_ENRICHMENT >= 1);
        assertTrue(PipelineConstants.LEAF_ENRICHMENT_MIN_SCORE >= 0);
    }
}

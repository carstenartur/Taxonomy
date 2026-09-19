package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.dto.RequirementRelationshipView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImpactRelationStepOrderingTest {

    @Test
    void relationTypeBreaksEqualImpactScoreTieDeterministically() {
        RequirementRelationshipView depends = impact(
                "CO-1011", "CR-1047", "DEPENDS_ON", 0.75);
        RequirementRelationshipView supports = impact(
                "CO-1011", "CR-1047", "SUPPORTS", 0.75);
        List<RequirementRelationshipView> relationships =
                new ArrayList<>(List.of(depends, supports));

        ImpactRelationStep.rankRelationships(relationships);

        assertThat(relationships).containsExactly(supports, depends);
    }

    @Test
    void architectureLayerBreaksEqualRelationTypeAndScoreTieDeterministically() {
        RequirementRelationshipView communications = impact(
                "UA-1574", "CO-1011", "USES", 0.62);
        RequirementRelationshipView core = impact(
                "UA-1574", "CR-1047", "USES", 0.62);
        List<RequirementRelationshipView> relationships =
                new ArrayList<>(List.of(communications, core));

        ImpactRelationStep.rankRelationships(relationships);

        assertThat(relationships).containsExactly(core, communications);
    }

    private static RequirementRelationshipView impact(
            String source, String target, String type, double score) {
        RequirementRelationshipView relationship = new RequirementRelationshipView();
        relationship.setSourceCode(source);
        relationship.setTargetCode(target);
        relationship.setRelationType(type);
        relationship.setConfidence(score);
        relationship.setPropagatedRelevance(score);
        relationship.setOrigin(RelationOrigin.IMPACT_DERIVED);
        relationship.setIncludedBecause(source + " -> " + target);
        return relationship;
    }
}

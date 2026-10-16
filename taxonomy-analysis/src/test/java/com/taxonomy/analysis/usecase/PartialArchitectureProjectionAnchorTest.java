package com.taxonomy.analysis.usecase;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.NodeOrigin;
import com.taxonomy.dto.RequirementAnchor;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.dto.TaxonomyNodeDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PartialArchitectureProjectionAnchorTest {
    @Test
    void oneAcceptedDirectAssessmentCreatesAnAnchorFromEffectiveEvidence() {
        var result = assessment(Map.of("IP-1018", 70d), List.of(familyTree()));

        var view = PartialArchitectureProjection.complete(result, null, 10);

        assertThat(view.getIncludedElements()).extracting(RequirementElementView::getNodeCode)
                .containsExactly("IP-1018");
        assertThat(view.getIncludedElements().getFirst().isAnchor()).isTrue();
        assertThat(view.getAnchors()).singleElement().satisfies(anchor -> {
            assertThat(anchor.getNodeCode()).isEqualTo("IP-1018");
            assertThat(anchor.getDirectScore()).isEqualTo(70);
        });
        assertThat(view.getTotalAnchors()).isEqualTo(1);
    }

    @Test
    void multipleDirectAssessmentsAreIdempotentAndKeepVerifiedRelationships() {
        var tree = productTree();
        var result = assessment(Map.of("IP-1065", 80d, "IP-1011", 70d), List.of(tree));
        var view = new RequirementArchitectureView();
        var verified = new RequirementRelationshipView();
        verified.setSourceCode("IP-1065");
        verified.setTargetCode("IP-1011");
        verified.setRelationType("SUPPORTS");
        verified.setOrigin(RelationOrigin.LLM_SUPPORTED);
        view.getIncludedRelationships().add(verified);

        PartialArchitectureProjection.complete(result, view, 10);
        PartialArchitectureProjection.complete(result, view, 10);

        assertThat(view.getIncludedElements()).extracting(RequirementElementView::getNodeCode)
                .containsExactlyInAnyOrder("IP-1065", "IP-1011");
        assertThat(view.getIncludedElements()).allMatch(RequirementElementView::isAnchor);
        assertThat(view.getAnchors()).extracting(RequirementAnchor::getNodeCode)
                .containsExactlyInAnyOrder("IP-1065", "IP-1011");
        assertThat(view.getAnchors()).filteredOn(anchor -> anchor.getNodeCode().equals("IP-1011"))
                .singleElement().satisfies(anchor -> {
                    assertThat(anchor.getDirectScore()).isEqualTo(56);
                    assertThat(anchor.getReason()).contains("raw score 70%", "effective relevance 56%");
                });
        assertThat(view.getTotalAnchors()).isEqualTo(2);
        assertThat(view.getIncludedElements()).filteredOn(element -> element.getNodeCode().equals("IP-1011"))
                .singleElement().satisfies(element -> {
                    assertThat(element.getScoreDetail().isProductSuitability()).isTrue();
                    assertThat(element.getDirectLlmScore()).isEqualTo(70);
                    assertThat(element.getRelevance()).isEqualTo(0.56);
                    assertThat(element.getScoreDetail().effectiveRelevance()).isEqualTo(56);
                });
        assertThat(view.getIncludedRelationships()).containsExactly(verified);
        assertThat(view.getTotalRelationships()).isEqualTo(1);
        assertThat(view.getIncludedElements()).filteredOn(element -> element.getNodeCode().equals("IP-1011"))
                .singleElement().satisfies(element -> assertThat(element.getParentNodeCode()).isEqualTo("IP-1065"));
    }

    @Test
    void preservesExistingValidAnchorWithoutDuplicatingItOnRepeatedCompletion() {
        var result = assessment(Map.of("IP-1018", 70d), List.of(familyTree()));
        var view = new RequirementArchitectureView();
        var element = new RequirementElementView();
        element.setNodeCode("IP-1018");
        element.setOrigin(NodeOrigin.DIRECT_SCORED);
        element.setAnchor(true);
        view.getIncludedElements().add(element);
        var existing = new RequirementAnchor("IP-1018", 70, "existing accepted assessment");
        view.getAnchors().add(existing);

        PartialArchitectureProjection.complete(result, view, 10);
        PartialArchitectureProjection.complete(result, view, 10);

        assertThat(view.getAnchors()).containsExactly(existing);
        assertThat(view.getTotalAnchors()).isEqualTo(1);
        assertThat(view.getIncludedElements()).singleElement().isSameAs(element);
    }

    @Test
    void excludesContainerZeroUnassessedAndOverLimitNodesFromAnchors() {
        var root = node("IP", null, "IP", 0, "Information Products");
        root.getChildren().add(node("IP-1018", "IP", "IP", 1, "Asset State Reports"));
        root.getChildren().add(node("IP-1021", "IP", "IP", 1, "Capability Reports"));
        var result = assessment(Map.of("IP", 100d, "IP-1018", 80d, "IP-1021", 0d, "IP-9999", 95d), List.of(root));

        var view = PartialArchitectureProjection.complete(result, null, 1);

        assertThat(view.getIncludedElements()).extracting(RequirementElementView::getNodeCode)
                .containsExactly("IP-1018");
        assertThat(view.getAnchors()).extracting(RequirementAnchor::getNodeCode).containsExactly("IP-1018");
        assertThat(view.getTotalAnchors()).isEqualTo(1);
        assertThat(view.getIncludedElements()).noneMatch(element -> element.getNodeCode().equals("IP-1021")
                || element.getNodeCode().equals("IP-9999") || element.getNodeCode().equals("IP"));
    }

    private static AnalysisResult assessment(Map<String, Double> scores, List<TaxonomyNodeDto> tree) {
        var result = new AnalysisResult(scores, tree);
        result.setStatus("PARTIAL");
        return result;
    }

    private static TaxonomyNodeDto familyTree() {
        var root = node("IP", null, "IP", 0, "Information Products");
        root.getChildren().add(node("IP-1018", "IP", "IP", 1, "Asset State Reports"));
        root.getChildren().add(node("IP-1021", "IP", "IP", 1, "Capability Reports"));
        return root;
    }

    private static TaxonomyNodeDto productTree() {
        var root = node("IP", null, "IP", 0, "Information Products");
        var parent = node("IP-1065", "IP", "IP", 1, "Product category");
        parent.getChildren().add(node("IP-1011", "IP-1065", "IP", 2, "Target Controls"));
        root.getChildren().add(parent);
        return root;
    }

    private static TaxonomyNodeDto node(String code, String parent, String root, int level, String name) {
        var node = new TaxonomyNodeDto();
        node.setCode(code);
        node.setParentCode(parent);
        node.setTaxonomyRoot(root);
        node.setLevel(level);
        node.setNameEn(name);
        if (code.equals("IP-1011")) node.setAnalysisRole("PRODUCT");
        if (code.equals("IP-1018") || code.equals("IP-1021")) node.setAnalysisRole("PRODUCT_FAMILY");
        return node;
    }
}

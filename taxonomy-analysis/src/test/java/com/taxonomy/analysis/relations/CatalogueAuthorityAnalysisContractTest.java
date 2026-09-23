package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.service.AiPromptBudgetPolicy;
import com.taxonomy.analysis.service.AnalysisRelationGenerator;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.api.TaxonomyNodeLookup;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.CatalogueNodeOrigin;
import com.taxonomy.dto.RelationSearchModel.Node;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CatalogueAuthorityAnalysisContractTest {

    @Test
    void legacyHypothesesNeverUseLocalNavigationAsEndpoint() {
        TaxonomyNode process = node("BP-1", "BP", CatalogueNodeOrigin.OFFICIAL_SOURCE);
        TaxonomyNode local = node("local:ip:reports", "IP", CatalogueNodeOrigin.LOCAL_NAVIGATION);
        TaxonomyNode official = node("IP-1", "IP", CatalogueNodeOrigin.OFFICIAL_SOURCE);
        Map<String, TaxonomyNode> nodes = Map.of(
                process.getCode(), process, local.getCode(), local, official.getCode(), official);
        TaxonomyNodeLookup lookup = code -> Optional.ofNullable(nodes.get(code));

        AnalysisRelationGenerator generator =
                new AnalysisRelationGenerator(new RelationCompatibilityMatrix(), lookup);
        var result = generator.generate(Map.of(
                process.getCode(), 90,
                local.getCode(), 99,
                official.getCode(), 80));

        assertThat(result).isNotEmpty();
        assertThat(result)
                .allSatisfy(hypothesis -> {
                    assertThat(hypothesis.getSourceCode()).isNotEqualTo(local.getCode());
                    assertThat(hypothesis.getTargetCode()).isNotEqualTo(local.getCode());
                });
        assertThat(result).anySatisfy(hypothesis ->
                assertThat(hypothesis.getTargetCode()).isEqualTo(official.getCode()));
    }

    @Test
    void hierarchicalSearchTreatsLocalNavigationAsContainerEvenWithAParent() throws Exception {
        TaxonomyNode local = node("local:ip:reports", "IP", CatalogueNodeOrigin.LOCAL_NAVIGATION);
        local.setParentCode("IP");

        Method scalar = RequirementRelationSearchService.class
                .getDeclaredMethod("scalar", TaxonomyNode.class, String.class);
        scalar.setAccessible(true);
        Node candidate = (Node) scalar.invoke(null, local, "local navigation helper");

        assertThat(candidate.container())
                .as("navigation-only nodes can guide descent but must never verify as endpoints")
                .isTrue();
    }

    private static TaxonomyNode node(String code, String root, CatalogueNodeOrigin origin) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(code);
        node.setTaxonomyRoot(root);
        node.setCatalogueOrigin(origin);
        return node;
    }
}

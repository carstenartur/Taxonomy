package com.taxonomy.analysis.service;

import com.taxonomy.analysis.relations.RequirementRelationSearchService;
import com.taxonomy.catalog.api.TaxonomyNodeLookup;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dto.CatalogueNodeOrigin;
import com.taxonomy.dto.RelationSearchModel;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LocalNavigationRelationBoundaryTest {

    @Test
    void legacyGeneratorNeverUsesLocalNavigationAsRelationEndpoint() {
        TaxonomyNode process = node("BP-1", "BP", CatalogueNodeOrigin.OFFICIAL_SOURCE);
        TaxonomyNode local = node("local:ip:navigation:test", "IP", CatalogueNodeOrigin.LOCAL_NAVIGATION);
        Map<String, TaxonomyNode> nodes = Map.of(process.getCode(), process, local.getCode(), local);
        TaxonomyNodeLookup lookup = code -> Optional.ofNullable(nodes.get(code));

        var generated = new AnalysisRelationGenerator(new RelationCompatibilityMatrix(), lookup)
                .generate(Map.of(process.getCode(), 90, local.getCode(), 90));

        assertThat(generated)
                .noneMatch(edge -> local.getCode().equals(edge.getSourceCode())
                        || local.getCode().equals(edge.getTargetCode()));
    }

    @Test
    void relationDownwalkTreatsLocalNavigationAsContainerNotEndpoint() throws Exception {
        TaxonomyNode local = node("local:ip:navigation:test", "IP", CatalogueNodeOrigin.LOCAL_NAVIGATION);
        local.setParentCode("IP-1000");

        Method scalar = RequirementRelationSearchService.class
                .getDeclaredMethod("scalar", TaxonomyNode.class, String.class);
        scalar.setAccessible(true);
        RelationSearchModel.Node projected =
                (RelationSearchModel.Node) scalar.invoke(null, local, "navigation only");

        assertThat(projected.container()).isTrue();
    }

    @Test
    void officialIntermediateNodeRemainsEligibleEndpoint() throws Exception {
        TaxonomyNode official = node("IP-1000", "IP", CatalogueNodeOrigin.OFFICIAL_SOURCE);
        official.setParentCode("IP");

        Method scalar = RequirementRelationSearchService.class
                .getDeclaredMethod("scalar", TaxonomyNode.class, String.class);
        scalar.setAccessible(true);
        RelationSearchModel.Node projected =
                (RelationSearchModel.Node) scalar.invoke(null, official, "official intermediate");

        assertThat(projected.container()).isFalse();
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

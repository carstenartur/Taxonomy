package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextBatchEfficiencyTest {
    @BeforeAll static void load() throws Exception { TaxonomyHierarchyContextTest.loadRealWorkbook(); }
    private List<TaxonomyNode> siblings() {
        var nodes=TaxonomyHierarchyContextTest.nodes;
        return nodes.values().stream().filter(n -> "BP".equals(n.getTaxonomyRoot()) && n.getParentCode()!=null)
            .filter(n -> nodes.containsKey(n.getParentCode()) && !nodes.get(n.getParentCode()).getDescriptionEn().isBlank())
            .filter(n -> !n.getDescriptionEn().contains(nodes.get(n.getParentCode()).getDescriptionEn()))
            .collect(Collectors.groupingBy(TaxonomyNode::getParentCode,TreeMap::new,Collectors.toList()))
            .values().stream().filter(v -> v.size()>=2).findFirst().orElseThrow().subList(0,2);
    }
    @Test void siblingContextIsSentOnlyOnce() {
        var offered=siblings();
        String inherited=TaxonomyHierarchyContextTest.nodes.get(offered.getFirst().getParentCode()).getDescriptionEn();
        String prompt=TaxonomyHierarchyContextTest.prompt(offered);
        assertThat(prompt).contains(inherited);
        assertThat(prompt.indexOf(inherited)).as("A shared ancestor must not be repeated for each child")
            .isEqualTo(prompt.lastIndexOf(inherited));
    }
    @Test void ancestorLookupIsSharedWithinTheBatch() {
        var offered=siblings();
        clearInvocations(TaxonomyHierarchyContextTest.repository);
        TaxonomyHierarchyContextTest.catalogue.getAssessmentDescriptions(offered);
        verify(TaxonomyHierarchyContextTest.repository,times(1)).findByCode(offered.getFirst().getParentCode());
    }
    @Test void duplicateCandidateIdentityIsRejectedBeforeReads() {
        var node=siblings().getFirst();
        clearInvocations(TaxonomyHierarchyContextTest.repository);
        assertThatThrownBy(() -> TaxonomyHierarchyContextTest.catalogue.getAssessmentDescriptions(List.of(node,node)))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(TaxonomyHierarchyContextTest.repository);
    }
}

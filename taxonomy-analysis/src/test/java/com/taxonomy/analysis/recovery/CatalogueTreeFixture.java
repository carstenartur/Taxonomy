package com.taxonomy.analysis.recovery;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository.TreeRow;
import com.taxonomy.dto.TaxonomyNodeDto;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.when;

final class CatalogueTreeFixture {
    private CatalogueTreeFixture() { }

    static void stub(TaxonomyNodeRepository repository, List<TaxonomyNode> roots) {
        List<TaxonomyNode> nodes = new ArrayList<>();
        roots.forEach(root -> flatten(nodes, root, null));
        when(repository.findAllInTreeOrder()).thenAnswer(call -> nodes.stream().map(CatalogueTreeFixture::row).toList());
    }

    private static void flatten(List<TaxonomyNode> nodes, TaxonomyNode node, TaxonomyNode parent) {
        node.setId(nodes.size() + 1L);
        node.setParent(parent);
        nodes.add(node);
        node.getChildren().forEach(child -> flatten(nodes, child, node));
    }

    private static TreeRow row(TaxonomyNode node) {
        var result = new TaxonomyNodeDto();
        result.setId(node.getId());
        result.setCode(node.getCode());
        result.setNameEn(node.getNameEn());
        result.setParentCode(node.getParentCode());
        result.setTaxonomyRoot(node.getTaxonomyRoot());
        result.setLevel(node.getLevel());
        Long parentId = node.getParent() == null ? null : node.getParent().getId();
        return new TreeRow(parentId, result);
    }
}

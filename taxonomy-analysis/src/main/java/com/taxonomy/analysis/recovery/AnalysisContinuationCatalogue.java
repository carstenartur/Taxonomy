package com.taxonomy.analysis.recovery;

import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.dto.TaxonomyNodeDto;
import java.util.*;

/** Compares frozen official node and overlay semantics without mutable relation projections. */
public final class AnalysisContinuationCatalogue {
    private AnalysisContinuationCatalogue() { }
    public static boolean matches(List<TaxonomyNodeDto> tree, Collection<RootCatalogueSnapshot> roots) {
        if (tree == null) return false;
        var selected = new HashMap<String,TaxonomyNodeDto>();
        var rootCodes = roots.stream().map(RootCatalogueSnapshot::rootCode).collect(java.util.stream.Collectors.toSet());
        for (var root : tree) if (rootCodes.contains(root.getCode())) flatten(root, selected);
        var offered = new HashSet<String>();
        for (var root : roots) for (var node : root.nodes()) {
            var saved = selected.get(node.code());
            if (!offered.add(node.code()) || saved == null || !fields(saved).equals(fields(node))) return false;
        }
        return offered.equals(selected.keySet());
    }
    private static void flatten(TaxonomyNodeDto node, Map<String,TaxonomyNodeDto> all) {
        if (all.putIfAbsent(node.getCode(), node) != null) throw new IllegalArgumentException("Duplicate frozen catalogue node");
        if (node.getChildren() != null) node.getChildren().forEach(child -> flatten(child, all));
    }
    private static List<Object> fields(TaxonomyNodeDto n) {
        return Arrays.asList(n.getId(),n.getCode(),n.getUuid(),n.getNameEn(),n.getNameDe(),n.getDescriptionEn(),n.getDescriptionDe(),
                n.getParentCode(),n.getTaxonomyRoot(),n.getLevel(),n.getDataset(),n.getExternalId(),n.getSource(),n.getReference(),
                n.getSortOrder(),n.getState(),n.getAnalysisRole(),n.getSecondaryClassificationCodes(),n.getClassificationConfidence(),
                n.isClassificationReviewRequired(),n.getClassificationJustification());
    }
    private static List<Object> fields(RootCatalogueSnapshot.Node n) {
        return Arrays.asList(n.id(),n.code(),n.uuid(),n.nameEn(),n.nameDe(),n.descriptionEn(),n.descriptionDe(),
                n.parentCode(),n.taxonomyRoot(),n.level(),n.dataset(),n.externalId(),n.source(),n.reference(),n.sortOrder(),n.state(),
                n.metadata().analysisRole(),n.metadata().secondaryClassificationCodes(),n.metadata().confidence(),
                n.metadata().reviewRequired(),n.metadata().justification());
    }
}

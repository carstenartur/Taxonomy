package com.taxonomy.dto;

import java.util.List;
import java.util.Set;

/** Read-only interactive catalogue suggestions with their admitted architecture revision. */
public record CatalogueAnalysisPreview(String method, String catalogueRevision, String commit, Long semanticRevision,
                                      List<Node> nodes, List<Association> associations) {
    public record Request(String businessText, Set<String> taxonomyRoots, String expectedCommit,
                          Long expectedSemanticRevision) { }

    /** A null score identifies an ancestor included solely to display the tree. */
    public record Node(String code, String parentCode, String taxonomyRoot, String nameEn, String nameDe,
                       Double score) { }

    public record Association(String sourceCode, String targetCode, List<String> sharedTerms) { }

    public record Ranking(String method, List<Node> nodes, List<Association> associations) {
        public CatalogueAnalysisPreview at(String catalogueRevision, String commit, Long semanticRevision) {
            return new CatalogueAnalysisPreview(method, catalogueRevision, commit, semanticRevision, nodes, associations);
        }
    }
}

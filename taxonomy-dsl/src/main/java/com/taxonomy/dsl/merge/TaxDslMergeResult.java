package com.taxonomy.dsl.merge;

import java.util.List;

/** Result of a semantic three-way merge of Taxonomy DSL documents. */
public record TaxDslMergeResult(String mergedText, List<TaxDslMergeConflict> conflicts) {

    public TaxDslMergeResult {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public boolean isSuccessful() {
        return conflicts.isEmpty();
    }

    public List<String> conflictIdentifiers() {
        return conflicts.stream().map(TaxDslMergeConflict::identifier).toList();
    }
}

package com.taxonomy.dto;

import java.util.Set;

/**
 * Selection of elements and relations to transfer between two exact commits.
 *
 * @param sourceContextId     source commit SHA
 * @param targetContextId     expected target branch HEAD SHA
 * @param selectedElementIds  elements to transfer
 * @param selectedRelationIds relations to transfer
 * @param mode                conflict handling for the selected items
 */
public record TransferSelection(
    String sourceContextId,
    String targetContextId,
    Set<String> selectedElementIds,
    Set<String> selectedRelationIds,
    TransferMode mode
) {

    /** How conflicts in the selected subset are handled. */
    public enum TransferMode {
        /** Copy selected items into the target and overwrite selected conflicts. */
        COPY,
        /** Merge only conflict-free selected items; conflicts abort without mutation. */
        MERGE_SELECTED
    }
}

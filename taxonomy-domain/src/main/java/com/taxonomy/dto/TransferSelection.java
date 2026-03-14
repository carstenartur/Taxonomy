package com.taxonomy.dto;

import java.util.Set;

/**
 * Selection of elements and relations to transfer between contexts.
 *
 * @param sourceContextId     the context to copy from
 * @param targetContextId     the context to copy into
 * @param selectedElementIds  which elements to transfer
 * @param selectedRelationIds which relations to transfer
 * @param mode                how to perform the transfer
 */
public record TransferSelection(
    String sourceContextId,
    String targetContextId,
    Set<String> selectedElementIds,
    Set<String> selectedRelationIds,
    TransferMode mode
) {

    /**
     * How the selective transfer is performed.
     */
    public enum TransferMode {
        /** Copy selected items into the target, overwriting any conflicts. */
        COPY,
        /** Cherry-pick the commit containing the selected items. */
        CHERRY_PICK,
        /** Merge only the selected items from the source. */
        MERGE_SELECTED
    }
}

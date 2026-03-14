package com.taxonomy.dto;

import java.util.List;

/**
 * A conflict detected during a selective transfer operation.
 *
 * <p>Describes a case where the same element or relation exists in both
 * source and target but with different values, requiring user resolution.
 *
 * @param elementOrRelationId the ID of the conflicting item
 * @param originValue         the value in the target (existing) context
 * @param incomingValue       the value from the source (incoming) context
 * @param affectedViews       architecture views that reference this item
 */
public record TransferConflict(
    String elementOrRelationId,
    String originValue,
    String incomingValue,
    List<String> affectedViews
) {}

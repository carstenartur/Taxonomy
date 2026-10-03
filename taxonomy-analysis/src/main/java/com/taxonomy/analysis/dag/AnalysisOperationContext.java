package com.taxonomy.analysis.dag;

import java.util.Objects;

/**
 * Immutable identity of one analysis operation shared by all its tasks.
 *
 * @param operationId   durable operation identity
 * @param authority     exact source authority every task reads
 * @param requirement   requirement version reference (never the text)
 * @param correlationId operation-wide correlation identity
 */
public record AnalysisOperationContext(String operationId, AnalysisSourceAuthority authority,
                                       RequirementReference requirement, String correlationId) {

    public AnalysisOperationContext {
        AnalysisTaskId.requireOperationId(operationId);
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(requirement, "requirement");
        correlationId = correlationId == null ? operationId : correlationId;
    }
}

package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.relations.RequirementRelationSearch;
import java.util.UUID;

/** Private durable command authority; never a broker envelope or provider prompt field. */
public record AnalysisContinuationBinding(String id, String token, String inputHash,
                                          String scoringPolicyHash, String relationPolicyHash,
                                          Integer sourceCohortSize, RequirementRelationSearch.BudgetPolicy relationBudget) {
    public AnalysisContinuationBinding(String id, String token, String inputHash,
            String scoringPolicyHash, String relationPolicyHash) {
        this(id, token, inputHash, scoringPolicyHash, relationPolicyHash, 0, null);
    }
    public AnalysisContinuationBinding(String id, String token, String inputHash,
            String scoringPolicyHash, String relationPolicyHash, int sourceCohortSize) {
        this(id, token, inputHash, scoringPolicyHash, relationPolicyHash, sourceCohortSize, null);
    }
    public AnalysisContinuationBinding {
        // Historical durable commands omit the new field; strict JSON readers
        // reject a missing primitive before a record constructor can normalize it.
        sourceCohortSize = sourceCohortSize == null ? 0 : sourceCohortSize;
        if (!UUID.fromString(id).toString().equals(id) || !UUID.fromString(token).toString().equals(token))
            throw new IllegalArgumentException("Canonical continuation identity is required");
        for (String hash : new String[]{inputHash, scoringPolicyHash, relationPolicyHash})
            if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Continuation fingerprint is required");
        if (sourceCohortSize < 0 || sourceCohortSize > 256)
            throw new IllegalArgumentException("Continuation source cohort must be 0..256");
        if (relationBudget != null && sourceCohortSize != relationBudget.sourceCohortSize())
            throw new IllegalArgumentException("Continuation source cohort differs from its frozen budget");
    }
}

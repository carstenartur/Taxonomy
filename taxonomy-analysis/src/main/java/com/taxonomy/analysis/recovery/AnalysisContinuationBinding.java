package com.taxonomy.analysis.recovery;

import java.util.UUID;

/** Private durable command authority; never a broker envelope or provider prompt field. */
public record AnalysisContinuationBinding(String id, String token, String inputHash,
                                          String scoringPolicyHash, String relationPolicyHash) {
    public AnalysisContinuationBinding {
        if (!UUID.fromString(id).toString().equals(id) || !UUID.fromString(token).toString().equals(token))
            throw new IllegalArgumentException("Canonical continuation identity is required");
        for (String hash : new String[]{inputHash, scoringPolicyHash, relationPolicyHash})
            if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Continuation fingerprint is required");
    }
}

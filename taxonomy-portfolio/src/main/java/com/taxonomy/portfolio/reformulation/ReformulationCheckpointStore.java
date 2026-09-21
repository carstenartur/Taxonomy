package com.taxonomy.portfolio.reformulation;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Aggregate-internal storage. The caller holds the scoped proposal/run locks and active-run check. */
@Component
class ReformulationCheckpointStore {
    static final int MAX_RESULT_BYTES = 8 * 1024 * 1024;
    private final ReformulationNodeCheckpointRepository checkpoints;
    private final PortfolioJsonCodec json;

    ReformulationCheckpointStore(ReformulationNodeCheckpointRepository checkpoints, PortfolioJsonCodec json) {
        this.checkpoints = checkpoints; this.json = json;
    }
    Optional<String> lookup(ReformulationProposal proposal, String kind, String fingerprint) {
        validate(kind, fingerprint);
        return checkpoints.findByIdAndProposalIdAndScopeKey(key(proposal, kind, fingerprint), proposal.getId(), proposal.getScopeKey())
                .map(ReformulationNodeCheckpoint::getResultPayload);
    }
    String complete(ReformulationProposal proposal, String runId, String kind, String fingerprint, String payload) {
        validate(kind, fingerprint);
        if (payload == null || payload.isBlank() || payload.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES)
            throw PortfolioException.validation("CHECKPOINT_RESULT_TOO_LARGE_OR_EMPTY");
        var previous = lookup(proposal, kind, fingerprint);
        if (previous.isPresent()) return previous.get();
        checkpoints.saveAndFlush(new ReformulationNodeCheckpoint(key(proposal, kind, fingerprint), proposal.getId(), proposal.getScopeKey(),
                runId, kind, fingerprint, payload, Instant.now()));
        return payload;
    }
    private String key(ReformulationProposal proposal, String kind, String fingerprint) {
        return StableIdentityHash.sha256(json.write(List.of(proposal.getScopeKey(), proposal.getId(), kind, fingerprint)));
    }
    private static void validate(String kind, String fingerprint) {
        if (kind == null || !Set.of("NODE", "RECONCILE", "REWORD").contains(kind)
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
            throw PortfolioException.validation("Invalid reformulation checkpoint identity");
    }
}

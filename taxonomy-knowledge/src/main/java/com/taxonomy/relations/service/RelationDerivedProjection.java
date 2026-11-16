package com.taxonomy.relations.service;

import com.taxonomy.error.SearchUnavailableException;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.DerivedProjection;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

@Service
public class RelationDerivedProjection implements DerivedProjection {
    private final RelationProjectionOperationsService relations;
    private final BranchGraphVectorAuthority authority;
    private final BranchGraphVectorStore store;
    private final BranchGraphVectors vectors;
    private final ObjectMapper json;

    public RelationDerivedProjection(RelationProjectionOperationsService relations, BranchGraphVectorAuthority authority,
                                     BranchGraphVectorStore store, BranchGraphVectors vectors, ObjectMapper json) {
        this.relations = relations;
        this.authority = authority;
        this.store = store;
        this.vectors = vectors;
        this.json = json;
    }

    @Override public Source capture(RepositoryContext context, Kind kind, String expected) {
        verifyHead(context, expected);
        if (kind == Kind.RELATION) return new Source(null, null);
        String current = store.currentGeneration(context);
        if (kind == Kind.VECTOR_PURGE) return new Source(null, current);
        var selected = authority.capture(context).source();
        if (!expected.equals(selected.commit())) throw new RelationProjectionOperationsService.RebuildHeadConflictException(expected, selected.commit());
        return new Source(selected.key(), current);
    }

    @Override public Result apply(RepositoryContext context, Kind kind, String expected, Source input,
                                  String operationId, Runnable authorize) {
        verifyHead(context, expected);
        try {
            if (kind == Kind.RELATION) {
                var result = relations.rebuild(context, expected);
                authorize.run();
                var summary = result.reconciliation();
                return new Result(null, null, 0, result.rebuild().relationCount(), summary.recoveredCount(),
                        summary.supersededCount(), summary.remainingPendingCount());
            }
            if (kind == Kind.VECTOR_PURGE) {
                String current = store.currentGeneration(context);
                if (current != null && !Objects.equals(input.generation(), current)) throw new SourceChanged("Captured vector generation replaced");
                if (current != null && !store.purge(context, input.generation(), () -> {
                    verifyHead(context, expected);
                    authorize.run();
                }) && store.currentGeneration(context) != null) throw new SourceChanged("Captured vector generation replaced");
                return new Result(null, null, 0, 0, null, null, null);
            }
            String token = UUID.nameUUIDFromBytes(operationId.getBytes(StandardCharsets.UTF_8)).toString();
            var result = vectors.rebuild(context, expected, input.key(), input.generation(), token, authorize);
            return new Result(result.generation(), result.modelKey(), result.nodes(), result.relations(), null, null, null);
        } catch (RelationProjectionOperationsService.RebuildVerificationException failed) {
            var state = failed.getReadiness();
            throw new DecisionRequired("VERIFICATION_FAILED", false, new Result(null, null, 0,
                    failed.getRebuild().relationCount(), null, null, null, state.state().name(),
                    state.currentHeadCommit(), state.projectedCommit()), failed);
        } catch (RelationProjectionOperationsService.RecoveryReconciliationPendingException pending) {
            var rebuilt = pending.getRebuild();
            throw new DecisionRequired("RECONCILIATION_PENDING", true, new Result(null, null, 0,
                    rebuilt.relationCount(), null, null, null, null, rebuilt.authoritativeCommitId(), rebuilt.authoritativeCommitId()), pending);
        } catch (BranchHeadConflictException moved) {
            throw new RelationProjectionOperationsService.RebuildHeadConflictException(expected, moved.getActualHeadCommit());
        } catch (IOException | SearchUnavailableException unavailable) {
            throw new SourceUnavailable(unavailable);
        }
    }

    @Override public void verifyHead(RepositoryContext context, String expectedCommit) {
        authority.requireHead(context, expectedCommit);
    }

    @Override public String describe(Result result) { return json.writeValueAsString(result); }
    @Override public Result readResult(String stored) { return json.readValue(stored, Result.class); }
}

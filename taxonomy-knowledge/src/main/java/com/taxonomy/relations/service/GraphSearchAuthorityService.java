package com.taxonomy.relations.service;

import com.taxonomy.error.SearchUnavailableException;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationProjectionReadService.CountResult;
import com.taxonomy.relations.service.RelationProjectionReadService.ReadModel;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * Captures detached source evidence for the graph search's supported relation sources.
 * Each inspection has a fresh persistence context, including when graph KNN runs
 * inside an outer read transaction. Reusing managed checkpoints could hide a
 * committed source/readiness transition at the same Git head.
 */
@Service
public class GraphSearchAuthorityService {
    private final RelationProjectionReadService relations;
    private final RelationBranchProjectionReadinessService readiness;
    private final TransactionTemplate inspection;

    public GraphSearchAuthorityService(RelationProjectionReadService relations,
                                       RelationBranchProjectionReadinessService readiness,
                                       PlatformTransactionManager transactions) {
        this.relations = relations;
        this.readiness = readiness;
        inspection = new TransactionTemplate(transactions);
        inspection.setReadOnly(true);
        inspection.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public CountResult inspect(RepositoryContext context) {
        Objects.requireNonNull(context, "context");
        return inspection.execute(status -> {
            CountResult source = relations.count(context);
            boolean supported = (source.readModel() == ReadModel.LEGACY_FALLBACK
                    && source.readinessState() == ReadinessState.NOT_BUILT)
                    || (source.readModel() == ReadModel.PROJECTION
                    && source.readinessState() == ReadinessState.READY && source.count() == 0);
            // NOT_BUILT returns early in projection readiness; explicitly verify
            // the live head for that source as well as for an empty projection.
            if (!supported || source.authoritativeCommitId() == null
                    || !Objects.equals(source.authoritativeCommitId(), readiness.readCurrentHead(context))) {
                throw new SearchUnavailableException();
            }
            return source;
        });
    }

    public void verifyUnchanged(RepositoryContext context, CountResult before) {
        if (!Objects.requireNonNull(before, "before").equals(inspect(context))) {
            throw new SearchUnavailableException();
        }
    }
}

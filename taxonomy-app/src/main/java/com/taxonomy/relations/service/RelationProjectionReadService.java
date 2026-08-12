package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Product read boundary for relation decisions.
 *
 * <p>A complete branch projection is the normal source. The only compatibility
 * fallback is the historic primary repository's configured default branch while
 * no projection has ever been built and no failed Git-authoritative projection
 * is pending. Stale, corrupt, branch-missing, workspace and fork projections
 * fail closed instead of exposing an unrelated legacy overlay.</p>
 */
@Service
public class RelationProjectionReadService {

    private static final Logger log = LoggerFactory.getLogger(
            RelationProjectionReadService.class);

    private final RelationBranchProjectionReadinessService readinessService;
    private final RelationProjectionRecoveryService recoveryService;
    private final TaxonomyRelationService legacyRelationService;
    private final TaxonomyNodeRepository nodeRepository;
    private final SystemRepositoryService repositoryService;
    private final Set<String> reportedFallbacks = ConcurrentHashMap.newKeySet();

    public RelationProjectionReadService(
            RelationBranchProjectionReadinessService readinessService,
            RelationProjectionRecoveryService recoveryService,
            TaxonomyRelationService legacyRelationService,
            TaxonomyNodeRepository nodeRepository,
            SystemRepositoryService repositoryService) {
        this.readinessService = Objects.requireNonNull(
                readinessService, "readinessService");
        this.recoveryService = Objects.requireNonNull(
                recoveryService, "recoveryService");
        this.legacyRelationService = Objects.requireNonNull(
                legacyRelationService, "legacyRelationService");
        this.nodeRepository = Objects.requireNonNull(
                nodeRepository, "nodeRepository");
        this.repositoryService = Objects.requireNonNull(
                repositoryService, "repositoryService");
    }

    public ReadResult readAll(RepositoryContext context) {
        RepositoryContext selected = Objects.requireNonNull(context, "context");
        return read(
                selected,
                ignored -> true,
                () -> legacyRelationService.getAllRelationsInContext(selected));
    }

    public ReadResult readByType(
            RepositoryContext context,
            RelationType relationType) {
        RepositoryContext selected = Objects.requireNonNull(context, "context");
        RelationType type = Objects.requireNonNull(
                relationType, "relationType");
        return read(
                selected,
                row -> row.getRelationType() == type,
                () -> legacyRelationService.getRelationsByTypeInContext(
                        type, selected));
    }

    public ReadResult readForNode(
            RepositoryContext context,
            String nodeCode) {
        RepositoryContext selected = Objects.requireNonNull(context, "context");
        String code = requireText(nodeCode, "nodeCode");
        return read(
                selected,
                row -> code.equals(row.getSourceCode())
                        || code.equals(row.getTargetCode()),
                () -> legacyRelationService.getRelationsForNodeInContext(
                        code, selected));
    }

    private ReadResult read(
            RepositoryContext selected,
            Predicate<RelationDecisionProjection> projectionFilter,
            Supplier<List<TaxonomyRelationDto>> legacyRead) {
        Readiness readiness = readinessService.inspect(selected);
        if (readiness.state() == ReadinessState.READY) {
            List<RelationDecisionProjection> selectedRows = readiness.rows()
                    .stream()
                    .filter(projectionFilter)
                    .toList();
            return new ReadResult(
                    ReadModel.PROJECTION,
                    readiness.state(),
                    readiness.currentHeadCommit(),
                    projectionDtos(selectedRows));
        }

        long pendingRecoveries = recoveryService.pendingCount(selected);
        if (readiness.state() == ReadinessState.NOT_BUILT
                && pendingRecoveries == 0
                && mayUseLegacyFallback(selected)) {
            reportFallback(selected);
            return new ReadResult(
                    ReadModel.LEGACY_FALLBACK,
                    readiness.state(),
                    readiness.currentHeadCommit(),
                    legacyRead.get());
        }

        throw new RelationProjectionUnavailableException(
                selected,
                readiness.state(),
                readiness.currentHeadCommit(),
                readiness.projectedCommit(),
                pendingRecoveries);
    }

    private List<TaxonomyRelationDto> projectionDtos(
            List<RelationDecisionProjection> rows) {
        List<RelationDecisionProjection> projections = List.copyOf(rows);
        Set<String> codes = new LinkedHashSet<>();
        projections.forEach(row -> {
            codes.add(row.getSourceCode());
            codes.add(row.getTargetCode());
        });
        Map<String, String> names = names(codes);
        return projections.stream()
                .map(row -> projectionDto(row, names))
                .toList();
    }

    private Map<String, String> names(Collection<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        nodeRepository.findByCodeIn(codes).forEach(node -> names.put(
                node.getCode(), displayName(node)));
        return Map.copyOf(names);
    }

    private static String displayName(TaxonomyNode node) {
        return node.getNameEn() == null || node.getNameEn().isBlank()
                ? node.getCode()
                : node.getNameEn();
    }

    private static TaxonomyRelationDto projectionDto(
            RelationDecisionProjection row,
            Map<String, String> names) {
        TaxonomyRelationDto dto = new TaxonomyRelationDto();
        dto.setId(row.getId());
        dto.setSourceCode(row.getSourceCode());
        dto.setSourceName(names.getOrDefault(
                row.getSourceCode(), row.getSourceCode()));
        dto.setTargetCode(row.getTargetCode());
        dto.setTargetName(names.getOrDefault(
                row.getTargetCode(), row.getTargetCode()));
        dto.setRelationType(row.getRelationType().name());
        dto.setDescription(null);
        dto.setProvenance(row.getProvenance());
        dto.setWeight(null);
        dto.setBidirectional(false);
        return dto;
    }

    private boolean mayUseLegacyFallback(RepositoryContext context) {
        if (context.scope() != RepositoryScope.CENTRAL_READ
                && context.scope() != RepositoryScope.CENTRAL_WRITE) {
            return false;
        }
        SystemRepository primary = repositoryService.getPrimaryRepository();
        return primary.getRepositoryId().equals(context.repositoryId())
                && primary.getDefaultBranch().equals(context.branch());
    }

    private void reportFallback(RepositoryContext context) {
        String key = context.repositoryId() + "/" + context.branch();
        if (reportedFallbacks.add(key)) {
            log.warn("Relation reads use the migration-only legacy fallback for {}. "
                            + "Build the complete Git branch projection to remove this fallback.",
                    key);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    public enum ReadModel {
        PROJECTION,
        LEGACY_FALLBACK
    }

    public record ReadResult(
            ReadModel readModel,
            ReadinessState readinessState,
            String authoritativeCommitId,
            List<TaxonomyRelationDto> relations) {
        public ReadResult {
            readModel = Objects.requireNonNull(readModel, "readModel");
            readinessState = Objects.requireNonNull(
                    readinessState, "readinessState");
            relations = List.copyOf(Objects.requireNonNull(
                    relations, "relations"));
        }
    }

    public static final class RelationProjectionUnavailableException
            extends IllegalStateException {
        private final RepositoryContext context;
        private final ReadinessState readinessState;
        private final String currentHeadCommit;
        private final String projectedCommit;
        private final long pendingRecoveryCount;

        public RelationProjectionUnavailableException(
                RepositoryContext context,
                ReadinessState readinessState,
                String currentHeadCommit,
                String projectedCommit,
                long pendingRecoveryCount) {
            super("Relation projection is not safely readable for "
                    + context.repositoryId() + "/" + context.branch()
                    + ": " + readinessState
                    + " (pending recovery=" + pendingRecoveryCount + ")");
            this.context = Objects.requireNonNull(context, "context");
            this.readinessState = Objects.requireNonNull(
                    readinessState, "readinessState");
            this.currentHeadCommit = currentHeadCommit;
            this.projectedCommit = projectedCommit;
            this.pendingRecoveryCount = pendingRecoveryCount;
        }

        public RepositoryContext getContext() {
            return context;
        }

        public ReadinessState getReadinessState() {
            return readinessState;
        }

        public String getCurrentHeadCommit() {
            return currentHeadCommit;
        }

        public String getProjectedCommit() {
            return projectedCommit;
        }

        public long getPendingRecoveryCount() {
            return pendingRecoveryCount;
        }
    }
}

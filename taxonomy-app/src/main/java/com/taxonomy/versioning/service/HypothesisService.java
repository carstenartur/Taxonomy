package com.taxonomy.versioning.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.mapper.ModelToAstMapper;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.model.TaxonomyRootTypes;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dsl.validation.DslValidationResult;
import com.taxonomy.dsl.validation.DslValidator;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationEvidence;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationEvidenceRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the lifecycle of repository/workspace-scoped relation hypotheses.
 *
 * <p>Explicit {@link RepositoryContext} methods are authoritative. Historic
 * {@link WorkspaceContext} overloads retain the exact selected repository and
 * resolve workspace provenance through the repository catalog. Only the explicit
 * legacy repository sentinel may fall back to the configured primary repository.</p>
 */
@Service
public class HypothesisService {

    private static final Logger log = LoggerFactory.getLogger(HypothesisService.class);
    private static final String TEST_REPOSITORY_ID = "test-primary";

    private final RelationHypothesisRepository hypothesisRepository;
    private final RelationEvidenceRepository evidenceRepository;
    private final TaxonomyRelationService relationService;
    private final TaxonomyNodeRepository nodeRepository;
    private final DslGitRepositoryFactory repositoryFactory;
    private final SystemRepositoryService systemRepositoryService;
    private final UserWorkspaceRepository userWorkspaceRepository;

    private final ModelToAstMapper modelToAstMapper = new ModelToAstMapper();
    private final AstToModelMapper astToModelMapper = new AstToModelMapper();
    private final TaxDslSerializer serializer = new TaxDslSerializer();
    private final TaxDslParser parser = new TaxDslParser();
    private final DslValidator validator = new DslValidator();

    @Autowired
    public HypothesisService(RelationHypothesisRepository hypothesisRepository,
                             RelationEvidenceRepository evidenceRepository,
                             TaxonomyRelationService relationService,
                             TaxonomyNodeRepository nodeRepository,
                             DslGitRepositoryFactory repositoryFactory,
                             SystemRepositoryService systemRepositoryService,
                             UserWorkspaceRepository userWorkspaceRepository) {
        this.hypothesisRepository = hypothesisRepository;
        this.evidenceRepository = evidenceRepository;
        this.relationService = relationService;
        this.nodeRepository = nodeRepository;
        this.repositoryFactory = repositoryFactory;
        this.systemRepositoryService = systemRepositoryService;
        this.userWorkspaceRepository = userWorkspaceRepository;
    }

    /** Test-only compatibility constructor for isolated mapper/DSL tests. */
    public HypothesisService(RelationHypothesisRepository hypothesisRepository,
                             RelationEvidenceRepository evidenceRepository,
                             TaxonomyRelationService relationService,
                             TaxonomyNodeRepository nodeRepository,
                             DslGitRepositoryFactory repositoryFactory) {
        this(hypothesisRepository, evidenceRepository, relationService, nodeRepository,
                repositoryFactory, null, null);
    }

    /** Persist provisional hypotheses and version their canonical DSL immediately. */
    @Transactional
    public List<RelationHypothesis> persistFromAnalysis(
            List<RelationHypothesisDto> hypotheses,
            String sessionId,
            RepositoryContext context) {
        return persistFromAnalysis(hypotheses, sessionId, context, false);
    }

    /**
     * Persist provisional hypotheses in the surrounding transaction and publish
     * their canonical Git DSL only after that transaction has committed.
     *
     * <p>This variant is the durable worker boundary: a snapshot or claim
     * finalization rollback must never leave a Git commit for database state that
     * was not committed.</p>
     */
    @Transactional
    public List<RelationHypothesis> persistFromAnalysisAfterCommit(
            List<RelationHypothesisDto> hypotheses,
            String sessionId,
            RepositoryContext context) {
        return persistFromAnalysis(hypotheses, sessionId, context, true);
    }

    private List<RelationHypothesis> persistFromAnalysis(
            List<RelationHypothesisDto> hypotheses,
            String sessionId,
            RepositoryContext context,
            boolean publishAfterCommit) {
        if (hypotheses == null || hypotheses.isEmpty()) {
            return List.of();
        }
        RepositoryContext tenant = requireWritableContext(context);
        String effectiveSessionId = normalizeSessionId(sessionId);

        List<RelationHypothesis> persisted = new ArrayList<>();
        for (RelationHypothesisDto dto : hypotheses) {
            RelationType relationType = RelationType.valueOf(
                    requireText(dto.getRelationType(), "relationType").toUpperCase(Locale.ROOT));
            String sourceCode = requireText(dto.getSourceCode(), "sourceCode");
            String targetCode = requireText(dto.getTargetCode(), "targetCode");
            if (hypothesisRepository.existsInRepositoryWorkspaceSession(
                    tenant.repositoryId(),
                    RelationHypothesis.scopeKeyFor(tenant.workspaceId()),
                    RelationHypothesis.sessionScopeKeyFor(effectiveSessionId),
                    sourceCode,
                    targetCode,
                    relationType)) {
                continue;
            }

            RelationHypothesis entity = new RelationHypothesis();
            entity.setRepositoryId(tenant.repositoryId());
            entity.setSourceNodeId(sourceCode);
            entity.setTargetNodeId(targetCode);
            entity.setRelationType(relationType);
            entity.setConfidence(dto.getConfidence());
            entity.setStatus(HypothesisStatus.PROVISIONAL);
            entity.setAnalysisSessionId(effectiveSessionId);
            entity.setWorkspaceId(tenant.workspaceId());
            entity.setOwnerUsername(tenant.username());

            RelationHypothesis saved = hypothesisRepository.save(entity);
            persisted.add(saved);

            if (dto.getReasoning() != null && !dto.getReasoning().isBlank()) {
                RelationEvidence evidence = new RelationEvidence();
                evidence.setHypothesis(saved);
                evidence.setEvidenceType("analysis-rule");
                evidence.setSummary(dto.getReasoning());
                evidence.setConfidence(dto.getConfidence());
                evidenceRepository.save(evidence);
            }
        }

        if (!persisted.isEmpty()) {
            if (publishAfterCommit) {
                publishHypothesesAfterCommit(
                        persisted, effectiveSessionId, tenant);
            } else {
                commitHypothesesAsDsl(
                        persisted, effectiveSessionId, tenant);
            }
        }
        log.info("Persisted {} hypotheses for session {} in repository {} workspace {}",
                persisted.size(), effectiveSessionId,
                tenant.repositoryId(), tenant.workspaceId());
        return persisted;
    }

    /** Historic workspace-only overload, resolved through catalog provenance. */
    @Transactional
    public List<RelationHypothesis> persistFromAnalysis(
            List<RelationHypothesisDto> hypotheses,
            String sessionId,
            WorkspaceContext workspaceContext) {
        return persistFromAnalysis(
                hypotheses, sessionId, resolveLegacyContext(workspaceContext));
    }

    /** Historic workspace-only overload with commit-bound Git publication. */
    @Transactional
    public List<RelationHypothesis> persistFromAnalysisAfterCommit(
            List<RelationHypothesisDto> hypotheses,
            String sessionId,
            WorkspaceContext workspaceContext) {
        return persistFromAnalysisAfterCommit(
                hypotheses, sessionId, resolveLegacyContext(workspaceContext));
    }

    @Transactional
    public RelationHypothesis accept(Long hypothesisId, RepositoryContext context) {
        RepositoryContext tenant = requireWritableContext(context);
        RelationHypothesis hypothesis = requireWritableHypothesis(hypothesisId, tenant);

        if (hypothesis.getStatus() == HypothesisStatus.ACCEPTED) {
            throw new IllegalStateException("Hypothesis " + hypothesisId + " is already ACCEPTED");
        }
        if (hypothesis.getStatus() == HypothesisStatus.REJECTED) {
            throw new IllegalStateException("Hypothesis " + hypothesisId + " is already REJECTED");
        }

        boolean relationCreated = false;
        if (nodeRepository.findByCode(hypothesis.getSourceNodeId()).isPresent()
                && nodeRepository.findByCode(hypothesis.getTargetNodeId()).isPresent()) {
            relationService.createRelationInContext(
                    hypothesis.getSourceNodeId(),
                    hypothesis.getTargetNodeId(),
                    hypothesis.getRelationType(),
                    "Accepted from hypothesis " + hypothesisId,
                    "hypothesis-accepted",
                    tenant);
            relationCreated = true;
        } else {
            log.warn("Could not create relation for hypothesis {}: source or target node not found",
                    hypothesisId);
        }

        hypothesis.setStatus(HypothesisStatus.ACCEPTED);
        hypothesisRepository.save(hypothesis);
        commitHypothesesAsDsl(List.of(hypothesis), "accepted-" + hypothesisId, tenant);

        log.info("Accepted hypothesis {} in repository {} workspace {}: {} --[{}]--> {} "
                        + "(relation created: {})",
                hypothesisId, tenant.repositoryId(), tenant.workspaceId(),
                hypothesis.getSourceNodeId(), hypothesis.getRelationType(),
                hypothesis.getTargetNodeId(), relationCreated);
        return hypothesis;
    }

    @Transactional
    public RelationHypothesis accept(Long hypothesisId, WorkspaceContext workspaceContext) {
        return accept(hypothesisId, resolveLegacyContext(workspaceContext));
    }

    @Transactional
    public RelationHypothesis reject(Long hypothesisId, RepositoryContext context) {
        RepositoryContext tenant = requireWritableContext(context);
        RelationHypothesis hypothesis = requireWritableHypothesis(hypothesisId, tenant);
        if (hypothesis.getStatus() == HypothesisStatus.ACCEPTED) {
            throw new IllegalStateException("Hypothesis " + hypothesisId + " is already ACCEPTED");
        }
        if (hypothesis.getStatus() == HypothesisStatus.REJECTED) {
            throw new IllegalStateException("Hypothesis " + hypothesisId + " is already REJECTED");
        }
        hypothesis.setStatus(HypothesisStatus.REJECTED);
        return hypothesisRepository.save(hypothesis);
    }

    @Transactional
    public RelationHypothesis reject(Long hypothesisId, WorkspaceContext workspaceContext) {
        return reject(hypothesisId, resolveLegacyContext(workspaceContext));
    }

    @Transactional
    public RelationHypothesis applyForSession(Long hypothesisId, RepositoryContext context) {
        RepositoryContext tenant = requireWritableContext(context);
        RelationHypothesis hypothesis = requireWritableHypothesis(hypothesisId, tenant);
        hypothesis.setAppliedInCurrentAnalysis(true);
        return hypothesisRepository.save(hypothesis);
    }

    @Transactional
    public RelationHypothesis applyForSession(
            Long hypothesisId, WorkspaceContext workspaceContext) {
        return applyForSession(hypothesisId, resolveLegacyContext(workspaceContext));
    }

    @Transactional(readOnly = true)
    public List<RelationHypothesis> findByStatus(
            HypothesisStatus status, RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        return tenant.workspaceId() == null
                ? hypothesisRepository.findCentralByRepositoryAndStatus(
                        tenant.repositoryId(), status)
                : hypothesisRepository.findVisibleByRepositoryAndWorkspaceAndStatus(
                        tenant.repositoryId(), tenant.workspaceId(), status);
    }

    @Transactional(readOnly = true)
    public List<RelationHypothesis> findByStatus(
            HypothesisStatus status, WorkspaceContext workspaceContext) {
        return findByStatus(status, resolveLegacyContext(workspaceContext));
    }

    @Transactional(readOnly = true)
    public List<RelationHypothesis> findAll(RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        return tenant.workspaceId() == null
                ? hypothesisRepository.findCentralByRepository(tenant.repositoryId())
                : hypothesisRepository.findVisibleByRepositoryAndWorkspace(
                        tenant.repositoryId(), tenant.workspaceId());
    }

    @Transactional(readOnly = true)
    public List<RelationHypothesis> findAll(WorkspaceContext workspaceContext) {
        return findAll(resolveLegacyContext(workspaceContext));
    }

    @Transactional(readOnly = true)
    public List<RelationEvidence> findEvidence(
            Long hypothesisId, RepositoryContext context) {
        requireReadableHypothesis(hypothesisId, requireContext(context));
        return evidenceRepository.findByHypothesisId(hypothesisId);
    }

    @Transactional(readOnly = true)
    public List<RelationEvidence> findEvidence(
            Long hypothesisId, WorkspaceContext workspaceContext) {
        return findEvidence(hypothesisId, resolveLegacyContext(workspaceContext));
    }

    private RelationHypothesis requireReadableHypothesis(
            Long hypothesisId, RepositoryContext context) {
        return hypothesisRepository.findByIdVisibleInRepositoryWorkspace(
                        context.repositoryId(), hypothesisId, context.workspaceId())
                .orElseThrow(() -> hypothesisNotFound(hypothesisId));
    }

    private RelationHypothesis requireWritableHypothesis(
            Long hypothesisId, RepositoryContext context) {
        return hypothesisRepository.findByIdInRepositoryWorkspace(
                        context.repositoryId(), hypothesisId, context.workspaceId())
                .orElseThrow(() -> hypothesisNotFound(hypothesisId));
    }

    private IllegalArgumentException hypothesisNotFound(Long hypothesisId) {
        return new IllegalArgumentException("Hypothesis not found: " + hypothesisId);
    }

    private void publishHypothesesAfterCommit(
            List<RelationHypothesis> hypotheses,
            String sessionId,
            RepositoryContext context) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Commit-bound hypothesis publication requires active transaction synchronization");
        }
        List<RelationHypothesis> committedHypotheses = List.copyOf(hypotheses);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            commitHypothesesAsDsl(
                                    committedHypotheses, sessionId, context);
                        } catch (RuntimeException failure) {
                            // The database is already authoritative and committed.
                            // Keep the snapshot valid, but make the projection gap
                            // operationally visible for reconciliation.
                            log.error("Failed to publish committed hypotheses for session {} "
                                            + "in repository {} workspace {}",
                                    sessionId,
                                    context.repositoryId(),
                                    context.workspaceId(),
                                    failure);
                        }
                    }
                });
    }

    private void commitHypothesesAsDsl(
            List<RelationHypothesis> hypotheses,
            String sessionId,
            RepositoryContext context) {
        String dslText = generateCanonicalDsl(hypotheses, sessionId);
        String branch = hypotheses.stream()
                .anyMatch(h -> h.getStatus() == HypothesisStatus.ACCEPTED)
                ? "accepted" : "draft";
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        try {
            String commitId = repository.commitDsl(
                    branch,
                    dslText,
                    context.username(),
                    "Auto-generated from analysis session " + sessionId);
            log.info("Committed {} hypotheses as canonical DSL to repository {} workspace {} "
                            + "branch '{}': {}",
                    hypotheses.size(), context.repositoryId(), context.workspaceId(),
                    branch, commitId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to commit canonical hypothesis DSL", e);
        }
    }

    private String generateCanonicalDsl(List<RelationHypothesis> hypotheses, String sessionId) {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        Set<String> declaredElements = new LinkedHashSet<>();

        for (RelationHypothesis hypothesis : hypotheses) {
            addElement(model, declaredElements, hypothesis.getSourceNodeId());
            addElement(model, declaredElements, hypothesis.getTargetNodeId());

            ArchitectureRelation relation = new ArchitectureRelation(
                    hypothesis.getSourceNodeId(),
                    hypothesis.getRelationType().name(),
                    hypothesis.getTargetNodeId());
            relation.setStatus(hypothesis.getStatus().name().toLowerCase(Locale.ROOT));
            relation.setConfidence(hypothesis.getConfidence());
            relation.setProvenance("analysis-session:" + sessionId);
            model.getRelations().add(relation);
        }

        assertValid(model, "generated hypothesis model");
        String text = serializer.serialize(modelToAstMapper.toDocument(model, "hypothesis-auto"));

        CanonicalArchitectureModel roundTripped = astToModelMapper.map(
                parser.parse(text, "hypotheses.taxdsl"));
        assertValid(roundTripped, "round-tripped hypothesis DSL");
        if (roundTripped.getElements().size() != model.getElements().size()
                || roundTripped.getRelations().size() != model.getRelations().size()) {
            throw new IllegalStateException(
                    "Canonical hypothesis DSL round-trip lost elements or relations");
        }
        return text;
    }

    private void addElement(CanonicalArchitectureModel model,
                            Set<String> declaredElements,
                            String code) {
        if (!declaredElements.add(code)) {
            return;
        }
        TaxonomyNode node = nodeRepository.findByCode(code).orElse(null);
        String root = resolveRoot(code, node);
        ArchitectureElement element = new ArchitectureElement();
        element.setId(code);
        element.setType(TaxonomyRootTypes.typeFor(root));
        element.setTaxonomy(root);
        element.setTitle(node != null && node.getNameEn() != null && !node.getNameEn().isBlank()
                ? node.getNameEn() : code);
        if (node != null) {
            element.setDescription(node.getDescriptionEn());
        }
        model.getElements().add(element);
    }

    private String resolveRoot(String code, TaxonomyNode node) {
        if (node != null && node.getTaxonomyRoot() != null) {
            return node.getTaxonomyRoot();
        }
        if (TaxonomyRootTypes.ROOT_TO_TYPE.containsKey(code)) {
            return code;
        }
        String root = TaxonomyRootTypes.rootFromId(code);
        return root != null ? root : "Unknown";
    }

    private void assertValid(CanonicalArchitectureModel model, String description) {
        DslValidationResult result = validator.validate(model);
        if (!result.isValid()) {
            throw new IllegalStateException(description + " is invalid: " + result);
        }
        if (result.hasWarnings()) {
            log.warn("{} contains validation warnings: {}", description, result.getWarnings());
        }
    }

    private RepositoryContext resolveLegacyContext(WorkspaceContext workspaceContext) {
        WorkspaceContext legacy = workspaceContext != null
                ? workspaceContext : WorkspaceContext.SHARED;
        String username = normalizeUsername(legacy.username());
        String branch = normalizeBranch(legacy.currentBranch());
        String workspaceId = normalizeOptional(legacy.workspaceId());
        String requestedRepositoryId = requireText(
                legacy.repositoryId(), "workspaceContext.repositoryId");
        boolean legacyRepositorySelection = WorkspaceContext.LEGACY_REPOSITORY_ID
                .equals(requestedRepositoryId);

        // Isolated mapper tests use the five-argument constructor. Preserve the
        // historic fixture repository only for the explicit legacy sentinel.
        if (systemRepositoryService == null || userWorkspaceRepository == null) {
            String repositoryId = legacyRepositorySelection
                    ? TEST_REPOSITORY_ID : requestedRepositoryId;
            String effectiveBranch = branch != null ? branch : "draft";
            return workspaceId == null
                    ? RepositoryContext.centralRead(
                            repositoryId, effectiveBranch, username)
                    : RepositoryContext.workspace(
                            repositoryId, workspaceId, effectiveBranch, username);
        }

        if (workspaceId == null) {
            SystemRepository selectedRepository = legacyRepositorySelection
                    ? systemRepositoryService.getPrimaryRepository()
                    : systemRepositoryService.getRepository(requestedRepositoryId);
            return RepositoryContext.centralRead(
                    selectedRepository.getRepositoryId(),
                    branch != null
                            ? branch : selectedRepository.getDefaultBranch(),
                    username);
        }

        UserWorkspace workspace = userWorkspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workspace not found while resolving repository context: " + workspaceId));
        if (workspace.getUsername() != null
                && !workspace.getUsername().equals(username)
                && !"system".equals(username)) {
            throw new IllegalArgumentException(
                    "Workspace does not belong to the active user: " + workspaceId);
        }
        String repositoryId = requireText(
                workspace.getSourceRepositoryId(), "workspace.sourceRepositoryId");
        if (!legacyRepositorySelection
                && !repositoryId.equals(requestedRepositoryId)) {
            throw new IllegalArgumentException(
                    "Workspace repository does not match the selected repository: "
                            + workspaceId);
        }
        String workspaceBranch = branch != null
                ? branch : normalizeBranch(workspace.getCurrentBranch());
        return RepositoryContext.workspace(
                repositoryId,
                workspaceId,
                workspaceBranch != null ? workspaceBranch : "draft",
                username);
    }

    private static RepositoryContext requireContext(RepositoryContext context) {
        if (context == null) {
            throw new IllegalArgumentException("RepositoryContext must not be null");
        }
        return context;
    }

    private static RepositoryContext requireWritableContext(RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        if (tenant.scope() == RepositoryScope.CENTRAL_READ) {
            throw new IllegalStateException(
                    "Hypothesis mutation requires a workspace or explicit central write context");
        }
        return tenant;
    }

    private static String normalizeSessionId(String value) {
        String normalized = normalizeOptional(value);
        return normalized != null ? normalized : UUID.randomUUID().toString();
    }

    private static String normalizeUsername(String value) {
        String normalized = normalizeOptional(value);
        return normalized != null ? normalized : "system";
    }

    private static String normalizeBranch(String value) {
        return normalizeOptional(value);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String requireText(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}

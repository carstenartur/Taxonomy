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
import com.taxonomy.workspace.service.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the lifecycle of relation hypotheses.
 *
 * <p>Every operation receives an explicit {@link WorkspaceContext}. Database
 * visibility, mutations, evidence access, and generated Git commits therefore
 * use the same workspace boundary and cannot silently fall back to another
 * request's context.</p>
 */
@Service
public class HypothesisService {

    private static final Logger log = LoggerFactory.getLogger(HypothesisService.class);

    private final RelationHypothesisRepository hypothesisRepository;
    private final RelationEvidenceRepository evidenceRepository;
    private final TaxonomyRelationService relationService;
    private final TaxonomyNodeRepository nodeRepository;
    private final DslGitRepositoryFactory repositoryFactory;

    private final ModelToAstMapper modelToAstMapper = new ModelToAstMapper();
    private final AstToModelMapper astToModelMapper = new AstToModelMapper();
    private final TaxDslSerializer serializer = new TaxDslSerializer();
    private final TaxDslParser parser = new TaxDslParser();
    private final DslValidator validator = new DslValidator();

    public HypothesisService(RelationHypothesisRepository hypothesisRepository,
                             RelationEvidenceRepository evidenceRepository,
                             TaxonomyRelationService relationService,
                             TaxonomyNodeRepository nodeRepository,
                             DslGitRepositoryFactory repositoryFactory) {
        this.hypothesisRepository = hypothesisRepository;
        this.evidenceRepository = evidenceRepository;
        this.relationService = relationService;
        this.nodeRepository = nodeRepository;
        this.repositoryFactory = repositoryFactory;
    }

    /** Persist provisional hypotheses and version their canonical DSL in the same workspace. */
    @Transactional
    public List<RelationHypothesis> persistFromAnalysis(List<RelationHypothesisDto> hypotheses,
                                                         String sessionId,
                                                         WorkspaceContext workspaceContext) {
        if (hypotheses == null || hypotheses.isEmpty()) {
            return List.of();
        }
        WorkspaceContext effectiveContext = requireContext(workspaceContext);
        String effectiveSessionId = sessionId != null ? sessionId : UUID.randomUUID().toString();

        List<RelationHypothesis> persisted = new ArrayList<>();
        for (RelationHypothesisDto dto : hypotheses) {
            RelationType relationType = RelationType.valueOf(dto.getRelationType());
            List<RelationHypothesis> existing = hypothesisRepository
                    .findBySourceNodeIdAndTargetNodeIdAndRelationType(
                            dto.getSourceCode(), dto.getTargetCode(), relationType);
            boolean alreadyExists = existing.stream()
                    .anyMatch(h -> effectiveSessionId.equals(h.getAnalysisSessionId())
                            && sameWorkspace(h.getWorkspaceId(), effectiveContext.workspaceId()));
            if (alreadyExists) {
                continue;
            }

            RelationHypothesis entity = new RelationHypothesis();
            entity.setSourceNodeId(dto.getSourceCode());
            entity.setTargetNodeId(dto.getTargetCode());
            entity.setRelationType(relationType);
            entity.setConfidence(dto.getConfidence());
            entity.setStatus(HypothesisStatus.PROVISIONAL);
            entity.setAnalysisSessionId(effectiveSessionId);
            entity.setWorkspaceId(effectiveContext.workspaceId());
            entity.setOwnerUsername(effectiveContext.username());

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
            commitHypothesesAsDsl(persisted, effectiveSessionId, effectiveContext);
        }
        log.info("Persisted {} hypotheses for session {} in workspace {}",
                persisted.size(), effectiveSessionId, effectiveContext.workspaceId());
        return persisted;
    }

    @Transactional
    public RelationHypothesis accept(Long hypothesisId, WorkspaceContext workspaceContext) {
        WorkspaceContext effectiveContext = requireContext(workspaceContext);
        RelationHypothesis hypothesis = requireWritableHypothesis(hypothesisId, effectiveContext);

        if (hypothesis.getStatus() == HypothesisStatus.ACCEPTED) {
            throw new IllegalStateException("Hypothesis " + hypothesisId + " is already ACCEPTED");
        }
        if (hypothesis.getStatus() == HypothesisStatus.REJECTED) {
            throw new IllegalStateException("Hypothesis " + hypothesisId + " is already REJECTED");
        }

        boolean relationCreated = false;
        if (nodeRepository.findByCode(hypothesis.getSourceNodeId()).isPresent()
                && nodeRepository.findByCode(hypothesis.getTargetNodeId()).isPresent()) {
            relationService.createRelation(
                    hypothesis.getSourceNodeId(),
                    hypothesis.getTargetNodeId(),
                    hypothesis.getRelationType(),
                    "Accepted from hypothesis " + hypothesisId,
                    "hypothesis-accepted",
                    hypothesis.getWorkspaceId(),
                    hypothesis.getOwnerUsername());
            relationCreated = true;
        } else {
            log.warn("Could not create relation for hypothesis {}: source or target node not found",
                    hypothesisId);
        }

        hypothesis.setStatus(HypothesisStatus.ACCEPTED);
        hypothesisRepository.save(hypothesis);
        commitHypothesesAsDsl(List.of(hypothesis), "accepted-" + hypothesisId, effectiveContext);

        log.info("Accepted hypothesis {}: {} --[{}]--> {} (relation created: {})",
                hypothesisId, hypothesis.getSourceNodeId(), hypothesis.getRelationType(),
                hypothesis.getTargetNodeId(), relationCreated);
        return hypothesis;
    }

    @Transactional
    public RelationHypothesis reject(Long hypothesisId, WorkspaceContext workspaceContext) {
        RelationHypothesis hypothesis = requireWritableHypothesis(
                hypothesisId, requireContext(workspaceContext));
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
    public RelationHypothesis applyForSession(Long hypothesisId, WorkspaceContext workspaceContext) {
        RelationHypothesis hypothesis = requireWritableHypothesis(
                hypothesisId, requireContext(workspaceContext));
        hypothesis.setAppliedInCurrentAnalysis(true);
        return hypothesisRepository.save(hypothesis);
    }

    @Transactional(readOnly = true)
    public List<RelationHypothesis> findByStatus(HypothesisStatus status,
                                                  WorkspaceContext workspaceContext) {
        WorkspaceContext context = requireContext(workspaceContext);
        if (context.workspaceId() == null) {
            return hypothesisRepository.findByStatusAndWorkspaceIdIsNull(status);
        }
        return hypothesisRepository.findByStatusAndWorkspace(status, context.workspaceId());
    }

    @Transactional(readOnly = true)
    public List<RelationHypothesis> findAll(WorkspaceContext workspaceContext) {
        WorkspaceContext context = requireContext(workspaceContext);
        if (context.workspaceId() == null) {
            return hypothesisRepository.findByWorkspaceIdIsNull();
        }
        return hypothesisRepository.findByWorkspaceIdIsNullOrWorkspaceId(context.workspaceId());
    }

    @Transactional(readOnly = true)
    public List<RelationEvidence> findEvidence(Long hypothesisId,
                                                WorkspaceContext workspaceContext) {
        requireReadableHypothesis(hypothesisId, requireContext(workspaceContext));
        return evidenceRepository.findByHypothesisId(hypothesisId);
    }

    private RelationHypothesis requireReadableHypothesis(Long hypothesisId,
                                                          WorkspaceContext context) {
        RelationHypothesis hypothesis = hypothesisRepository.findById(hypothesisId)
                .orElseThrow(() -> hypothesisNotFound(hypothesisId));
        String hypothesisWorkspace = hypothesis.getWorkspaceId();
        String currentWorkspace = context.workspaceId();
        boolean visible = currentWorkspace == null
                ? hypothesisWorkspace == null
                : hypothesisWorkspace == null || currentWorkspace.equals(hypothesisWorkspace);
        if (!visible) {
            throw hypothesisNotFound(hypothesisId);
        }
        return hypothesis;
    }

    private RelationHypothesis requireWritableHypothesis(Long hypothesisId,
                                                          WorkspaceContext context) {
        RelationHypothesis hypothesis = hypothesisRepository.findById(hypothesisId)
                .orElseThrow(() -> hypothesisNotFound(hypothesisId));
        if (!sameWorkspace(hypothesis.getWorkspaceId(), context.workspaceId())) {
            // Hide the existence of hypotheses owned by another workspace.
            throw hypothesisNotFound(hypothesisId);
        }
        return hypothesis;
    }

    private IllegalArgumentException hypothesisNotFound(Long hypothesisId) {
        return new IllegalArgumentException("Hypothesis not found: " + hypothesisId);
    }

    private void commitHypothesesAsDsl(List<RelationHypothesis> hypotheses,
                                        String sessionId,
                                        WorkspaceContext context) {
        String dslText = generateCanonicalDsl(hypotheses, sessionId);
        String branch = hypotheses.stream()
                .anyMatch(h -> h.getStatus() == HypothesisStatus.ACCEPTED)
                ? "accepted" : "draft";
        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        try {
            String commitId = repository.commitDsl(
                    branch,
                    dslText,
                    context.username() != null ? context.username() : "hypothesis-service",
                    "Auto-generated from analysis session " + sessionId);
            log.info("Committed {} hypotheses as canonical DSL to workspace {} branch '{}': {}",
                    hypotheses.size(), context.workspaceId(), branch, commitId);
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
            throw new IllegalStateException("Canonical hypothesis DSL round-trip lost elements or relations");
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

    private WorkspaceContext requireContext(WorkspaceContext context) {
        return context != null ? context : WorkspaceContext.SHARED;
    }

    private static boolean sameWorkspace(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}

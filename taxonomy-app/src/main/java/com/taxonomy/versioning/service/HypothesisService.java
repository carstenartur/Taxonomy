package com.taxonomy.versioning.service;

import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.model.*;
import com.taxonomy.relations.repository.RelationEvidenceRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.taxonomy.analysis.service.AnalysisRelationGenerator;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationEvidence;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;

/**
 * Manages the lifecycle of relation hypotheses: persistence, acceptance, and rejection.
 *
 * <p>Bridges the gap between:
 * <ul>
 *   <li>{@link AnalysisRelationGenerator} which produces in-memory DTOs</li>
 *   <li>{@link RelationHypothesis} entities persisted in the database</li>
 *   <li>{@link TaxonomyRelation} entities created when hypotheses are accepted</li>
 *   <li>{@link DslGitRepository} for versioned DSL commits on the "draft" branch</li>
 * </ul>
 *
 * <p>When hypotheses are persisted from analysis, a DSL representation is
 * automatically generated and committed to the "draft" Git branch.
 */
@Service
public class HypothesisService {

    private static final Logger log = LoggerFactory.getLogger(HypothesisService.class);

    private final RelationHypothesisRepository hypothesisRepository;
    private final RelationEvidenceRepository evidenceRepository;
    private final TaxonomyRelationService relationService;
    private final TaxonomyNodeRepository nodeRepository;
    private final DslGitRepository gitRepository;
    private final WorkspaceContextResolver contextResolver;

    public HypothesisService(RelationHypothesisRepository hypothesisRepository,
                             RelationEvidenceRepository evidenceRepository,
                             TaxonomyRelationService relationService,
                             TaxonomyNodeRepository nodeRepository,
                             DslGitRepository gitRepository,
                             WorkspaceContextResolver contextResolver) {
        this.hypothesisRepository = hypothesisRepository;
        this.evidenceRepository = evidenceRepository;
        this.relationService = relationService;
        this.nodeRepository = nodeRepository;
        this.gitRepository = gitRepository;
        this.contextResolver = contextResolver;
    }

    /**
     * Persist provisional relation hypothesis DTOs (from analysis) to the database
     * and commit a DSL representation to the "draft" Git branch.
     *
     * <p>Each DTO is saved as a {@link RelationHypothesis} entity with status
     * {@link HypothesisStatus#PROVISIONAL}. Duplicates (same source+target+type
     * within the same session) are skipped.
     *
     * <p>After persisting, a DSL document is generated from the hypotheses and
     * committed to the "draft" branch in the JGit repository.
     *
     * @param hypotheses  the provisional hypotheses from analysis
     * @param sessionId   unique identifier for the analysis session
     * @return the persisted entities
     */
    @Transactional
    public List<RelationHypothesis> persistFromAnalysis(List<RelationHypothesisDto> hypotheses,
                                                         String sessionId) {
        if (hypotheses == null || hypotheses.isEmpty()) {
            return List.of();
        }
        final String effectiveSessionId = sessionId != null ? sessionId : UUID.randomUUID().toString();

        List<RelationHypothesis> persisted = new ArrayList<>();
        for (RelationHypothesisDto dto : hypotheses) {
            // Skip duplicates in the same session
            List<RelationHypothesis> existing = hypothesisRepository
                    .findBySourceNodeIdAndTargetNodeIdAndRelationType(
                            dto.getSourceCode(), dto.getTargetCode(),
                            RelationType.valueOf(dto.getRelationType()));
            boolean alreadyExists = existing.stream()
                    .anyMatch(h -> effectiveSessionId.equals(h.getAnalysisSessionId()));
            if (alreadyExists) {
                continue;
            }

            RelationHypothesis entity = new RelationHypothesis();
            entity.setSourceNodeId(dto.getSourceCode());
            entity.setTargetNodeId(dto.getTargetCode());
            entity.setRelationType(RelationType.valueOf(dto.getRelationType()));
            entity.setConfidence(dto.getConfidence());
            entity.setStatus(HypothesisStatus.PROVISIONAL);
            entity.setAnalysisSessionId(effectiveSessionId);

            WorkspaceContext ctx = contextResolver.resolveCurrentContext();
            entity.setWorkspaceId(ctx.workspaceId());
            entity.setOwnerUsername(ctx.username());

            RelationHypothesis saved = hypothesisRepository.save(entity);
            persisted.add(saved);

            // Create evidence record from reasoning
            if (dto.getReasoning() != null && !dto.getReasoning().isBlank()) {
                RelationEvidence evidence = new RelationEvidence();
                evidence.setHypothesis(saved);
                evidence.setEvidenceType("analysis-rule");
                evidence.setSummary(dto.getReasoning());
                evidence.setConfidence(dto.getConfidence());
                evidenceRepository.save(evidence);
            }
        }

        log.info("Persisted {} hypotheses for session {}", persisted.size(), effectiveSessionId);

        // Generate DSL and commit to "draft" branch
        if (!persisted.isEmpty()) {
            commitHypothesesAsDsl(persisted, effectiveSessionId);
        }

        return persisted;
    }

    /**
     * Accept a hypothesis: creates a real {@link TaxonomyRelation} and marks the
     * hypothesis as {@link HypothesisStatus#ACCEPTED}.
     *
     * <p>If the source or target nodes no longer exist in the taxonomy, the hypothesis
     * is still marked as accepted but no relation is created.
     *
     * <p>After acceptance, a DSL commit is created on the "accepted" branch.
     *
     * @param hypothesisId the ID of the hypothesis to accept
     * @return the accepted hypothesis entity
     * @throws IllegalArgumentException if the hypothesis is not found
     * @throws IllegalStateException if the hypothesis is not in PROVISIONAL or PROPOSED status
     */
    @Transactional
    public RelationHypothesis accept(Long hypothesisId) {
        RelationHypothesis hypothesis = hypothesisRepository.findById(hypothesisId)
                .orElseThrow(() -> new IllegalArgumentException("Hypothesis not found: " + hypothesisId));

        if (hypothesis.getStatus() == HypothesisStatus.ACCEPTED) {
            throw new IllegalStateException("Hypothesis " + hypothesisId + " is already ACCEPTED");
        }
        if (hypothesis.getStatus() == HypothesisStatus.REJECTED) {
            throw new IllegalStateException("Hypothesis " + hypothesisId + " is already REJECTED");
        }

        // Create real TaxonomyRelation if both nodes exist
        boolean relationCreated = false;
        if (nodeRepository.findByCode(hypothesis.getSourceNodeId()).isPresent()
                && nodeRepository.findByCode(hypothesis.getTargetNodeId()).isPresent()) {
            WorkspaceContext ctx = contextResolver.resolveCurrentContext();
            relationService.createRelation(
                    hypothesis.getSourceNodeId(),
                    hypothesis.getTargetNodeId(),
                    hypothesis.getRelationType(),
                    "Accepted from hypothesis " + hypothesisId,
                    "hypothesis-accepted",
                    ctx.workspaceId(), ctx.username());
            relationCreated = true;
        } else {
            log.warn("Could not create relation for hypothesis {}: source or target node not found",
                    hypothesisId);
        }

        hypothesis.setStatus(HypothesisStatus.ACCEPTED);
        hypothesisRepository.save(hypothesis);

        log.info("Accepted hypothesis {}: {} --[{}]--> {} (relation created: {})",
                hypothesisId, hypothesis.getSourceNodeId(),
                hypothesis.getRelationType(), hypothesis.getTargetNodeId(),
                relationCreated);

        // Commit accepted relation as DSL to "accepted" branch
        commitHypothesesAsDsl(List.of(hypothesis), "accepted-" + hypothesisId);

        return hypothesis;
    }

    /**
     * Reject a hypothesis.
     *
     * @param hypothesisId the ID of the hypothesis to reject
     * @return the rejected hypothesis entity
     */
    @Transactional
    public RelationHypothesis reject(Long hypothesisId) {
        RelationHypothesis hypothesis = hypothesisRepository.findById(hypothesisId)
                .orElseThrow(() -> new IllegalArgumentException("Hypothesis not found: " + hypothesisId));

        if (hypothesis.getStatus() == HypothesisStatus.ACCEPTED) {
            throw new IllegalStateException("Hypothesis " + hypothesisId + " is already ACCEPTED");
        }
        if (hypothesis.getStatus() == HypothesisStatus.REJECTED) {
            throw new IllegalStateException("Hypothesis " + hypothesisId + " is already REJECTED");
        }

        hypothesis.setStatus(HypothesisStatus.REJECTED);
        hypothesisRepository.save(hypothesis);

        log.info("Rejected hypothesis {}: {} --[{}]--> {}",
                hypothesisId, hypothesis.getSourceNodeId(),
                hypothesis.getRelationType(), hypothesis.getTargetNodeId());

        return hypothesis;
    }

    /**
     * Mark a hypothesis as "applied for this session only".
     *
     * <p>The relationship is used in the current Architecture View and exports
     * but is not permanently persisted as a {@link TaxonomyRelation}.
     */
    @Transactional
    public RelationHypothesis applyForSession(Long hypothesisId) {
        RelationHypothesis hypothesis = hypothesisRepository.findById(hypothesisId)
                .orElseThrow(() -> new IllegalArgumentException("Hypothesis not found: " + hypothesisId));

        hypothesis.setAppliedInCurrentAnalysis(true);
        hypothesisRepository.save(hypothesis);

        log.info("Applied hypothesis {} for current session: {} --[{}]--> {}",
                hypothesisId, hypothesis.getSourceNodeId(),
                hypothesis.getRelationType(), hypothesis.getTargetNodeId());

        return hypothesis;
    }

    /**
     * List hypotheses by status.
     */
    @Transactional(readOnly = true)
    public List<RelationHypothesis> findByStatus(HypothesisStatus status) {
        return hypothesisRepository.findByStatus(status);
    }

    /**
     * List all hypotheses.
     */
    @Transactional(readOnly = true)
    public List<RelationHypothesis> findAll() {
        return hypothesisRepository.findAll();
    }

    /**
     * Find evidence records for a hypothesis.
     */
    @Transactional(readOnly = true)
    public List<RelationEvidence> findEvidence(Long hypothesisId) {
        return evidenceRepository.findByHypothesisId(hypothesisId);
    }

    // ── DSL generation + Git commit ─────────────────────────────────

    /**
     * Generate DSL text from hypotheses and commit to the appropriate Git branch.
     *
     * <p>Provisional hypotheses go to "draft", accepted hypotheses go to "accepted".
     */
    private void commitHypothesesAsDsl(List<RelationHypothesis> hypotheses, String sessionId) {
        try {
            String dslText = generateDsl(hypotheses);
            String branch = hypotheses.stream()
                    .anyMatch(h -> h.getStatus() == HypothesisStatus.ACCEPTED)
                    ? "accepted" : "draft";

            String commitId = gitRepository.commitDsl(
                    branch, dslText,
                    "hypothesis-service",
                    "Auto-generated from analysis session " + sessionId);

            log.info("Committed {} hypotheses as DSL to branch '{}': {}",
                    hypotheses.size(), branch, commitId);
        } catch (IOException e) {
            log.warn("Failed to commit hypotheses as DSL to Git: {}", e.getMessage());
            // Non-fatal: DB persistence already succeeded
        }
    }

    /**
     * Generate DSL text from a list of hypotheses.
     */
    private String generateDsl(List<RelationHypothesis> hypotheses) {
        StringBuilder sb = new StringBuilder();
        sb.append("meta\n");
        sb.append("  language \"taxdsl\"\n");
        sb.append("  version \"1.0\"\n");
        sb.append("  namespace \"hypothesis-auto\"\n\n");

        // Track declared elements to avoid duplicates
        java.util.Set<String> declaredElements = new java.util.LinkedHashSet<>();

        for (RelationHypothesis h : hypotheses) {
            // Element declarations for source and target (avoid duplicates)
            if (declaredElements.add(h.getSourceNodeId())) {
                sb.append("element ").append(h.getSourceNodeId()).append(" type Node\n");
                sb.append("  title \"").append(h.getSourceNodeId()).append("\"\n\n");
            }
            if (declaredElements.add(h.getTargetNodeId())) {
                sb.append("element ").append(h.getTargetNodeId()).append(" type Node\n");
                sb.append("  title \"").append(h.getTargetNodeId()).append("\"\n\n");
            }

            // Relation declaration
            sb.append("relation ").append(h.getSourceNodeId()).append(" ")
                    .append(h.getRelationType().name()).append(" ")
                    .append(h.getTargetNodeId()).append("\n");
            sb.append("  status ").append(h.getStatus().name().toLowerCase()).append("\n");
            if (h.getConfidence() > 0) {
                sb.append("  confidence ").append(String.format("%.2f", h.getConfidence())).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}

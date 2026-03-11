package com.nato.taxonomy.service;

import com.nato.taxonomy.dto.RelationHypothesisDto;
import com.nato.taxonomy.model.*;
import com.nato.taxonomy.repository.RelationEvidenceRepository;
import com.nato.taxonomy.repository.RelationHypothesisRepository;
import com.nato.taxonomy.repository.TaxonomyNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages the lifecycle of relation hypotheses: persistence, acceptance, and rejection.
 *
 * <p>Bridges the gap between:
 * <ul>
 *   <li>{@link AnalysisRelationGenerator} which produces in-memory DTOs</li>
 *   <li>{@link RelationHypothesis} entities persisted in the database</li>
 *   <li>{@link TaxonomyRelation} entities created when hypotheses are accepted</li>
 * </ul>
 */
@Service
public class HypothesisService {

    private static final Logger log = LoggerFactory.getLogger(HypothesisService.class);

    private final RelationHypothesisRepository hypothesisRepository;
    private final RelationEvidenceRepository evidenceRepository;
    private final TaxonomyRelationService relationService;
    private final TaxonomyNodeRepository nodeRepository;

    public HypothesisService(RelationHypothesisRepository hypothesisRepository,
                             RelationEvidenceRepository evidenceRepository,
                             TaxonomyRelationService relationService,
                             TaxonomyNodeRepository nodeRepository) {
        this.hypothesisRepository = hypothesisRepository;
        this.evidenceRepository = evidenceRepository;
        this.relationService = relationService;
        this.nodeRepository = nodeRepository;
    }

    /**
     * Persist provisional relation hypothesis DTOs (from analysis) to the database.
     *
     * <p>Each DTO is saved as a {@link RelationHypothesis} entity with status
     * {@link HypothesisStatus#PROVISIONAL}. Duplicates (same source+target+type
     * within the same session) are skipped.
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
        return persisted;
    }

    /**
     * Accept a hypothesis: creates a real {@link TaxonomyRelation} and marks the
     * hypothesis as {@link HypothesisStatus#ACCEPTED}.
     *
     * <p>If the source or target nodes no longer exist in the taxonomy, the hypothesis
     * is still marked as accepted but no relation is created.
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
            relationService.createRelation(
                    hypothesis.getSourceNodeId(),
                    hypothesis.getTargetNodeId(),
                    hypothesis.getRelationType(),
                    "Accepted from hypothesis " + hypothesisId,
                    "hypothesis-accepted");
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
}

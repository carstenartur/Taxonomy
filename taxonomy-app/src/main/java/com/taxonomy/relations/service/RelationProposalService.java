package com.taxonomy.relations.service;

import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.model.*;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;

/**
 * Orchestrator for the Relation Proposal Pipeline.
 *
 * <p>Pipeline stages:
 * <ol>
 *   <li>Candidate Search (via {@link RelationCandidateService})</li>
 *   <li>RelationType Compatibility filtering</li>
 *   <li>Validation (via {@link RelationValidationService})</li>
 *   <li>Confidence scoring</li>
 *   <li>Proposal creation &amp; persistence</li>
 * </ol>
 */
@Service
public class RelationProposalService {

    private static final Logger log = LoggerFactory.getLogger(RelationProposalService.class);

    private final TaxonomyNodeRepository nodeRepository;
    private final RelationProposalRepository proposalRepository;
    private final RelationCandidateService candidateService;
    private final RelationValidationService validationService;
    private final WorkspaceContextResolver contextResolver;

    public RelationProposalService(TaxonomyNodeRepository nodeRepository,
                                   RelationProposalRepository proposalRepository,
                                   RelationCandidateService candidateService,
                                   RelationValidationService validationService,
                                   WorkspaceContextResolver contextResolver) {
        this.nodeRepository = nodeRepository;
        this.proposalRepository = proposalRepository;
        this.candidateService = candidateService;
        this.validationService = validationService;
        this.contextResolver = contextResolver;
    }

    /**
     * Runs the full proposal pipeline for a source node and relation type.
     *
     * @param sourceNodeCode code of the source taxonomy node (e.g. "BP.001")
     * @param relationType   the type of relation to propose
     * @param limit          maximum number of candidates to evaluate
     * @return list of created proposals (only those that passed validation)
     */
    @Transactional
    public List<RelationProposalDto> proposeRelations(String sourceNodeCode,
                                                       RelationType relationType,
                                                       int limit) {
        TaxonomyNode source = nodeRepository.findByCode(sourceNodeCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Source node not found: " + sourceNodeCode));

        log.info("Proposing {} relations for node '{}'", relationType, sourceNodeCode);

        // 1. Candidate search
        List<TaxonomyNodeDto> candidates = candidateService.findCandidates(
                source, relationType, limit);

        List<RelationProposalDto> proposals = new ArrayList<>();

        // Resolve workspace context
        WorkspaceContext ctx = contextResolver.resolveCurrentContext();

        // 2–4. Validate each candidate and create proposals
        for (int i = 0; i < candidates.size(); i++) {
            TaxonomyNodeDto candidate = candidates.get(i);

            // Skip if a proposal already exists for this triple in this workspace
            if (ctx.workspaceId() != null) {
                if (proposalRepository.existsBySourceNodeCodeAndTargetNodeCodeAndRelationTypeAndWorkspaceId(
                        sourceNodeCode, candidate.getCode(), relationType, ctx.workspaceId())) {
                    log.debug("Proposal already exists: {} → {} [{}] (workspace={})",
                            sourceNodeCode, candidate.getCode(), relationType, ctx.workspaceId());
                    continue;
                }
            } else if (proposalRepository.existsBySourceNodeCodeAndTargetNodeCodeAndRelationType(
                    sourceNodeCode, candidate.getCode(), relationType)) {
                log.debug("Proposal already exists: {} → {} [{}]",
                        sourceNodeCode, candidate.getCode(), relationType);
                continue;
            }

            RelationValidationService.ValidationResult result =
                    validationService.validate(source, candidate, relationType,
                            i, candidates.size());

            if (result.isValid()) {
                TaxonomyNode target = nodeRepository.findByCode(candidate.getCode())
                        .orElse(null);
                if (target == null) continue;

                RelationProposal proposal = new RelationProposal();
                proposal.setSourceNode(source);
                proposal.setTargetNode(target);
                proposal.setRelationType(relationType);
                proposal.setConfidence(result.getConfidence());
                proposal.setRationale(result.getRationale());
                proposal.setProvenance("hybrid-search");
                proposal.setStatus(ProposalStatus.PENDING);
                proposal.setWorkspaceId(ctx.workspaceId());
                proposal.setOwnerUsername(ctx.username());

                RelationProposal saved = proposalRepository.save(proposal);
                proposals.add(toDto(saved));
                log.debug("Created proposal: {} → {} [{}] confidence={}",
                        sourceNodeCode, candidate.getCode(), relationType,
                        result.getConfidence());
            }
        }

        log.info("Proposed {} relations for node '{}' [{}]",
                proposals.size(), sourceNodeCode, relationType);
        return proposals;
    }

    @Transactional(readOnly = true)
    public List<RelationProposalDto> getPendingProposals() {
        WorkspaceContext ctx = contextResolver.resolveCurrentContext();
        List<RelationProposal> proposals;
        if (ctx.workspaceId() != null) {
            proposals = proposalRepository.findByStatusAndWorkspace(ProposalStatus.PENDING, ctx.workspaceId());
        } else {
            proposals = proposalRepository.findByStatus(ProposalStatus.PENDING);
        }
        return proposals.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<RelationProposalDto> getAllProposals() {
        WorkspaceContext ctx = contextResolver.resolveCurrentContext();
        List<RelationProposal> proposals;
        if (ctx.workspaceId() != null) {
            proposals = proposalRepository.findByWorkspaceIdIsNullOrWorkspaceId(ctx.workspaceId());
        } else {
            proposals = proposalRepository.findAll();
        }
        return proposals.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<RelationProposalDto> getProposalsForNode(String sourceCode) {
        WorkspaceContext ctx = contextResolver.resolveCurrentContext();
        List<RelationProposal> proposals;
        if (ctx.workspaceId() != null) {
            proposals = proposalRepository.findBySourceNodeCodeAndWorkspace(sourceCode, ctx.workspaceId());
        } else {
            proposals = proposalRepository.findBySourceNodeCode(sourceCode);
        }
        return proposals.stream().map(this::toDto).toList();
    }

    /**
     * Creates a proposal directly from a provisional relation hypothesis.
     * Used to persist an AI-generated hypothesis as a formal proposal for review.
     *
     * @param sourceCode   source node code
     * @param targetCode   target node code
     * @param relationType the type of relation
     * @param confidence   confidence score (0.0–1.0)
     * @param rationale    explanation for the proposal
     * @return the created proposal DTO, or {@code null} if the proposal already exists
     */
    @Transactional
    public RelationProposalDto createFromHypothesis(String sourceCode, String targetCode,
                                                     RelationType relationType,
                                                     double confidence, String rationale) {
        TaxonomyNode source = nodeRepository.findByCode(sourceCode)
                .orElseThrow(() -> new IllegalArgumentException("Source node not found: " + sourceCode));
        TaxonomyNode target = nodeRepository.findByCode(targetCode)
                .orElseThrow(() -> new IllegalArgumentException("Target node not found: " + targetCode));

        if (proposalRepository.existsBySourceNodeCodeAndTargetNodeCodeAndRelationType(
                sourceCode, targetCode, relationType)) {
            log.debug("Proposal already exists: {} → {} [{}]", sourceCode, targetCode, relationType);
            return null;
        }

        RelationProposal proposal = new RelationProposal();
        proposal.setSourceNode(source);
        proposal.setTargetNode(target);
        proposal.setRelationType(relationType);
        proposal.setConfidence(confidence);
        proposal.setRationale(rationale);
        proposal.setProvenance("analysis-hypothesis");
        proposal.setStatus(ProposalStatus.PENDING);

        WorkspaceContext ctx = contextResolver.resolveCurrentContext();
        proposal.setWorkspaceId(ctx.workspaceId());
        proposal.setOwnerUsername(ctx.username());

        RelationProposal saved = proposalRepository.save(proposal);
        log.info("Created proposal from hypothesis: {} → {} [{}] confidence={}",
                sourceCode, targetCode, relationType, confidence);
        return toDto(saved);
    }

    public RelationProposalDto toDto(RelationProposal proposal) {
        RelationProposalDto dto = new RelationProposalDto();
        dto.setId(proposal.getId());
        dto.setSourceCode(proposal.getSourceNode().getCode());
        dto.setSourceName(proposal.getSourceNode().getNameEn());
        dto.setTargetCode(proposal.getTargetNode().getCode());
        dto.setTargetName(proposal.getTargetNode().getNameEn());
        dto.setRelationType(proposal.getRelationType().name());
        dto.setStatus(proposal.getStatus().name());
        dto.setConfidence(proposal.getConfidence());
        dto.setRationale(proposal.getRationale());
        dto.setProvenance(proposal.getProvenance());
        dto.setExplanationBasis(deriveExplanationBasis(proposal));
        dto.setCreatedAt(proposal.getCreatedAt());
        dto.setReviewedAt(proposal.getReviewedAt());
        return dto;
    }

    /**
     * Derives a human-readable explanation of how and why a proposal was created.
     */
    private String deriveExplanationBasis(RelationProposal proposal) {
        String provenance = proposal.getProvenance();
        if (provenance == null) return "unknown source";

        return switch (provenance) {
            case "hybrid-search" -> "Discovered via hybrid search (semantic + keyword) "
                    + "and validated against the relation compatibility matrix";
            case "analysis-hypothesis" -> "Derived from LLM analysis of a business requirement";
            default -> "Source: " + provenance;
        };
    }
}

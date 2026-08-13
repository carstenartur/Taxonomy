package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.relations.service.RelationProjectionReadService.IdentitySnapshot;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrator for the Relation Proposal Pipeline.
 *
 * <p>Every persisted/read proposal is scoped by an explicit
 * {@link RepositoryContext}; a null workspace means central state only inside
 * that repository and never a global proposal set.</p>
 *
 * <p>Pipeline stages:
 * <ol>
 *   <li>Resolve one complete active-relation identity snapshot</li>
 *   <li>Candidate Search (via {@link RelationCandidateService})</li>
 *   <li>RelationType Compatibility filtering</li>
 *   <li>Validation (via {@link RelationValidationService})</li>
 *   <li>Confidence scoring</li>
 *   <li>Proposal creation and tenant-scoped persistence</li>
 * </ol>
 */
@Service
public class RelationProposalService {

    private static final Logger log = LoggerFactory.getLogger(
            RelationProposalService.class);

    private final TaxonomyNodeRepository nodeRepository;
    private final RelationProposalRepository proposalRepository;
    private final RelationCandidateService candidateService;
    private final RelationProjectionReadService relationReadService;
    private final RelationValidationService validationService;
    private final WorkspaceResolver workspaceResolver;

    public RelationProposalService(
            TaxonomyNodeRepository nodeRepository,
            RelationProposalRepository proposalRepository,
            RelationCandidateService candidateService,
            RelationProjectionReadService relationReadService,
            RelationValidationService validationService,
            WorkspaceResolver workspaceResolver) {
        this.nodeRepository = nodeRepository;
        this.proposalRepository = proposalRepository;
        this.candidateService = candidateService;
        this.relationReadService = relationReadService;
        this.validationService = validationService;
        this.workspaceResolver = workspaceResolver;
    }

    /**
     * Resolve one request-stable context and run the proposal pipeline.
     *
     * <p>This compatibility boundary deliberately fails for a central read-only
     * context. HTTP boundaries that authorize central mutation must convert it to
     * {@link RepositoryScope#CENTRAL_WRITE} explicitly before calling the
     * context-aware method.</p>
     */
    @Transactional
    public List<RelationProposalDto> proposeRelations(
            String sourceNodeCode,
            RelationType relationType,
            int limit) {
        return proposeRelationsInContext(
                sourceNodeCode,
                relationType,
                limit,
                workspaceResolver.resolveCurrentRepositoryContext());
    }

    /** Runs the full proposal pipeline in one explicit writable tenant. */
    @Transactional
    public List<RelationProposalDto> proposeRelationsInContext(
            String sourceNodeCode,
            RelationType relationType,
            int limit,
            RepositoryContext context) {
        RepositoryContext tenant = requireWritableContext(context);
        TaxonomyNode source = nodeRepository.findByCode(sourceNodeCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Source node not found: " + sourceNodeCode));

        log.info("Proposing {} relations for node '{}' "
                        + "(repository={}, workspace={}, branch={})",
                relationType,
                sourceNodeCode,
                tenant.repositoryId(),
                tenant.workspaceId(),
                tenant.branch());

        IdentitySnapshot existingRelations =
                relationReadService.readIdentitySnapshot(tenant);
        List<TaxonomyNodeDto> candidates = candidateService.findCandidates(
                source, relationType, limit);
        List<RelationProposalDto> proposals = new ArrayList<>();

        for (int i = 0; i < candidates.size(); i++) {
            TaxonomyNodeDto candidate = candidates.get(i);

            if (proposalRepository.existsInRepositoryWorkspace(
                    tenant.repositoryId(),
                    sourceNodeCode,
                    candidate.getCode(),
                    relationType,
                    tenant.workspaceId())) {
                log.debug("Proposal already exists: {} → {} [{}] "
                                + "(repository={}, workspace={})",
                        sourceNodeCode,
                        candidate.getCode(),
                        relationType,
                        tenant.repositoryId(),
                        tenant.workspaceId());
                continue;
            }

            if (existingRelations.contains(
                    sourceNodeCode,
                    relationType,
                    candidate.getCode())) {
                log.debug("Active relation already exists: {} → {} [{}] "
                                + "(repository={}, workspace={}, branch={})",
                        sourceNodeCode,
                        candidate.getCode(),
                        relationType,
                        tenant.repositoryId(),
                        tenant.workspaceId(),
                        tenant.branch());
                continue;
            }

            RelationValidationService.ValidationResult result =
                    validationService.validate(
                            source,
                            candidate,
                            relationType,
                            i,
                            candidates.size(),
                            tenant);

            if (!result.isValid()) {
                continue;
            }

            TaxonomyNode target = nodeRepository.findByCode(candidate.getCode())
                    .orElse(null);
            if (target == null) {
                continue;
            }

            RelationProposal proposal = new RelationProposal();
            proposal.setRepositoryId(tenant.repositoryId());
            proposal.setSourceNode(source);
            proposal.setTargetNode(target);
            proposal.setRelationType(relationType);
            proposal.setConfidence(result.getConfidence());
            proposal.setRationale(result.getRationale());
            proposal.setProvenance("hybrid-search");
            proposal.setStatus(ProposalStatus.PENDING);
            proposal.setWorkspaceId(tenant.workspaceId());
            proposal.setOwnerUsername(tenant.username());

            RelationProposal saved = proposalRepository.save(proposal);
            proposals.add(toDto(saved));
            log.debug("Created proposal: {} → {} [{}] confidence={} "
                            + "(repository={}, workspace={})",
                    sourceNodeCode,
                    candidate.getCode(),
                    relationType,
                    result.getConfidence(),
                    tenant.repositoryId(),
                    tenant.workspaceId());
        }

        log.info("Proposed {} relations for node '{}' [{}] "
                        + "(repository={}, workspace={}, branch={}, relationCommit={})",
                proposals.size(),
                sourceNodeCode,
                relationType,
                tenant.repositoryId(),
                tenant.workspaceId(),
                tenant.branch(),
                existingRelations.authoritativeCommitId());
        return proposals;
    }

    @Transactional(readOnly = true)
    public List<RelationProposalDto> getPendingProposals() {
        return getPendingProposalsInContext(
                workspaceResolver.resolveCurrentRepositoryContext());
    }

    @Transactional(readOnly = true)
    public List<RelationProposalDto> getPendingProposalsInContext(
            RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        List<RelationProposal> proposals = tenant.workspaceId() == null
                ? proposalRepository.findCentralByRepositoryAndStatus(
                        tenant.repositoryId(), ProposalStatus.PENDING)
                : proposalRepository.findVisibleByRepositoryAndWorkspaceAndStatus(
                        tenant.repositoryId(),
                        tenant.workspaceId(),
                        ProposalStatus.PENDING);
        return proposals.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<RelationProposalDto> getAllProposals() {
        return getAllProposalsInContext(
                workspaceResolver.resolveCurrentRepositoryContext());
    }

    @Transactional(readOnly = true)
    public List<RelationProposalDto> getAllProposalsInContext(
            RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        List<RelationProposal> proposals = tenant.workspaceId() == null
                ? proposalRepository.findCentralByRepository(tenant.repositoryId())
                : proposalRepository.findVisibleByRepositoryAndWorkspace(
                        tenant.repositoryId(), tenant.workspaceId());
        return proposals.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<RelationProposalDto> getProposalsForNode(String sourceCode) {
        return getProposalsForNodeInContext(
                sourceCode,
                workspaceResolver.resolveCurrentRepositoryContext());
    }

    @Transactional(readOnly = true)
    public List<RelationProposalDto> getProposalsForNodeInContext(
            String sourceCode,
            RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        List<RelationProposal> proposals = tenant.workspaceId() == null
                ? proposalRepository.findCentralByRepositoryAndSourceNodeCode(
                        tenant.repositoryId(), sourceCode)
                : proposalRepository.findVisibleByRepositoryAndWorkspaceAndSourceNodeCode(
                        tenant.repositoryId(), tenant.workspaceId(), sourceCode);
        return proposals.stream().map(this::toDto).toList();
    }

    /**
     * Resolve one request-stable context and create a proposal from a hypothesis.
     * Central read-only contexts fail closed for the same reason as
     * {@link #proposeRelations(String, RelationType, int)}.
     */
    @Transactional
    public RelationProposalDto createFromHypothesis(
            String sourceCode,
            String targetCode,
            RelationType relationType,
            double confidence,
            String rationale) {
        return createFromHypothesisInContext(
                sourceCode,
                targetCode,
                relationType,
                confidence,
                rationale,
                workspaceResolver.resolveCurrentRepositoryContext());
    }

    /** Creates a formal proposal from a provisional hypothesis in the exact writable tenant. */
    @Transactional
    public RelationProposalDto createFromHypothesisInContext(
            String sourceCode,
            String targetCode,
            RelationType relationType,
            double confidence,
            String rationale,
            RepositoryContext context) {
        RepositoryContext tenant = requireWritableContext(context);
        TaxonomyNode source = nodeRepository.findByCode(sourceCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Source node not found: " + sourceCode));
        TaxonomyNode target = nodeRepository.findByCode(targetCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Target node not found: " + targetCode));

        if (proposalRepository.existsInRepositoryWorkspace(
                tenant.repositoryId(),
                sourceCode,
                targetCode,
                relationType,
                tenant.workspaceId())) {
            log.debug("Proposal already exists: {} → {} [{}] "
                            + "(repository={}, workspace={})",
                    sourceCode,
                    targetCode,
                    relationType,
                    tenant.repositoryId(),
                    tenant.workspaceId());
            return null;
        }

        IdentitySnapshot existingRelations =
                relationReadService.readIdentitySnapshot(tenant);
        if (existingRelations.contains(sourceCode, relationType, targetCode)) {
            log.debug("Active relation already exists: {} → {} [{}] "
                            + "(repository={}, workspace={}, branch={})",
                    sourceCode,
                    targetCode,
                    relationType,
                    tenant.repositoryId(),
                    tenant.workspaceId(),
                    tenant.branch());
            return null;
        }

        RelationProposal proposal = new RelationProposal();
        proposal.setRepositoryId(tenant.repositoryId());
        proposal.setSourceNode(source);
        proposal.setTargetNode(target);
        proposal.setRelationType(relationType);
        proposal.setConfidence(confidence);
        proposal.setRationale(rationale);
        proposal.setProvenance("analysis-hypothesis");
        proposal.setStatus(ProposalStatus.PENDING);
        proposal.setWorkspaceId(tenant.workspaceId());
        proposal.setOwnerUsername(tenant.username());

        RelationProposal saved = proposalRepository.save(proposal);
        log.info("Created proposal from hypothesis: {} → {} [{}] confidence={} "
                        + "(repository={}, workspace={})",
                sourceCode,
                targetCode,
                relationType,
                confidence,
                tenant.repositoryId(),
                tenant.workspaceId());
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

    private String deriveExplanationBasis(RelationProposal proposal) {
        String provenance = proposal.getProvenance();
        if (provenance == null) {
            return "unknown source";
        }

        return switch (provenance) {
            case "hybrid-search" -> "Discovered via hybrid search (semantic + keyword) "
                    + "and validated against the relation compatibility matrix";
            case "analysis-hypothesis" -> "Derived from LLM analysis of a business requirement";
            default -> "Source: " + provenance;
        };
    }

    private static RepositoryContext requireContext(RepositoryContext context) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "RepositoryContext must not be null");
        }
        return context;
    }

    private static RepositoryContext requireWritableContext(
            RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        if (tenant.workspaceId() == null) {
            if (tenant.scope() != RepositoryScope.CENTRAL_WRITE) {
                throw new IllegalArgumentException(
                        "Central proposal mutation requires CENTRAL_WRITE scope");
            }
            return tenant;
        }
        if (tenant.scope() != RepositoryScope.WORKSPACE
                && tenant.scope() != RepositoryScope.FORK) {
            throw new IllegalArgumentException(
                    "Workspace proposal mutation requires WORKSPACE or FORK scope");
        }
        return tenant;
    }
}

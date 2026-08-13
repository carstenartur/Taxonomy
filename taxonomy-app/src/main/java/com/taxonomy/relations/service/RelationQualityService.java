package com.taxonomy.relations.service;

import com.taxonomy.dto.ProvenanceMetrics;
import com.taxonomy.dto.RelationQualityMetrics;
import com.taxonomy.dto.RelationTypeMetrics;
import com.taxonomy.dto.TopRejectedProposal;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Computes relation-review quality metrics inside one selected repository
 * context and provides the same scoped history as a confidence signal.
 *
 * <p>A central context sees central reviews in exactly one repository. A
 * workspace context intentionally inherits those central reviews and adds only
 * reviews from that exact workspace. Sibling workspaces and other repositories
 * are never part of the normal product view.</p>
 */
@Service
public class RelationQualityService {

    private final RelationProposalRepository proposalRepository;

    public RelationQualityService(RelationProposalRepository proposalRepository) {
        this.proposalRepository = Objects.requireNonNull(
                proposalRepository, "proposalRepository");
    }

    /** Computes the full dashboard from one request-stable visible snapshot. */
    @Transactional(readOnly = true)
    public RelationQualityMetrics calculateMetrics(RepositoryContext context) {
        List<RelationProposal> proposals = visibleProposals(context);
        long accepted = countByStatus(proposals, ProposalStatus.ACCEPTED);
        long rejected = countByStatus(proposals, ProposalStatus.REJECTED);
        long pending = countByStatus(proposals, ProposalStatus.PENDING);
        long reviewed = accepted + rejected;

        return new RelationQualityMetrics(
                Math.toIntExact(accepted + rejected + pending),
                Math.toIntExact(accepted),
                Math.toIntExact(rejected),
                Math.toIntExact(pending),
                reviewed > 0 ? (double) accepted / reviewed : 0.0,
                averageConfidence(proposals, ProposalStatus.ACCEPTED),
                averageConfidence(proposals, ProposalStatus.REJECTED),
                metricsByRelationType(proposals),
                metricsByProvenance(proposals));
    }

    /** Returns metrics by relation type in the exact selected context. */
    @Transactional(readOnly = true)
    public List<RelationTypeMetrics> metricsByRelationType(
            RepositoryContext context) {
        return metricsByRelationType(visibleProposals(context));
    }

    /** Returns metrics by provenance in the exact selected context. */
    @Transactional(readOnly = true)
    public List<ProvenanceMetrics> metricsByProvenance(
            RepositoryContext context) {
        return metricsByProvenance(visibleProposals(context));
    }

    /**
     * Returns rejected proposals visible in the selected context, ordered by
     * descending confidence (the worst false positives first).
     */
    @Transactional(readOnly = true)
    public List<TopRejectedProposal> topRejected(
            int limit,
            RepositoryContext context) {
        if (limit <= 0) {
            return List.of();
        }
        return visibleProposals(context).stream()
                .filter(proposal -> proposal.getStatus() == ProposalStatus.REJECTED)
                .sorted(Comparator
                        .comparingDouble(RelationProposal::getConfidence)
                        .reversed()
                        .thenComparing(
                                RelationProposal::getId,
                                Comparator.nullsLast(Long::compareTo)))
                .limit(limit)
                .map(proposal -> new TopRejectedProposal(
                        proposal.getSourceNode().getCode(),
                        proposal.getSourceNode().getNameEn(),
                        proposal.getTargetNode().getCode(),
                        proposal.getTargetNode().getNameEn(),
                        proposal.getRelationType().name(),
                        proposal.getConfidence(),
                        proposal.getRationale()))
                .toList();
    }

    /**
     * Returns a feedback weight in {@code [0.0, 1.0]} from review history
     * visible in the exact proposal-generation context.
     *
     * <p>Returns {@code 0.5} when no reviewed history exists. A workspace
     * intentionally inherits central review history from the same repository,
     * but never history from a sibling workspace or another repository.</p>
     */
    @Transactional(readOnly = true)
    public double acceptanceHistoryWeight(
            String sourceRoot,
            String targetRoot,
            RelationType relationType,
            RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        long accepted = proposalRepository.countVisibleReviewHistory(
                tenant.repositoryId(),
                tenant.workspaceId(),
                sourceRoot,
                targetRoot,
                relationType,
                ProposalStatus.ACCEPTED);
        long rejected = proposalRepository.countVisibleReviewHistory(
                tenant.repositoryId(),
                tenant.workspaceId(),
                sourceRoot,
                targetRoot,
                relationType,
                ProposalStatus.REJECTED);
        long reviewed = accepted + rejected;
        return reviewed == 0 ? 0.5 : (double) accepted / reviewed;
    }

    private List<RelationProposal> visibleProposals(
            RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        if (tenant.workspaceId() == null) {
            return proposalRepository.findCentralByRepository(
                    tenant.repositoryId());
        }
        return proposalRepository.findVisibleByRepositoryAndWorkspace(
                tenant.repositoryId(), tenant.workspaceId());
    }

    private static List<RelationTypeMetrics> metricsByRelationType(
            List<RelationProposal> proposals) {
        return proposals.stream()
                .map(RelationProposal::getRelationType)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(relationType -> {
                    long accepted = countByTypeAndStatus(
                            proposals, relationType, ProposalStatus.ACCEPTED);
                    long rejected = countByTypeAndStatus(
                            proposals, relationType, ProposalStatus.REJECTED);
                    long pending = countByTypeAndStatus(
                            proposals, relationType, ProposalStatus.PENDING);
                    long reviewed = accepted + rejected;
                    return new RelationTypeMetrics(
                            relationType.name(),
                            Math.toIntExact(reviewed + pending),
                            Math.toIntExact(accepted),
                            Math.toIntExact(rejected),
                            reviewed > 0 ? (double) accepted / reviewed : 0.0);
                })
                .toList();
    }

    private static List<ProvenanceMetrics> metricsByProvenance(
            List<RelationProposal> proposals) {
        return proposals.stream()
                .map(RelationProposal::getProvenance)
                .distinct()
                .sorted(Comparator.nullsFirst(String::compareTo))
                .map(provenance -> {
                    long accepted = countByProvenanceAndStatus(
                            proposals, provenance, ProposalStatus.ACCEPTED);
                    long rejected = countByProvenanceAndStatus(
                            proposals, provenance, ProposalStatus.REJECTED);
                    long pending = countByProvenanceAndStatus(
                            proposals, provenance, ProposalStatus.PENDING);
                    long reviewed = accepted + rejected;
                    return new ProvenanceMetrics(
                            provenance,
                            Math.toIntExact(reviewed + pending),
                            Math.toIntExact(accepted),
                            reviewed > 0 ? (double) accepted / reviewed : 0.0);
                })
                .toList();
    }

    private static long countByStatus(
            List<RelationProposal> proposals,
            ProposalStatus status) {
        return proposals.stream()
                .filter(proposal -> proposal.getStatus() == status)
                .count();
    }

    private static long countByTypeAndStatus(
            List<RelationProposal> proposals,
            RelationType relationType,
            ProposalStatus status) {
        return proposals.stream()
                .filter(proposal -> proposal.getRelationType() == relationType)
                .filter(proposal -> proposal.getStatus() == status)
                .count();
    }

    private static long countByProvenanceAndStatus(
            List<RelationProposal> proposals,
            String provenance,
            ProposalStatus status) {
        return proposals.stream()
                .filter(proposal -> Objects.equals(
                        proposal.getProvenance(), provenance))
                .filter(proposal -> proposal.getStatus() == status)
                .count();
    }

    private static double averageConfidence(
            List<RelationProposal> proposals,
            ProposalStatus status) {
        return proposals.stream()
                .filter(proposal -> proposal.getStatus() == status)
                .mapToDouble(RelationProposal::getConfidence)
                .average()
                .orElse(0.0);
    }

    private static RepositoryContext requireContext(
            RepositoryContext context) {
        return Objects.requireNonNull(
                context, "RepositoryContext must not be null");
    }
}

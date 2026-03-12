package com.taxonomy.service;

import com.taxonomy.dto.ProvenanceMetrics;
import com.taxonomy.dto.RelationQualityMetrics;
import com.taxonomy.dto.RelationTypeMetrics;
import com.taxonomy.dto.TopRejectedProposal;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.repository.RelationProposalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service for computing quality metrics over {@link com.taxonomy.model.RelationProposal}
 * entities and providing a feedback signal for confidence scoring.
 */
@Service
public class RelationQualityService {

    private final RelationProposalRepository proposalRepository;

    public RelationQualityService(RelationProposalRepository proposalRepository) {
        this.proposalRepository = proposalRepository;
    }

    /**
     * Computes the full quality dashboard metrics.
     */
    @Transactional(readOnly = true)
    public RelationQualityMetrics calculateMetrics() {
        long accepted = proposalRepository.countByStatus(ProposalStatus.ACCEPTED);
        long rejected = proposalRepository.countByStatus(ProposalStatus.REJECTED);
        long pending  = proposalRepository.countByStatus(ProposalStatus.PENDING);
        long total    = accepted + rejected + pending;

        double acceptanceRate = (accepted + rejected) > 0
                ? (double) accepted / (accepted + rejected) : 0.0;

        Double avgAccepted = proposalRepository.avgConfidenceByStatus(ProposalStatus.ACCEPTED);
        Double avgRejected = proposalRepository.avgConfidenceByStatus(ProposalStatus.REJECTED);

        return new RelationQualityMetrics(
                (int) total,
                (int) accepted,
                (int) rejected,
                (int) pending,
                acceptanceRate,
                avgAccepted != null ? avgAccepted : 0.0,
                avgRejected != null ? avgRejected : 0.0,
                metricsByRelationType(),
                metricsByProvenance()
        );
    }

    /**
     * Aggregates metrics broken down by relation type.
     */
    @Transactional(readOnly = true)
    public List<RelationTypeMetrics> metricsByRelationType() {
        return proposalRepository.findDistinctRelationTypes().stream()
                .map(rt -> {
                    long accepted = proposalRepository.countByRelationTypeAndStatus(rt, ProposalStatus.ACCEPTED);
                    long rejected = proposalRepository.countByRelationTypeAndStatus(rt, ProposalStatus.REJECTED);
                    long pending  = proposalRepository.countByRelationTypeAndStatus(rt, ProposalStatus.PENDING);
                    long proposed = accepted + rejected + pending;
                    double rate   = (accepted + rejected) > 0
                            ? (double) accepted / (accepted + rejected) : 0.0;
                    return new RelationTypeMetrics(rt.name(), (int) proposed, (int) accepted, (int) rejected, rate);
                })
                .toList();
    }

    /**
     * Aggregates metrics broken down by provenance string.
     */
    @Transactional(readOnly = true)
    public List<ProvenanceMetrics> metricsByProvenance() {
        return proposalRepository.findDistinctProvenances().stream()
                .map(prov -> {
                    long accepted = proposalRepository.countByProvenanceAndStatus(prov, ProposalStatus.ACCEPTED);
                    long rejected = proposalRepository.countByProvenanceAndStatus(prov, ProposalStatus.REJECTED);
                    long pending  = proposalRepository.countByProvenanceAndStatus(prov, ProposalStatus.PENDING);
                    long proposed = accepted + rejected + pending;
                    double rate   = (accepted + rejected) > 0
                            ? (double) accepted / (accepted + rejected) : 0.0;
                    return new ProvenanceMetrics(prov, (int) proposed, (int) accepted, rate);
                })
                .toList();
    }

    /**
     * Returns the top rejected proposals ordered by confidence descending (worst false positives first).
     */
    @Transactional(readOnly = true)
    public List<TopRejectedProposal> topRejected(int limit) {
        return proposalRepository.findByStatusOrderByConfidenceDesc(ProposalStatus.REJECTED)
                .stream()
                .limit(limit)
                .map(p -> new TopRejectedProposal(
                        p.getSourceNode().getCode(),
                        p.getSourceNode().getNameEn(),
                        p.getTargetNode().getCode(),
                        p.getTargetNode().getNameEn(),
                        p.getRelationType().name(),
                        p.getConfidence(),
                        p.getRationale()
                ))
                .toList();
    }

    /**
     * Returns a feedback weight [0.0, 1.0] derived from the acceptance history
     * for the given source root, target root, and relation type.
     *
     * <p>Returns 0.5 (neutral) when no history exists.
     */
    public double acceptanceHistoryWeight(String sourceRoot, String targetRoot, RelationType relationType) {
        long accepted = proposalRepository
                .countBySourceNodeTaxonomyRootAndTargetNodeTaxonomyRootAndRelationTypeAndStatus(
                        sourceRoot, targetRoot, relationType, ProposalStatus.ACCEPTED);
        long rejected = proposalRepository
                .countBySourceNodeTaxonomyRootAndTargetNodeTaxonomyRootAndRelationTypeAndStatus(
                        sourceRoot, targetRoot, relationType, ProposalStatus.REJECTED);

        if ((accepted + rejected) == 0) {
            return 0.5;
        }
        return (double) accepted / (accepted + rejected);
    }
}

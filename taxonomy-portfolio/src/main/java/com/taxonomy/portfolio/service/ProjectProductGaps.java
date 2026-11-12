package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisCoverage;
import com.taxonomy.dto.ProductCoverageGap;
import com.taxonomy.portfolio.repository.ProductGapSnapshotRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Collects confirmed product gaps from the selected project's retained analyses. */
public final class ProjectProductGaps {
    public record Candidate(String code, Double assessedScore, double recordedScore, String reason) { }
    public record Observation(String snapshotId, Long requirementId, String requirementKey, Long requirementVersionId,
                              int requirementVersion, String catalogueFingerprint, Integer minimumSuitabilityScore,
                              double familyScore, String reason, List<Candidate> candidates) { }
    public record Family(String familyCode, String familyName, String catalogueFingerprint, List<Observation> observations) {
        public String reviewKey() {
            String source = catalogueFingerprint == null || catalogueFingerprint.isBlank()
                    ? "snapshot:" + observations.getFirst().snapshotId() : "catalogue:" + catalogueFingerprint;
            return com.taxonomy.identity.StableIdentityHash.sha256(familyCode.length() + ":" + familyCode + source);
        }

        public String evidenceFingerprint() {
            StringBuilder identity = new StringBuilder(reviewKey());
            observations.stream().map(Observation::snapshotId).sorted()
                    .forEach(id -> identity.append(id.length()).append(':').append(id));
            return com.taxonomy.identity.StableIdentityHash.sha256(identity.toString());
        }
    }
    private record Key(String family, String catalogue, String unpinnedSnapshot) { }

    /** Read only the evidence fields from a saved snapshot; old payloads used scores as their score map. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Evidence(List<ProductCoverageGap> productCoverageGaps, Map<String, Double> rawScores,
                           Map<String, Double> scores, Map<String, String> reasons, AnalysisCoverage analysisCoverage) { }

    private record Group(String name, Map<String, Observation> bySnapshot) { }
    private final Map<Key, Group> groups = new LinkedHashMap<>();

    public void include(ProductGapSnapshotRow source, Evidence evidence) {
        if (evidence == null || evidence.productCoverageGaps() == null) return;
        var scores = evidence.rawScores() == null ? evidence.scores() : evidence.rawScores();
        if (scores == null) return;
        for (var gap : evidence.productCoverageGaps()) {
            if (gap == null || gap.productFamilyCode() == null || gap.productFamilyCode().isBlank()
                    || gap.reason() == null || gap.reason().isBlank()
                    || gap.candidateCodes().isEmpty() || gap.familyScore() <= 0
                    || !Objects.equals(scores.get(gap.productFamilyCode()), gap.familyScore())) continue;
            var candidates = new ArrayList<Candidate>();
            for (var code : gap.candidateCodes().stream().distinct().sorted().toList()) {
                if (!Objects.equals(scores.get(code), 0d)) break;
                if (evidence.analysisCoverage() != null) {
                    var assessment = evidence.analysisCoverage().nodes().get(code);
                    if (assessment == null || assessment.state() != AnalysisCoverage.State.NOT_RELEVANT) break;
                }
                candidates.add(new Candidate(code, gap.assessedProductScores().get(code), scores.get(code),
                        evidence.reasons() == null ? null : evidence.reasons().get(code)));
            }
            if (candidates.size() != gap.candidateCodes().size()) continue;
            String fingerprint = source.taxonomyFingerprint();
            var key = new Key(gap.productFamilyCode(), fingerprint,
                    fingerprint == null || fingerprint.isBlank() ? source.id() : null);
            var group = groups.computeIfAbsent(key, ignored -> new Group(gap.productFamilyName(), new LinkedHashMap<>()));
            group.bySnapshot().putIfAbsent(source.id(), new Observation(source.id(), source.requirementId(), source.requirementKey(),
                    source.requirementVersionId(), source.requirementVersionNumber(), fingerprint,
                    gap.minimumSuitabilityScore(), gap.familyScore(), gap.reason(), List.copyOf(candidates)));
        }
    }

    public List<Family> families() {
        return groups.entrySet().stream().map(entry -> new Family(entry.getKey().family(), entry.getValue().name(),
                        entry.getKey().catalogue(), List.copyOf(entry.getValue().bySnapshot().values())))
                .sorted(Comparator.comparing(Family::familyCode)
                        .thenComparing(Family::catalogueFingerprint, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}

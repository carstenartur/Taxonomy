package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.dto.SnapshotSelection;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Selects the strongest immutable snapshot without mutating or discarding any pass. */
@Component
public class CopilotResultSelector {

    public Optional<SnapshotDetail> select(List<SnapshotDetail> candidates) {
        if (candidates == null) return Optional.empty();
        return candidates.stream()
                .filter(this::usable)
                .max(Comparator.comparing(this::quality));
    }

    public Optional<SnapshotSelection> selectForOperation(List<SnapshotSelection> candidates) {
        if (candidates == null) return Optional.empty();
        return candidates.stream()
                .filter(candidate -> candidate != null && usable(candidate.status(), candidate.scores()))
                .max(Comparator.comparing(candidate -> quality(candidate.status(), candidate.durationMs(),
                        candidate.scores(), candidate.elementMappingCount(), candidate.relationMappingCount(),
                        candidate.warningCount())));
    }

    private boolean usable(SnapshotDetail detail) {
        return detail != null
                && detail.summary() != null
                && detail.analysis() != null
                && usable(detail.summary().status(), detail.analysis().getScores());
    }

    private boolean usable(AnalysisStatus status, Map<String, Double> scores) {
        return scores != null && !scores.isEmpty()
                && (status == AnalysisStatus.SUCCESS || status == AnalysisStatus.PARTIAL);
    }

    private Quality quality(SnapshotDetail detail) {
        AnalysisResult analysis = detail.analysis();
        return quality(detail.summary().status(), detail.summary().durationMs(), analysis.getScores(),
                detail.elementMappings() == null ? 0 : detail.elementMappings().size(),
                detail.relationMappings() == null ? 0 : detail.relationMappings().size(),
                analysis.getWarnings() == null ? 0 : analysis.getWarnings().size());
    }

    private Quality quality(AnalysisStatus status, long durationMs, Map<String, Double> scores,
                            long mappedElements, long mappedRelations, int warnings) {
        int positive = (int) scores.values().stream()
                .filter(value -> value != null && value > 0)
                .count();
        double scoreSum = scores.values().stream()
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        return new Quality(
                status == AnalysisStatus.SUCCESS ? 2 : 1,
                positive,
                scoreSum,
                mappedElements,
                mappedRelations,
                -warnings,
                -Math.max(0L, durationMs));
    }

    private record Quality(
            int status,
            int positiveScores,
            double scoreSum,
            long mappedElements,
            long mappedRelations,
            int warningPreference,
            long durationPreference) implements Comparable<Quality> {

        @Override
        public int compareTo(Quality other) {
            int result = Integer.compare(status, other.status);
            if (result != 0) return result;
            result = Integer.compare(positiveScores, other.positiveScores);
            if (result != 0) return result;
            result = Double.compare(scoreSum, other.scoreSum);
            if (result != 0) return result;
            result = Long.compare(mappedElements, other.mappedElements);
            if (result != 0) return result;
            result = Long.compare(mappedRelations, other.mappedRelations);
            if (result != 0) return result;
            result = Integer.compare(warningPreference, other.warningPreference);
            if (result != 0) return result;
            return Long.compare(durationPreference, other.durationPreference);
        }
    }
}

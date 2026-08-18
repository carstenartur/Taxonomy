package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
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

    private boolean usable(SnapshotDetail detail) {
        return detail != null
                && detail.summary() != null
                && detail.analysis() != null
                && detail.analysis().getScores() != null
                && !detail.analysis().getScores().isEmpty()
                && (detail.summary().status() == AnalysisStatus.SUCCESS
                    || detail.summary().status() == AnalysisStatus.PARTIAL);
    }

    private Quality quality(SnapshotDetail detail) {
        AnalysisResult analysis = detail.analysis();
        Map<String, Integer> scores = analysis.getScores();
        int positive = (int) scores.values().stream()
                .filter(value -> value != null && value > 0)
                .count();
        int scoreSum = scores.values().stream()
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        int mappedElements = detail.elementMappings() != null
                ? detail.elementMappings().size() : 0;
        int mappedRelations = detail.relationMappings() != null
                ? detail.relationMappings().size() : 0;
        int warnings = analysis.getWarnings() != null
                ? analysis.getWarnings().size() : 0;
        return new Quality(
                detail.summary().status() == AnalysisStatus.SUCCESS ? 2 : 1,
                positive,
                scoreSum,
                mappedElements,
                mappedRelations,
                -warnings,
                -Math.max(0L, detail.summary().durationMs()));
    }

    private record Quality(
            int status,
            int positiveScores,
            int scoreSum,
            int mappedElements,
            int mappedRelations,
            int warningPreference,
            long durationPreference) implements Comparable<Quality> {

        @Override
        public int compareTo(Quality other) {
            int result = Integer.compare(status, other.status);
            if (result != 0) return result;
            result = Integer.compare(positiveScores, other.positiveScores);
            if (result != 0) return result;
            result = Integer.compare(scoreSum, other.scoreSum);
            if (result != 0) return result;
            result = Integer.compare(mappedElements, other.mappedElements);
            if (result != 0) return result;
            result = Integer.compare(mappedRelations, other.mappedRelations);
            if (result != 0) return result;
            result = Integer.compare(warningPreference, other.warningPreference);
            if (result != 0) return result;
            return Long.compare(durationPreference, other.durationPreference);
        }
    }
}

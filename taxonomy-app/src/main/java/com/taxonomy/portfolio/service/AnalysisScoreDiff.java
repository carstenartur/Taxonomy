package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AnalysisScoreDetail;
import com.taxonomy.dto.AnalysisScoreKind;
import com.taxonomy.portfolio.dto.PortfolioDtos.ScoreChange;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Builds a deterministic snapshot diff over effective values and their complete score meaning. */
final class AnalysisScoreDiff {

    private AnalysisScoreDiff() {
    }

    static Map<String, ScoreChange> between(
            AnalysisResult older,
            AnalysisResult newer) {
        Map<String, Integer> oldEffective = safeScores(older == null ? null : older.getScores());
        Map<String, Integer> newEffective = safeScores(newer == null ? null : newer.getScores());
        Map<String, Integer> oldRaw = safeScores(older == null ? null : older.getRawScores());
        Map<String, Integer> newRaw = safeScores(newer == null ? null : newer.getRawScores());
        Map<String, AnalysisScoreDetail> oldDetails =
                safeDetails(older == null ? null : older.getScoreDetails());
        Map<String, AnalysisScoreDetail> newDetails =
                safeDetails(newer == null ? null : newer.getScoreDetails());

        Set<String> codes = new TreeSet<>();
        codes.addAll(oldEffective.keySet());
        codes.addAll(newEffective.keySet());
        codes.addAll(oldRaw.keySet());
        codes.addAll(newRaw.keySet());
        codes.addAll(oldDetails.keySet());
        codes.addAll(newDetails.keySet());

        Map<String, ScoreChange> changes = new LinkedHashMap<>();
        for (String code : codes) {
            AnalysisScoreDetail oldDetail = oldDetails.get(code);
            AnalysisScoreDetail newDetail = newDetails.get(code);
            Integer oldEffectiveValue = oldEffective.get(code);
            Integer newEffectiveValue = newEffective.get(code);
            Integer oldRawValue = rawValue(oldRaw.get(code), oldDetail);
            Integer newRawValue = rawValue(newRaw.get(code), newDetail);
            AnalysisScoreKind oldKind = oldDetail == null ? null : oldDetail.kind();
            AnalysisScoreKind newKind = newDetail == null ? null : newDetail.kind();
            String oldParentCode = oldDetail == null ? null : oldDetail.parentCode();
            String newParentCode = newDetail == null ? null : newDetail.parentCode();
            Integer oldParentScore = oldDetail == null ? null : oldDetail.parentScore();
            Integer newParentScore = newDetail == null ? null : newDetail.parentScore();

            if (Objects.equals(oldEffectiveValue, newEffectiveValue)
                    && Objects.equals(oldRawValue, newRawValue)
                    && oldKind == newKind
                    && Objects.equals(oldParentCode, newParentCode)
                    && Objects.equals(oldParentScore, newParentScore)) {
                continue;
            }
            changes.put(code, new ScoreChange(
                    oldEffectiveValue,
                    newEffectiveValue,
                    oldRawValue,
                    newRawValue,
                    oldKind,
                    newKind,
                    oldParentCode,
                    newParentCode,
                    oldParentScore,
                    newParentScore));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(changes));
    }

    private static Integer rawValue(
            Integer rawScore,
            AnalysisScoreDetail detail) {
        if (detail != null) {
            return detail.rawScore();
        }
        return rawScore;
    }

    private static Map<String, Integer> safeScores(Map<String, Integer> scores) {
        return scores == null ? Map.of() : scores;
    }

    private static Map<String, AnalysisScoreDetail> safeDetails(
            Map<String, AnalysisScoreDetail> details) {
        return details == null ? Map.of() : details;
    }
}

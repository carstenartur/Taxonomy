package com.taxonomy.service;

import com.taxonomy.dto.TaxonomyNodeDto;

import java.util.*;

/**
 * Reciprocal Rank Fusion (RRF) utility for combining multiple ranked result lists.
 *
 * <p>RRF merges ranked lists from different retrieval systems (e.g., full-text Lucene and
 * semantic KNN) without requiring score calibration.  Each document receives a score of
 * {@code 1 / (k + rank)} from each list in which it appears, where {@code k} is a smoothing
 * constant (typically 60).  The final score is the sum across all lists.
 *
 * <p>This mirrors the {@code reciprocalRankFusion()} helper described in the
 * {@code GitDatabaseQueryService.hybridSearch()} plan from the sandbox project
 * (sandbox-jgit-storage-hibernate).
 *
 * @see <a href="https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf">
 *     Cormack et al., "Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning
 *     Methods", SIGIR 2009</a>
 */
public final class RankFusionUtil {

    /** RRF smoothing constant. 60 is the standard value from the original paper. */
    static final int K = 60;

    private RankFusionUtil() {}

    /**
     * Merges two ranked lists of taxonomy node DTOs using Reciprocal Rank Fusion.
     *
     * @param list1   first ranked list (e.g. semantic KNN results)
     * @param list2   second ranked list (e.g. full-text Lucene results)
     * @param topK    maximum number of results to return
     * @return merged and re-ranked list with at most {@code topK} entries
     */
    public static List<TaxonomyNodeDto> fuse(List<TaxonomyNodeDto> list1,
                                              List<TaxonomyNodeDto> list2,
                                              int topK) {
        Map<String, Double>         scoreMap  = new LinkedHashMap<>();
        Map<String, TaxonomyNodeDto> dtoMap   = new LinkedHashMap<>();

        addRankScores(list1, scoreMap, dtoMap);
        addRankScores(list2, scoreMap, dtoMap);

        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> dtoMap.get(e.getKey()))
                .toList();
    }

    private static void addRankScores(List<TaxonomyNodeDto> list,
                                      Map<String, Double> scoreMap,
                                      Map<String, TaxonomyNodeDto> dtoMap) {
        for (int i = 0; i < list.size(); i++) {
            TaxonomyNodeDto dto = list.get(i);
            String code = dto.getCode();
            double rrfScore = 1.0 / (K + i + 1);
            scoreMap.merge(code, rrfScore, Double::sum);
            dtoMap.putIfAbsent(code, dto);
        }
    }
}

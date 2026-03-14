package com.taxonomy.dto;

import java.util.List;
import java.util.Map;

/**
 * Result of an APQC process coverage analysis.
 *
 * <p>Reports which APQC process categories are covered by the current
 * architecture model, and which have gaps.
 */
public record ApqcCoverageResult(
        int totalCategories,
        int coveredCategories,
        double coveragePercent,
        List<String> uncoveredCategories,
        Map<String, Integer> coverageByLevel
) {}

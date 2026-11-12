package com.taxonomy.reporting.api.document;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionReportScope;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport.*;
import com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.*;

/** Complete, ordered navigation evidence, independent of the leading decision path. */
public record DecisionTreeOverview(List<DecisionTreeRow> rows, List<String> warnings) {
    public DecisionTreeOverview {
        rows = List.copyOf(rows);
        warnings = List.copyOf(warnings);
    }

    public record DecisionTreeRow(
            int depth,
            String code,
            String title,
            Double score,
            Disposition disposition,
            Integer chapterNumber,
            String bookmark,
            @JsonInclude(JsonInclude.Include.NON_NULL) Assignment responsibility) {
        public DecisionTreeRow(int depth, String code, String title, Double score, Disposition disposition,
                Integer chapterNumber, String bookmark) {
            this(depth, code, title, score, disposition, chapterNumber, bookmark, null);
        }
    }

    public DecisionTreeOverview withResponsibilities(Map<String, Assignment> assignments) {
        if (assignments.isEmpty()) return this;
        return new DecisionTreeOverview(rows.stream().map(row -> new DecisionTreeRow(row.depth(), row.code(),
                row.title(), row.score(), row.disposition(), row.chapterNumber(), row.bookmark(), assignments.get(row.code())))
                .toList(), warnings);
    }

    public Map<String, Assignment> responsibilitiesByCode() {
        var assignments = new LinkedHashMap<String, Assignment>();
        for (var row : rows) if (row.responsibility() != null) assignments.put(row.code(), row.responsibility());
        return assignments;
    }

    public static String bookmark(DecisionChapter chapter) {
        String code = chapter.parentCode().replaceAll("[^A-Za-z0-9_]", "_");
        // Chapter numbers are unique; truncation cannot collide even for non-Latin codes.
        return "decision_chapter_"
                + chapter.number()
                + "_"
                + code.substring(0, Math.min(12, code.length()));
    }

    /** Compatibility factory; new producers can supply the frozen rows directly. */
    public static DecisionTreeOverview from(List<DecisionChapter> chapters) {
        return com.taxonomy.reporting.api.decision.DecisionReportScope.legacy(chapters).decisionTree();
    }
}

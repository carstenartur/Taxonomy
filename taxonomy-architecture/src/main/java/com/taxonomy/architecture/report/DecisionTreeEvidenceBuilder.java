package com.taxonomy.architecture.report;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport.*;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;
import com.taxonomy.reporting.api.document.DecisionTreeOverview.DecisionTreeRow;
import com.taxonomy.dto.TaxonomyNodeDto;
import java.util.*;
import static com.taxonomy.reporting.api.document.DecisionTreeOverview.bookmark;

/** Derives navigation evidence from the captured taxonomy, before any renderer sees it. */
public final class DecisionTreeEvidenceBuilder {
    private DecisionTreeEvidenceBuilder() {}

    /** Include rejected roots and missing alternatives even when they do not create a positive chapter. */
    public static DecisionTreeOverview fromEvidence(List<TaxonomyNodeDto> tree, Map<String, Integer> scores,
            List<DecisionChapter> chapters, Set<String> roots, boolean german) {
        var chapterByCode = new HashMap<String, DecisionChapter>();
        chapters.forEach(chapter -> chapterByCode.put(chapter.parentCode(), chapter));
        var included = new HashSet<String>();
        for (var root : tree) if (roots.contains(root.getCode())) includeEvidence(root, scores, included, true);
        var rows = new ArrayList<DecisionTreeRow>();
        for (var root : tree) if (roots.contains(root.getCode()))
            appendEvidence(root, 0, included, scores, chapterByCode, rows, german);
        return new DecisionTreeOverview(rows, List.of());
    }

    private static boolean includeEvidence(TaxonomyNodeDto node, Map<String, Integer> scores,
            Set<String> included, boolean required) {
        boolean keep = required || scores.containsKey(node.getCode());
        for (var child : node.getChildren() == null ? List.<TaxonomyNodeDto>of() : node.getChildren())
            keep |= includeEvidence(child, scores, included, scores.getOrDefault(node.getCode(), 0) > 0);
        if (keep) included.add(node.getCode());
        return keep;
    }

    private static void appendEvidence(TaxonomyNodeDto node, int depth, Set<String> included,
            Map<String, Integer> scores, Map<String, DecisionChapter> chapters, List<DecisionTreeRow> rows,
            boolean german) {
        if (!included.contains(node.getCode())) return;
        var children = node.getChildren() == null ? List.<TaxonomyNodeDto>of() : node.getChildren();
        var chapter = chapters.get(node.getCode());
        String title = german ? node.getNameDe() : node.getNameEn();
        if (title == null || title.isBlank()) title = node.getNameEn();
        if (title == null || title.isBlank()) title = node.getCode();
        rows.add(new DecisionTreeRow(depth, node.getCode(), title, scores.get(node.getCode()),
                disposition(scores.get(node.getCode()), children.isEmpty()),
                chapter == null ? null : chapter.number(), chapter == null ? null : bookmark(chapter)));
        for (var child : children) appendEvidence(child, depth + 1, included, scores, chapters, rows, german);
    }

    private static Disposition disposition(Integer score, boolean leaf) {
        return score == null ? Disposition.NOT_EVALUATED : score == 0 ? Disposition.REJECTED
                : leaf ? Disposition.LEAF_CANDIDATE : Disposition.CONTINUED;
    }
}

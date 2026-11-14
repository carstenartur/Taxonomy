package com.taxonomy.reporting.render.document;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;

import static org.assertj.core.api.Assertions.*;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class DecisionTreeOverviewTest {
    static ChildDecision child(String id, Double score) {
        return new ChildDecision(
                id,
                id,
                "",
                score,
                null,
                null,
                false,
                score == null
                        ? Disposition.NOT_EVALUATED
                        : score == 0 ? Disposition.REJECTED : Disposition.CONTINUED,
                "Same prose",
                ReasonSource.AI_SCORING,
                false);
    }

    static DecisionChapter chapter(int n, String id, int depth, ChildDecision... children) {
        return new DecisionChapter(
                n,
                id,
                id,
                "",
                100d,
                depth,
                true,
                "Saved summary",
                "Saved rationale",
                List.of(children),
                List.of());
    }

    @Test
    void responsibilityAppearsInGraphicsAndAltTextWithoutChangingScores() {
        var tree = DecisionTreeOverview.from(List.of(chapter(1, "A", 0, child("B", 80d))));
        var owner = new com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment("unit-1", "Network & Ops", "A");
        var annotated = tree.withResponsibilities(Map.of("A", owner, "B", owner));
        var renderer = new DecisionTreeFigureRenderer();
        var layout = com.taxonomy.reporting.api.decision.DecisionReportOptions.TreeLayout.AUTO;
        var figure = renderer.render(annotated, "en", layout).getFirst();
        assertThat(figure.svg()).contains(owner.color(), "Network &amp; Ops", "relevance-bar");
        assertThat(figure.description()).contains("Network & Ops");
        assertThat(renderer.render(tree, "en", layout).getFirst().svg()).doesNotContain("responsibility-marker");
    }

    @Test
    void responsibilityIsReadableOnEachLandscapeContinuationAndInTheWordTable() throws Exception {
        var rows = new ArrayList<DecisionTreeOverview.DecisionTreeRow>();
        var owner = new com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment("unit-1", "Betrieb", "CP");
        for (int i = 0; i < 80; i++) rows.add(new DecisionTreeOverview.DecisionTreeRow(
                i == 0 ? 0 : 1, i == 0 ? "CP" : "CP-" + i, "Knoten", 80d, Disposition.CONTINUED, null, null, owner));
        var tree = new DecisionTreeOverview(rows, List.of());
        var panels = new DecisionTreeFigureRenderer().render(tree, "de",
                com.taxonomy.reporting.api.decision.DecisionReportOptions.TreeLayout.AUTO);
        assertThat(panels).hasSizeGreaterThan(1);
        assertThat(panels).allSatisfy(p -> assertThat(p.svg().substring(p.svg().indexOf("</title>"))).containsOnlyOnce("Zuständig: Betrieb"));
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            new DecisionTreeWordSectionRenderer().write(document, tree, "de");
            var xml = document.getDocument().xmlText();
            assertThat(xml).contains("Zuständig: Betrieb", owner.color().substring(1), "80.0%");
        }
    }

    @Test
    void retainsAlternativesMissingAndZeroWithOneLinkPerChapter() {
        var tree =
                DecisionTreeOverview.from(
                        List.of(
                                chapter(
                                        1,
                                        "A",
                                        0,
                                        child("B", 100d),
                                        child("Z", 0d),
                                        child("M", null)),
                                chapter(2, "B", 1, child("C", 100d)),
                                chapter(3, "ORPHAN", 3)));
        assertThat(tree.rows())
                .extracting(DecisionTreeOverview.DecisionTreeRow::code)
                .containsExactly("A", "B", "C", "Z", "M", "ORPHAN");
        assertThat(
                        tree.rows().stream()
                                .filter(r -> r.code().equals("Z"))
                                .findFirst()
                                .orElseThrow()
                                .score())
                .isZero();
        assertThat(
                        tree.rows().stream()
                                .filter(r -> r.code().equals("M"))
                                .findFirst()
                                .orElseThrow()
                                .score())
                .isNull();
        assertThat(tree.rows().stream().filter(r -> r.bookmark() != null).toList()).hasSize(3);
        assertThat(tree.warnings()).isNotEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(doubles = {0d, 100d})
    void rejectsLeafParentDisagreementRegardlessOfScoreAndChapterOrder(Double score) {
        var leaf = new ChildDecision("B", "B", "", score, null, null, false,
                score == null ? Disposition.NOT_EVALUATED : score == 0 ? Disposition.REJECTED
                        : Disposition.LEAF_CANDIDATE, "Saved reason", ReasonSource.AI_SCORING, true);
        var parent = new DecisionChapter(2, "B", "B", "", score, 1, true, "", "",
                List.of(child("C", 100d)), List.of());
        var root = chapter(1, "A", 0, leaf);
        for (var chapters : List.of(List.of(root, parent), List.of(parent, root))) {
            assertThatThrownBy(() -> DecisionTreeOverview.from(chapters))
                    .hasMessageContaining("Contradictory decision node B");
        }
        var validLeaf = DecisionTreeOverview.from(List.of(root)).rows().getLast();
        assertThat(validLeaf.score()).isEqualTo(score);
        assertThat(validLeaf.disposition()).isEqualTo(leaf.disposition());
    }

    @Test
    void deepHierarchyRetainsEveryDepthAndUniqueChapterLink() {
        var chapters = new ArrayList<DecisionChapter>();
        for (int i = 0; i < 128; i++) {
            chapters.add(
                    chapter(
                            i + 1,
                            "node-" + i,
                            i,
                            i == 127
                                    ? new ChildDecision[0]
                                    : new ChildDecision[] {child("node-" + (i + 1), 100d)}));
        }
        var tree = DecisionTreeOverview.from(chapters);
        assertThat(tree.rows()).hasSize(128);
        assertThat(tree.rows().getLast().depth()).isEqualTo(127);
        assertThat(
                        tree.rows().stream()
                                .map(DecisionTreeOverview.DecisionTreeRow::bookmark)
                                .distinct())
                .hasSize(128);
        assertThat(tree.warnings()).isEmpty();
    }

    @Test
    void rejectsCyclesAndContradictoryParents() {
        assertThatThrownBy(
                        () ->
                                DecisionTreeOverview.from(
                                        List.of(
                                                chapter(1, "A", 0, child("B", 100d)),
                                                chapter(2, "B", 1, child("A", 100d)))))
                .hasMessageContaining("cycle");
        assertThatThrownBy(
                        () ->
                                DecisionTreeOverview.from(
                                        List.of(
                                                chapter(1, "A", 0, child("X", 100d)),
                                                chapter(2, "B", 0, child("X", 100d)))))
                .hasMessageContaining("parent");
    }
}

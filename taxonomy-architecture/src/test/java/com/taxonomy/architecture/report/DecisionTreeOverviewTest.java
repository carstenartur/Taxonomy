package com.taxonomy.architecture.report;

import static org.assertj.core.api.Assertions.*;

import com.taxonomy.architecture.decision.DecisionRationaleReport.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class DecisionTreeOverviewTest {
    static ChildDecision child(String id, Integer score) {
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
                100,
                depth,
                true,
                "Saved summary",
                "Saved rationale",
                List.of(children),
                List.of());
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
                                        child("B", 100),
                                        child("Z", 0),
                                        child("M", null)),
                                chapter(2, "B", 1, child("C", 100)),
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
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 100})
    void rejectsLeafParentDisagreementRegardlessOfScoreAndChapterOrder(Integer score) {
        var leaf = new ChildDecision("B", "B", "", score, null, null, false,
                score == null ? Disposition.NOT_EVALUATED : score == 0 ? Disposition.REJECTED
                        : Disposition.LEAF_CANDIDATE, "Saved reason", ReasonSource.AI_SCORING, true);
        var parent = new DecisionChapter(2, "B", "B", "", score, 1, true, "", "",
                List.of(child("C", 100)), List.of());
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
                                    : new ChildDecision[] {child("node-" + (i + 1), 100)}));
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
                                                chapter(1, "A", 0, child("B", 100)),
                                                chapter(2, "B", 1, child("A", 100)))))
                .hasMessageContaining("cycle");
        assertThatThrownBy(
                        () ->
                                DecisionTreeOverview.from(
                                        List.of(
                                                chapter(1, "A", 0, child("X", 100)),
                                                chapter(2, "B", 0, child("X", 100)))))
                .hasMessageContaining("parent");
    }
}

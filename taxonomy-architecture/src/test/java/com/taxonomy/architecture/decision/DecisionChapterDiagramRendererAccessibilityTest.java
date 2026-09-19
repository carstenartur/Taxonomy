package com.taxonomy.architecture.decision;

import com.taxonomy.architecture.decision.DecisionChapterDiagramRenderer.DiagramPanel;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ChildDecision;
import com.taxonomy.architecture.decision.DecisionRationaleReport.DecisionChapter;
import com.taxonomy.architecture.decision.DecisionRationaleReport.Disposition;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ReasonSource;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionChapterDiagramRendererAccessibilityTest {

    private static final Pattern ID_PATTERN = Pattern.compile("\\bid=\"([^\"]+)\"");

    @Test
    void svgAccessibilityIdsRemainUniqueAcrossChaptersAndPanels() {
        DecisionChapterDiagramRenderer renderer =
                new DecisionChapterDiagramRenderer();
        Set<String> documentIds = new HashSet<>();

        int panelCount = assertUniqueIds(
                renderer.render(chapter(1, "P", 13), "en"),
                1,
                documentIds);
        panelCount += assertUniqueIds(
                renderer.render(chapter(2, "Q", 2), "en"),
                2,
                documentIds);

        assertThat(documentIds).hasSize(panelCount * 2);
    }

    private int assertUniqueIds(
            List<DiagramPanel> panels,
            int chapterNumber,
            Set<String> documentIds) {
        for (DiagramPanel panel : panels) {
            String prefix = "decision-chapter-" + chapterNumber
                    + "-panel-" + panel.panelNumber();
            assertThat(panel.svg())
                    .contains("aria-labelledby=\"" + prefix
                            + "-title " + prefix + "-desc\"")
                    .contains("<title id=\"" + prefix + "-title\">")
                    .contains("<desc id=\"" + prefix + "-desc\">")
                    .doesNotContain("id=\"title\"", "id=\"desc\"");

            Matcher matcher = ID_PATTERN.matcher(panel.svg());
            while (matcher.find()) {
                assertThat(documentIds.add(matcher.group(1)))
                        .as("duplicate SVG ID %s", matcher.group(1))
                        .isTrue();
            }
        }
        return panels.size();
    }

    private DecisionChapter chapter(
            int number,
            String parentCode,
            int childCount) {
        List<ChildDecision> children = IntStream.range(0, childCount)
                .mapToObj(index -> new ChildDecision(
                        parentCode + "-" + index,
                        "Child " + index,
                        "",
                        100 - index,
                        100.0 - index,
                        index + 1,
                        index == 0,
                        Disposition.LEAF_CANDIDATE,
                        "reason",
                        ReasonSource.AI_SCORING,
                        true))
                .toList();
        return new DecisionChapter(
                number,
                parentCode,
                "Parent " + parentCode,
                "",
                100,
                0,
                true,
                "decision",
                "comparison",
                children,
                List.of());
    }
}

package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Keys;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The production version-review UI, executed by JUnit/Failsafe in a real browser. */
@Tag("browser")
class RequirementVersionReviewIT {
    private static PortfolioClientTestPage page;

    @BeforeAll static void openBrowser(@TempDir Path downloads) throws Exception {
        page = new PortfolioClientTestPage(downloads);
        page.driver().manage().window().setSize(new Dimension(1440, 1000));
    }

    @AfterAll static void closeBrowser() { if (page != null) page.close(); }

    private record Row(String kind, String text) { }

    private List<Row> compare(String older, String newer, String locale) {
        var fixture = new PortfolioRequirementFixture(page);
        fixture.setVersions(older, newer);
        fixture.open(locale);
        fixture.click("versions-tab");
        fixture.click(By.cssSelector("#versionDetail details > summary"));
        List<Row> rows = rows();
        assertThat(rows.stream().filter(row -> !row.kind().equals("added")).map(Row::text).toList())
                .as("Removing additions reconstructs every original line in order")
                .containsExactlyElementsOf(Arrays.asList(older.split("\\r?\\n", -1)));
        assertThat(rows.stream().filter(row -> !row.kind().equals("removed")).map(Row::text).toList())
                .as("Removing deletions reconstructs every revised line in order")
                .containsExactlyElementsOf(Arrays.asList(newer.split("\\r?\\n", -1)));
        return rows;
    }

    @SuppressWarnings("unchecked")
    private List<Row> rows() {
        var values = (List<Map<String, Object>>) page.driver().executeScript("""
                return Array.from(document.querySelectorAll(
                    '#versionDetail .portfolio-requirement-text > div'), node => ({
                    css: node.className, text: node.textContent, children: node.children.length
                }));
                """);
        assertThat(values).as("The real DOM contains the line comparison").isNotEmpty();
        var rows = new ArrayList<Row>();
        for (var value : values) {
            assertThat(((Number) value.get("children")).intValue())
                    .as("Requirement text must remain text, including HTML-like literals").isZero();
            String css = String.valueOf(value.get("css"));
            assertThat(css).isIn("text-danger-emphasis", "text-success-emphasis", "text-body-secondary");
            String kind = css.equals("text-danger-emphasis") ? "removed"
                    : css.equals("text-success-emphasis") ? "added" : "unchanged";
            String marker = kind.equals("removed") ? "− " : kind.equals("added") ? "+ " : "  ";
            String text = String.valueOf(value.get("text"));
            assertThat(text).startsWith(marker);
            rows.add(new Row(kind, text.substring(2)));
        }
        return rows;
    }

    @Test void movingOrderedStepsIsVisible() {
        var rows = compare("Stop the pump\nClose the valve", "Close the valve\nStop the pump", "en");
        assertThat(rows).anyMatch(row -> row.kind().equals("removed"))
                .anyMatch(row -> row.kind().equals("added"));
    }

    @Test void removingRepeatedApprovalCheckIsVisible() {
        var rows = compare("Verify approval\nRelease package\nVerify approval",
                "Verify approval\nRelease package", "en");
        assertThat(rows.stream().filter(row -> row.kind().equals("removed")).toList())
                .containsExactly(new Row("removed", "Verify approval"));
    }

    @Test void addingAnIdenticalRepeatedLineIsVisible() {
        var rows = compare("Check\nRelease", "Check\nCheck\nRelease", "en");
        assertThat(rows.stream().filter(row -> row.kind().equals("added")).toList())
                .containsExactly(new Row("added", "Check"));
    }

    @Test void separateEditsPreserveTheContextBetweenThem() {
        var rows = compare("Heading\nOld first\nStable context\nOld last\nEnd",
                "Heading\nNew first\nStable context\nNew last\nEnd", "en");
        assertThat(rows.stream().filter(row -> row.kind().equals("unchanged")).map(Row::text).toList())
                .containsExactly("Heading", "Stable context", "End");
    }

    @Test void indentationEmptyLinesAndFinalNewlineRemainReviewable() {
        compare("  First\n\n\n  Last\n", "    First\n\n  Last\n", "en");
        assertThat(page.driver().findElement(By.cssSelector("#versionDetail .portfolio-requirement-text"))
                .getCssValue("white-space")).isIn("pre-wrap", "break-spaces");
    }

    @Test void equalLinesAndEmptyTextsHaveNoInventedChanges() {
        for (String value : List.of("", "Same\nSame\n\nSame")) {
            assertThat(compare(value, value, "en")).allMatch(row -> row.kind().equals("unchanged"));
        }
    }

    @Test void lineEndingNormalizationKeepsEquivalentLinesUnchanged() {
        assertThat(compare("First\r\nSecond\r\n", "First\nSecond\n", "en"))
                .allMatch(row -> row.kind().equals("unchanged"));
    }

    @Test void htmlLikeRequirementTextRemainsLiteralInTheActualDom() {
        compare("<img src=x onerror=\"window.injected=true\">\nKeep & verify\n<scrip<script>t>",
                "<script>window.injected=true</script>\nKeep & verify\n</div><img src=x>\n&lt;literal&gt; &amp; &#39;", "en");
        assertThat(page.driver().findElements(By.cssSelector("#versionDetail img, #versionDetail script"))).isEmpty();
        assertThat(page.driver().executeScript("return window.injected === true;")).isEqualTo(false);
        assertThat(page.driver().findElement(By.cssSelector("#versionDetail .portfolio-requirement-text"))
                .getDomProperty("innerHTML")).contains("&lt;img", "&lt;script&gt;", "Keep &amp; verify");
    }

    @Test void aLargeChangedBlockRemainsCompleteAndExplainsTheBoundedComparison() {
        List<String> original = IntStream.range(0, 2000).mapToObj(index -> "Step " + index).toList();
        compare(String.join("\n", original), String.join("\n", original.reversed()), "en");
        assertThat(page.driver().findElement(By.cssSelector("#versionDetail details")).getDomProperty("textContent"))
                .containsIgnoringCase("large changed block");
    }

    @Test void aSmallEditInLongTextKeepsTheCommonPrefixAndSuffix() {
        String context = String.join("\n", IntStream.range(0, 2000)
                .mapToObj(index -> "Unchanged " + index).toList());
        var rows = compare(context + "\nBefore\n" + context, context + "\nAfter\n" + context, "en");
        assertThat(rows.stream().filter(row -> !row.kind().equals("unchanged")).toList()).hasSize(2);
        assertThat(page.driver().findElement(By.cssSelector("#versionDetail details")).getDomProperty("textContent"))
                .doesNotContainIgnoringCase("large changed block");
    }

    @Test void germanLargeComparisonGuidanceIsTranslated() {
        List<String> original = IntStream.range(0, 1100).mapToObj(index -> "Zeile " + index).toList();
        compare(String.join("\n", original), String.join("\n", original.reversed()), "de");
        assertThat(page.driver().findElement(By.cssSelector("#versionDetail details")).getDomProperty("textContent"))
                .containsIgnoringCase("große Änderungsblock")
                .doesNotContainIgnoringCase("large changed block");
    }

    @Test void theInitialVersionHasOneVisibleAndAccessibleSelectionMarker() {
        compare("Original", "Revised", "en");
        assertThat(page.driver().findElements(By.cssSelector("#versionList .active"))).hasSize(1);
        assertThat(page.driver().findElements(By.cssSelector("#versionList [aria-current='true']"))).hasSize(1);
        assertThat(page.driver().findElement(By.cssSelector("#versionList [data-version-id='12']"))
                .getDomAttribute("aria-current")).isEqualTo("true");
    }

    @Test void selectingAnotherVersionPreservesItsActualFocusTarget() {
        compare("Original", "Revised", "en");
        var previous = page.driver().findElement(By.cssSelector("#versionList [data-version-id='11']"));
        var current = page.driver().findElement(By.cssSelector("#versionList [data-version-id='12']"));
        previous.sendKeys(Keys.ENTER);
        assertThat(page.driver().switchTo().activeElement()).isEqualTo(previous);
        assertThat(previous.getDomAttribute("aria-current")).isEqualTo("true");
        assertThat(current.getDomAttribute("aria-current")).isNull();
        assertThat(page.driver().findElements(By.cssSelector("#versionList .active"))).hasSize(1);
        assertThat(page.driver().findElement(By.id("versionDetail")).getDomProperty("textContent"))
                .contains("Original").doesNotContain("Revised");
    }

    @Test void reopenedAnalysisLinksToTheOriginalTextAndModelAfterBothAdvance() {
        var fixture = new PortfolioRequirementFixture(page);
        fixture.setVersions("Record the original pump readings.", "Record the revised pump readings.");
        fixture.updateBaseline("Record only the latest readings.", "Pump control");
        String originalCommit = "a".repeat(40);
        var oldSnapshot = Map.of("id", "historic-impact", "projectId", 1, "requirementId", 2,
                "requirementVersionId", 11, "requirementVersionNumber", 1, "status", "SUCCESS",
                "workspaceId", "qa-copy", "branchName", "draft", "commitSha", originalCommit);
        var newSnapshot = Map.of("id", "latest-impact", "projectId", 1, "requirementId", 2,
                "requirementVersionId", 13, "requirementVersionNumber", 3, "status", "SUCCESS",
                "workspaceId", "qa-copy", "branchName", "draft", "commitSha", "b".repeat(40));
        page.route("GET", PortfolioRequirementFixture.REQUIREMENT + "/snapshots", request ->
                PortfolioClientTestPage.json(200, List.of(newSnapshot, oldSnapshot)));
        page.route("GET", PortfolioRequirementFixture.ROOT + "/snapshots/historic-impact", request ->
                PortfolioClientTestPage.json(200, Map.of("summary", oldSnapshot, "analysis", Map.of("modelBinding",
                        Map.of("repositoryId", "control-architecture", "workspaceScopeKey", "qa-copy",
                                "branch", "draft", "commit", originalCommit, "modelSource", "GIT_CHECKPOINT")))));
        fixture.open("de", "&snapshot=historic-impact");
        fixture.click("analyses-tab");

        var evidence = fixture.wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("snapshotImpactEvidence")));
        var model = evidence.findElement(By.cssSelector("a.snapshot-impact-model"));
        var link = UriComponentsBuilder.fromUriString(model.getDomAttribute("href")).build();
        assertThat(link.getQueryParams().toSingleValueMap())
                .containsEntry("repositoryId", "control-architecture").containsEntry("workspaceScopeKey", "qa-copy")
                .containsEntry("branch", "draft").containsEntry("commit", originalCommit).doesNotContainKey("revision");
        fixture.click(By.cssSelector("#snapshotImpactEvidence [data-snapshot-version='11']"));
        fixture.wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("versionDetail")));
        assertThat(page.driver().findElement(By.id("versionDetail")).getText())
                .contains("Record the original pump readings.").doesNotContain("Record only the latest readings.");
        assertThat(page.driver().getCurrentUrl()).contains("snapshot=historic-impact", "repositoryId=control-architecture",
                "workspaceScopeKey=qa-copy", "branch=draft");
    }
}

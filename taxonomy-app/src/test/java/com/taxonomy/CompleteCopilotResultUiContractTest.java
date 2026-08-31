package com.taxonomy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CompleteCopilotResultUiContractTest {

    @Test
    void normalReviewFlowUsesStructuredSectionsInsteadOfRawPayloads()
            throws IOException {
        String detail = Files.readString(Path.of(
                "src/main/resources/static/js/portfolio/requirement-detail.js"));

        assertThat(detail)
                .contains("snapshotResultOverview", "portfolio-result-kpi")
                .contains("renderGapResult", "renderPatternResult")
                .contains("renderRecommendationResult", "technicalSnapshotData")
                .contains("architectureWorkbenchUrl")
                .doesNotContain("gap.summary || gap.description || JSON.stringify(gap)")
                .doesNotContain(
                        "recommendation.summary || recommendation.recommendation"
                                + " || JSON.stringify(recommendation)")
                .doesNotContain("snapshotSequence", "verificationResult");
    }

    @Test
    void longEvidenceIsBoundedAndSecondaryDetailsStartCollapsed()
            throws IOException {
        String css = Files.readString(Path.of(
                "src/main/resources/static/css/taxonomy-portfolio.css"));
        String detail = Files.readString(Path.of(
                "src/main/resources/static/js/portfolio/requirement-detail.js"));

        assertThat(css)
                .contains(".portfolio-result-kpis")
                .contains("grid-template-columns: repeat(6")
                .contains(".portfolio-result-table")
                .contains("max-height: 34rem")
                .contains("overflow: auto")
                .contains("@media (max-width: 767.98px)");
        assertThat(detail)
                .contains("<details class=\"portfolio-finding-details\"")
                .contains("<details id=\"technicalSnapshotData\"")
                .doesNotContain(
                        "<details class=\"portfolio-finding-details\" open")
                .doesNotContain(
                        "<details id=\"technicalSnapshotData\""
                                + " class=\"portfolio-technical-data mt-3\" open");
    }

    @Test
    void selectedSnapshotIsExplicitWithoutInventingPassNumbers()
            throws IOException {
        String detail = Files.readString(Path.of(
                "src/main/resources/static/js/portfolio/requirement-detail.js"));

        assertThat(detail)
                .contains("String(state.selectedSnapshot.id) === String(snapshot.id)")
                .contains("button.setAttribute('aria-current', 'true')")
                .contains("renderSnapshots();")
                .doesNotContain("Verification result {0} of {1}")
                .doesNotContain("Prüfergebnis {0} von {1}");
    }

    @Test
    void snapshotBoundUrlsActivateTheResultOnlyAfterItHasLoaded()
            throws IOException {
        String detail = Files.readString(Path.of(
                "src/main/resources/static/js/portfolio/requirement-detail.js"));
        String session = Files.readString(Path.of(
                "src/test/java/com/taxonomy/CompleteCopilotSessionIT.java"));

        assertThat(detail)
                .contains("await selectSnapshot(requestedSnapshot);")
                .contains("activateDetailTab('analyses-tab');")
                .contains("window.bootstrap.Tab.getOrCreateInstance(trigger).show();");
        int methodStart = session.indexOf(
                "private static void openSelectedSnapshotThroughVisibleAnalysisControls(long projectId)");
        int methodEnd = session.indexOf(
                "private static void saveCompleteCopilotRunResultScreenshot()",
                methodStart);
        assertThat(methodStart).isGreaterThanOrEqualTo(0);
        assertThat(methodEnd).isGreaterThan(methodStart);
        String navigationMethod = session.substring(methodStart, methodEnd);
        assertThat(navigationMethod)
                .contains("aria-selected", "snapshotResultOverview")
                .contains("no compensating tab or snapshot click")
                .doesNotContain("click(By.id(\"analyses-tab\"))")
                .doesNotContain(
                        "click(By.cssSelector(\"#snapshotList [data-snapshot-id]\"))");
    }


    @Test
    void findingTablesExposeLocalizedAccessibleColumnHeaders()
            throws IOException {
        String detail = Files.readString(Path.of(
                "src/main/resources/static/js/portfolio/requirement-detail.js"));
        String css = Files.readString(Path.of(
                "src/main/resources/static/css/taxonomy-portfolio.css"));

        assertThat(detail)
                .contains("<caption class=\"visually-hidden\">")
                .contains("<th scope=\"col\">")
                .contains("findingColumnLabel(field)")
                .contains("sourceNode: 'Source node'")
                .contains("sourceNode: 'Quellknoten'")
                .contains("coverageScore: 'Coverage score'")
                .contains("coverageScore: 'Abdeckungswert'");
        assertThat(css).contains(".portfolio-result-table thead th");
    }


    @Test
    void suggestedRelationshipTableHasCaptionAndScopedHeaders()
            throws IOException {
        String detail = Files.readString(Path.of(
                "src/main/resources/static/js/portfolio/requirement-detail.js"));

        assertThat(detail)
                .contains("suggestedRelations")
                .contains("<caption class=", "visually-hidden")
                .contains("<th scope=", "t('relation')", "t('reasoning')");
    }

    @Test
    void workbenchLinkKeepsTheExactSelectedImmutableSnapshot()
            throws IOException {
        String detail = Files.readString(Path.of(
                "src/main/resources/static/js/portfolio/requirement-detail.js"));
        String session = Files.readString(Path.of(
                "src/test/java/com/taxonomy/CompleteCopilotSessionIT.java"));

        assertThat(detail)
                .contains("new URL('/architecture/workbench', window.location.origin)")
                .contains("url.searchParams.set('projectId', String(projectId))")
                .contains("url.searchParams.set('snapshotId', String(state.selectedSnapshot.id))")
                .doesNotContain("+ '/architecture?lang=' + encodeURIComponent(locale)");
        assertThat(session)
                .contains("openSelectedSnapshotThroughVisibleAnalysisControls(projectId)")
                .contains("#snapshotResultOverview a[href*='/architecture/workbench']")
                .contains("projectId=")
                .contains("snapshotId=")
                .contains("selectedSnapshotId");
    }

}

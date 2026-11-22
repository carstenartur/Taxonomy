package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Keys;
import org.openqa.selenium.support.ui.ExpectedConditions;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.taxonomy.PortfolioClientTestPage.json;
import static com.taxonomy.PortfolioRequirementFixture.REQUIREMENT;
import static com.taxonomy.PortfolioRequirementFixture.ROOT;
import static com.taxonomy.PortfolioRequirementFixture.SAVE_REQUIREMENT;
import static org.assertj.core.api.Assertions.assertThat;

/** Real form events and HTTP races, owned by JUnit/Failsafe rather than a script suite. */
@Tag("browser")
class PortfolioRequirementReviewIT {
    private static PortfolioClientTestPage page;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll static void openBrowser(@TempDir Path downloads) throws Exception {
        page = new PortfolioClientTestPage(downloads);
        page.driver().manage().window().setSize(new Dimension(1440, 1000));
    }

    @AfterAll static void closeBrowser() { if (page != null) page.close(); }

    private PortfolioRequirementFixture open() {
        var fixture = new PortfolioRequirementFixture(page);
        fixture.open("en");
        return fixture;
    }

    private static final class PendingResponse implements AutoCloseable {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final Supplier<PortfolioClientTestPage.Response> response;

        PendingResponse(Supplier<PortfolioClientTestPage.Response> response) { this.response = response; }

        PortfolioClientTestPage.Response respond(PortfolioClientTestPage.Request request) {
            started.countDown();
            try {
                if (!released.await(20, TimeUnit.SECONDS)) throw new AssertionError("Test did not release " + request.path());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for " + request.path(), interrupted);
            }
            return response.get();
        }

        void awaitStarted() throws Exception {
            assertThat(started.await(20, TimeUnit.SECONDS)).as("The real HTTP request reached its fixture").isTrue();
        }

        void release() { released.countDown(); }
        @Override public void close() { release(); }
    }

    private PendingResponse startBackground(PortfolioRequirementFixture fixture) {
        var response = new PendingResponse(() ->
                json(200, Map.of("id", "qa-refresh", "status", "SUCCESS", "items", List.of())));
        page.route("GET", ROOT + "/analysis-jobs/qa-refresh", response::respond);
        fixture.click("reanalyzeRequirement");
        fixture.idle();
        return response;
    }

    private void refreshed(PortfolioRequirementFixture fixture, String text) {
        fixture.wait.until(driver -> text.equals(fixture.element("currentText").getDomProperty("textContent")));
        fixture.idle();
    }

    private void error(PortfolioRequirementFixture fixture, String text) {
        fixture.wait.until(ExpectedConditions.textToBe(By.id("newVersionError"), text));
        fixture.idle();
        assertThat(fixture.element("newVersionError").isDisplayed()).isTrue();
        assertThat(page.driver().switchTo().activeElement()).isEqualTo(fixture.element("newVersionError"));
    }

    @Test
    void architectOpensRecordedQuestionsFromTheSelectedHistoricalSnapshot() {
        var fixture = new PortfolioRequirementFixture(page);
        var summary = Map.of("id", "qa-partial", "projectId", 1, "requirementId", 2, "requirementVersionId", 11,
                "requirementVersionNumber", 1, "status", "PARTIAL");
        String question = "Welches Team bestätigt den Eingang?";
        // The analysis did not ask a question; the selected historical version has an explicit decision section.
        var older = new java.util.LinkedHashMap<>(PortfolioRequirementFixture.version(11, 1, "## Offene Entscheidung\n\n" + question));
        older.put("decisionSectionPresent", true);
        fixture.versions.set(List.of(fixture.versions.get().getFirst(), older));
        var analysis = Map.of("analysisCoverage", Map.of("nodes", Map.of()));
        page.route("GET", REQUIREMENT + "/snapshots", request -> json(200, List.of(summary)));
        page.route("GET", ROOT + "/snapshots/qa-partial", request -> json(200, Map.of("summary", summary, "analysis", analysis)));
        var item = Map.of("id", "analysis-1", "state", "OPEN", "wording", question, "discoveries", List.of(),
                "affectedStatementIds", List.of(), "answerSchema", Map.of("kind", "TEXT"));
        var offer = Map.of("id", "clarification-offer", "baseline", Map.of("sourceVersionId", 11, "snapshotId", "qa-partial",
                        "originalText", "Original", "frozenContext", Map.of("questionSource", "ANALYSIS_SNAPSHOT")),
                "currentRevision", Map.of("number", 1, "text", "Original", "questions", List.of(item),
                        "validation", Map.of("findings", List.of()), "statements", List.of(), "answers", List.of()));
        page.route("POST", REQUIREMENT + "/reformulations", request -> json(200, offer));
        page.route("GET", REQUIREMENT + "/reformulations/clarification-offer", request -> json(200, offer));
        page.route("GET", REQUIREMENT + "/reformulations/clarification-offer/synthesis-runs", request -> json(200, List.of()));
        fixture.open("de", "&snapshot=qa-partial");
        var action = By.cssSelector("button[data-clarify-snapshot]");
        fixture.click(action);
        var input = fixture.wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#question-analysis-1 textarea")));
        assertThat(input.getDomAttribute("aria-label")).isEqualTo(question);
        input.sendKeys("Eingangsprüfung");
        fixture.click(action);
        assertThat(input.getDomProperty("value")).isEqualTo("Eingangsprüfung");
        var creates = page.requests().stream().filter(r -> r.method().equals("POST") && r.path().equals(REQUIREMENT+"/reformulations")).toList();
        assertThat(creates).singleElement().satisfies(request -> {
            var sent = JSON.readTree(request.body());
            assertThat(sent.path("includeAnalysisQuestions").asBoolean()).isTrue();
            assertThat(sent.path("sourceVersionId").asInt()).isEqualTo(11);
            assertThat(sent.path("snapshotId").asText()).isEqualTo("qa-partial");
        });
    }

    @ParameterizedTest
    @CsvSource({"en,Analyze selected version", "de,Ausgewählte Version analysieren"})
    void analysisActionNamesAndSubmitsTheSelectedVersion(String locale, String label) {
        var fixture = new PortfolioRequirementFixture(page);
        fixture.open(locale);
        fixture.click("versions-tab");
        var current = By.cssSelector("#versionList [data-version-id='12']");
        assertThat(page.driver().findElement(current).getDomAttribute("aria-current")).isEqualTo("true");
        fixture.click(By.cssSelector("#versionList [data-version-id='11']"));
        assertThat(page.driver().findElement(current).getDomAttribute("aria-current")).isNull();
        assertThat(fixture.element("reanalyzeRequirement").getAccessibleName()).isEqualTo(label);

        int initialReads = page.requests().size();
        fixture.click("reanalyzeRequirement");
        fixture.wait.until(driver -> page.requests().stream().skip(initialReads)
                .anyMatch(request -> request.path().equals(REQUIREMENT + "/versions")));
        fixture.idle();
        assertThat(page.driver().findElement(By.cssSelector("#versionList [aria-current='true']"))
                .getDomAttribute("data-version-id")).isEqualTo("11");

        page.route("POST", REQUIREMENT + "/analyses", request -> json(202, Map.of("id", "second-job")));
        fixture.click("reanalyzeRequirement");
        fixture.idle();
        var submittedVersions = page.requests().stream().filter(request -> request.method().equals("POST")
                && request.path().equals(REQUIREMENT + "/analyses"))
                .map(request -> JSON.readTree(request.body()).path("requirementVersionId").asInt()).toList();
        assertThat(submittedVersions).containsExactly(11, 11);
    }

    @Test void versionFeedbackIsInsideTheActualModalFocusTrap() {
        var fixture = open();
        fixture.openVersionModal();
        var error = page.driver().findElement(By.cssSelector("#newVersionModal #newVersionForm #newVersionError"));
        assertThat(error.getDomAttribute("role")).isEqualTo("alert");
        assertThat(error.getDomAttribute("tabindex")).isEqualTo("-1");
    }

    @ParameterizedTest @ValueSource(ints = {400, 409})
    void failedVersionCreationPreservesDraftAndShowsLocalFocusedError(int status) {
        var fixture = open();
        String message = status == 409 ? "This requirement changed. Review the new version."
                : "Change reason is required.";
        page.route("POST", SAVE_REQUIREMENT, request -> json(status, Map.of("detail", message)));
        fixture.openVersionModal();
        fixture.editVersion();
        fixture.submitVersion();
        error(fixture, message);
        fixture.assertDraft();
        assertThat(fixture.element("newVersionModal").isDisplayed()).isTrue();
        assertThat(fixture.element("detailError").getDomProperty("textContent")).isEmpty();
        var submitted = page.requests().stream()
                .filter(request -> request.method().equals("POST") && request.path().equals(SAVE_REQUIREMENT))
                .reduce((first, second) -> second).orElseThrow();
        var body = JSON.readTree(submitted.body());
        assertThat(body.path("choice").asString()).isEqualTo("CHANGE");
        assertThat(body.path("projectId").asInt()).isEqualTo(1);
        assertThat(body.path("requirementId").asInt()).isEqualTo(2);
        assertThat(body.path("expectedVersionId").asInt()).isEqualTo(12);
        assertThat(body.path("expectedContentHash").asString()).isEqualTo(PortfolioRequirementFixture.hash("Stored text"));
        assertThat(body.path("inputHash").asString()).isEqualTo(PortfolioRequirementFixture.hash(PortfolioRequirementFixture.DRAFT.getFirst()));
        assertThat(body.path("requirement").path("text").asString()).isEqualTo(PortfolioRequirementFixture.DRAFT.getFirst());
        assertThat(body.path("requirement").path("source").path("sourceArtifactId").asInt()).isEqualTo(91);
        assertThat(body.path("requirement").path("source").path("pageNumber").asInt()).isEqualTo(9);
        assertThat(page.requests()).noneMatch(request -> request.method().equals("POST") && request.path().endsWith("/versions"));
    }

    @Test void retryClearsTheOldErrorWhileKeepingSubmittedFields() throws Exception {
        var fixture = open();
        page.route("POST", SAVE_REQUIREMENT, request -> json(409, Map.of("detail", "First attempt failed")));
        fixture.openVersionModal();
        fixture.editVersion();
        fixture.submitVersion();
        error(fixture, "First attempt failed");
        try (var pending = new PendingResponse(() -> json(400, Map.of("detail", "Retry failed")))) {
            page.route("POST", SAVE_REQUIREMENT, pending::respond);
            fixture.submitVersion();
            pending.awaitStarted();
            assertThat(fixture.element("newVersionError").isDisplayed()).isFalse();
            assertThat(fixture.element("newVersionError").getDomProperty("textContent")).isEmpty();
            fixture.assertDraft();
            pending.release();
            error(fixture, "Retry failed");
        }
    }

    @Test void terminalBackgroundAnalysisPreservesTheOpenDraftAndSelection() {
        var fixture = open();
        try (var pending = startBackground(fixture)) {
            fixture.openVersionModal();
            fixture.editVersion();
            fixture.updateBaseline("New persisted baseline", "Refreshed requirement");
            pending.release();
            refreshed(fixture, "New persisted baseline");
            fixture.assertDraft();
            assertThat(fixture.element("versionText").getDomProperty("selectionStart")).isEqualTo("3");
            assertThat(fixture.element("versionText").getDomProperty("selectionEnd")).isEqualTo("8");
            assertThat(fixture.element("requirementHeading").getText()).isEqualTo("Refreshed requirement");
        }
    }

    @Test void aFormOpenedDuringTheBackgroundReadSurvivesTheLateResponse() throws Exception {
        var fixture = open();
        try (var read = new PendingResponse(() -> json(200, fixture.requirement.get()));
             var job = startBackground(fixture)) {
            page.route("GET", REQUIREMENT, read::respond);
            fixture.updateBaseline("Late persisted baseline", "Refreshed requirement");
            job.release();
            read.awaitStarted();
            // Native keyboard operation remains possible while the background busy overlay is shown.
            fixture.element("newVersionButton").sendKeys(Keys.ENTER);
            fixture.wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("newVersionModal")));
            fixture.editVersion();
            read.release();
            refreshed(fixture, "Late persisted baseline");
            fixture.assertDraft();
        }
    }

    @Test void closingAnEditedFormKeepsItsDraftThroughBackgroundRefresh() {
        var fixture = open();
        try (var pending = startBackground(fixture)) {
            fixture.openVersionModal();
            fixture.editVersion();
            fixture.closeVersionModal();
            fixture.updateBaseline("New baseline after closing", "Refreshed requirement");
            pending.release();
            refreshed(fixture, "New baseline after closing");
            fixture.assertDraft();
        }
    }

    @Test void anUntouchedClosedFormFollowsTheRefreshedBaseline() {
        var fixture = open();
        try (var pending = startBackground(fixture)) {
            fixture.updateBaseline("Updated baseline", "Refreshed requirement");
            pending.release();
            refreshed(fixture, "Updated baseline");
            assertThat(fixture.element("versionText").getDomProperty("value")).isEqualTo("Updated baseline");
        }
    }

    @Test void savedVersionClearsDirtyStateAndAllowsLaterBaselineRefresh() {
        var fixture = open();
        page.route("POST", SAVE_REQUIREMENT, request -> {
            var body = JSON.readTree(request.body());
            fixture.updateBaseline(body.path("requirement").path("text").asString(), "Pump control");
            return json(200, fixture.saved());
        });
        fixture.openVersionModal();
        fixture.editVersion();
        fixture.submitVersion();
        fixture.wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("newVersionModal")));
        refreshed(fixture, PortfolioRequirementFixture.DRAFT.getFirst());
        assertThat(page.driver().findElement(By.cssSelector("#versionList [aria-current='true']"))
                .getDomAttribute("data-version-id")).isEqualTo("13");
        try (var pending = startBackground(fixture)) {
            fixture.updateBaseline("Subsequently refreshed baseline", "Refreshed requirement");
            pending.release();
            refreshed(fixture, "Subsequently refreshed baseline");
            assertThat(fixture.element("versionText").getDomProperty("value"))
                    .isEqualTo("Subsequently refreshed baseline");
        }
    }
}

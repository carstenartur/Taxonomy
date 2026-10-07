package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
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
        page.route("POST", REQUIREMENT + "/versions", request -> json(status, Map.of("detail", message)));
        fixture.openVersionModal();
        fixture.editVersion();
        fixture.submitVersion();
        error(fixture, message);
        fixture.assertDraft();
        assertThat(fixture.element("newVersionModal").isDisplayed()).isTrue();
        assertThat(fixture.element("detailError").getDomProperty("textContent")).isEmpty();
        var submitted = page.requests().stream()
                .filter(request -> request.method().equals("POST") && request.path().endsWith("/versions"))
                .reduce((first, second) -> second).orElseThrow();
        var body = JSON.readTree(submitted.body());
        assertThat(body.path("text").asString()).isEqualTo(PortfolioRequirementFixture.DRAFT.getFirst());
        assertThat(body.path("source").path("sourceArtifactId").asInt()).isEqualTo(91);
        assertThat(body.path("source").path("pageNumber").asInt()).isEqualTo(9);
    }

    @Test void retryClearsTheOldErrorWhileKeepingSubmittedFields() throws Exception {
        var fixture = open();
        page.route("POST", REQUIREMENT + "/versions", request -> json(409, Map.of("detail", "First attempt failed")));
        fixture.openVersionModal();
        fixture.editVersion();
        fixture.submitVersion();
        error(fixture, "First attempt failed");
        try (var pending = new PendingResponse(() -> json(400, Map.of("detail", "Retry failed")))) {
            page.route("POST", REQUIREMENT + "/versions", pending::respond);
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
        page.route("POST", REQUIREMENT + "/versions", request -> {
            var body = JSON.readTree(request.body());
            fixture.updateBaseline(body.path("text").asString(), "Pump control");
            return json(201, fixture.requirement.get().get("currentVersion"));
        });
        fixture.openVersionModal();
        fixture.editVersion();
        fixture.submitVersion();
        fixture.wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("newVersionModal")));
        refreshed(fixture, PortfolioRequirementFixture.DRAFT.getFirst());
        try (var pending = startBackground(fixture)) {
            fixture.updateBaseline("Subsequently refreshed baseline", "Refreshed requirement");
            pending.release();
            refreshed(fixture, "Subsequently refreshed baseline");
            assertThat(fixture.element("versionText").getDomProperty("value"))
                    .isEqualTo("Subsequently refreshed baseline");
        }
    }
}

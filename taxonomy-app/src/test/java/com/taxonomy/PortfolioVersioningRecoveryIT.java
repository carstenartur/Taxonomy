package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-browser recovery contracts for the production versioning page.
 * JUnit owns every case, network delay and assertion; no JavaScript test runner
 * or replacement DOM is involved. The same BrowserSession runs locally or in
 * the repository's Selenium container.
 */
@Tag("browser")
@Tag("ui-acceptance")
class PortfolioVersioningRecoveryIT {
    private static final String PREFIX = "/taxonomy";
    private static final String COMMIT_DRAFT = "  Reviewed the revised requirements.\nKeep this context.  ";
    private static final String MERGE_DRAFT = "  Merge the reviewed proposal.  ";
    private static final List<String> BRANCH_IDS = List.of("commitBranch", "materializeBranch", "mergeSource", "mergeTarget");
    private static final Map<String, String> SELECTIONS = Map.of(
            "commitBranch", "release", "materializeBranch", "review", "mergeSource", "proposal", "mergeTarget", "main");
    private static final Map<String, String> DRAFTS = Map.of("commitMessage", COMMIT_DRAFT, "mergeMessage", MERGE_DRAFT);
    private static final Map<String, String> DEFAULT_DRAFTS = Map.of(
            "commitMessage", "Portfolio PORT-42: reviewed project state", "mergeMessage", "Merge portfolio branches for PORT-42");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir static Path downloads;
    private static PortfolioClientTestPage page;
    private static RemoteWebDriver driver;
    private static WebDriverWait wait;
    private static RecoveryResponses responses;

    @BeforeAll static void startBrowser() throws Exception {
        page = new PortfolioClientTestPage(downloads);
        driver = page.driver();
        driver.manage().window().setSize(new Dimension(1440, 1000));
        wait = new WebDriverWait(driver, Duration.ofSeconds(20));
    }

    @AfterAll static void stopBrowser() throws Exception {
        if (responses != null) responses.releaseRead();
        if (page != null) page.close();
    }

    @BeforeEach void resetResponses() {
        if (responses != null) responses.releaseRead();
        responses = new RecoveryResponses();
        configureResponses();
    }

    @ParameterizedTest(name = "{0}: initial load sets exact DTO branches and message defaults")
    @ValueSource(strings = {"en", "de"})
    void initialLoadSetsMessageAndBranchDefaults(String locale) {
        open(locale);
        for (String id : BRANCH_IDS) {
            assertThat(new Select(element(id)).getOptions())
                    .extracting(option -> List.of(option.getDomProperty("value"), option.getText()))
                    .as("%s renders each RepositoryState string as its value and label", id)
                    .containsExactly(List.of("main", "main"), List.of("review", "review"),
                            List.of("release", "release"), List.of("proposal", "proposal"));
        }
        assertDrafts(DEFAULT_DRAFTS);
        assertSelections(Map.of("commitBranch", "main", "materializeBranch", "main",
                "mergeSource", "review", "mergeTarget", "main"));
        assertThat(element("refreshPreview").getText()).isEqualTo(label(locale, "refresh"));
        assertThat(element("reportsLink").getDomAttribute("href"))
                .isEqualTo(PREFIX + "/projects/42/reports?lang=" + locale);
        assertThat(text("versioningError")).isEmpty();
    }

    @ParameterizedTest(name = "{0}: manual refresh preserves deliberately empty drafts = {1}")
    @MethodSource("localesAndEmpty")
    void manualRefreshPreservesExactMessagesAndIndependentBranches(String locale, boolean empty) {
        prepare(locale);
        Map<String, String> expected = empty ? Map.of("commitMessage", "", "mergeMessage", "") : DRAFTS;
        editAll(expected);
        responses.dsl = "project PORT-42 refreshed";
        refresh();
        assertThat(text("dslPreview")).isEqualTo(responses.dsl);
        assertDrafts(expected);
        assertSelections(SELECTIONS);
        assertThat(hidden("versioningBusy")).isTrue();
    }

    @ParameterizedTest(name = "{0}: pending refresh preserves edits made while reading; empty = {1}")
    @MethodSource("localesAndEmpty")
    void deferredRefreshPreservesLatestDraftAndBranchEdits(String locale, boolean empty) throws Exception {
        prepare(locale);
        try (ReadGate gate = responses.deferRead()) {
            click(By.id("refreshPreview"));
            gate.awaitStarted();
            assertThat(hidden("versioningBusy")).isFalse();
            Map<String, String> latest = empty ? Map.of("commitMessage", "", "mergeMessage", "")
                    : Map.of("commitMessage", "Newest commit draft", "mergeMessage", "Newest merge draft");
            Map<String, String> latestSelections = Map.of("commitBranch", "proposal", "materializeBranch", "release",
                    "mergeSource", "review", "mergeTarget", "release");
            editAll(latest);
            editAll(latestSelections);
            gate.release();
            awaitIdle();
            assertDrafts(latest);
            assertSelections(latestSelections);
        }
    }

    @ParameterizedTest(name = "{0}: initial pending read preserves a message typed then cleared = {1}")
    @MethodSource("localesAndEmpty")
    void initialDeferredLoadPreservesInputBeforeDefaultsArrive(String locale, boolean empty) throws Exception {
        try (ReadGate gate = responses.deferRead()) {
            navigate(locale);
            gate.awaitStarted();
            edit("commitMessage", COMMIT_DRAFT);
            if (empty) edit("commitMessage", "");
            gate.release();
            awaitIdle();
            assertDrafts(Map.of("commitMessage", empty ? "" : COMMIT_DRAFT,
                    "mergeMessage", DEFAULT_DRAFTS.get("mergeMessage")));
        }
    }

    @ParameterizedTest(name = "{0}: manual refresh failure preserves drafts and identifies a read error")
    @ValueSource(strings = {"en", "de"})
    void failedManualRefreshPreservesDraftsAndIdentifiesReadFailure(String locale) {
        prepare(locale);
        responses.readFailure = "Repository read unavailable";
        refresh();
        assertDrafts(DRAFTS);
        assertSelections(SELECTIONS);
        assertThat(text("versioningError"))
                .isEqualTo(label(locale, "refreshFailed") + " Repository read unavailable");
        assertThat(hidden("versioningInfo")).isTrue();
    }

    @ParameterizedTest(name = "{0}: a new active repository branch preserves every available selection")
    @ValueSource(strings = {"en", "de"})
    void changingTheActiveRepositoryBranchPreservesIndependentSelections(String locale) {
        prepare(locale);
        responses.activeBranch = "review";
        refresh();
        assertThat(text("activeBranch")).isEqualTo("review");
        assertSelections(SELECTIONS);
    }

    @ParameterizedTest(name = "{0}: successful {1} survives a deferred follow-up read")
    @MethodSource("localesAndOperations")
    void mutationSuccessSurvivesDeferredFollowUpRefresh(String locale, Operation operation) throws Exception {
        prepare(locale);
        prepareOperation(operation);
        try (ReadGate gate = responses.deferRead()) {
            trigger(operation);
            gate.awaitStarted();
            assertSuccess(locale, operation);
            assertThat(responses.mutationRequest).isEqualTo(JSON.valueToTree(operation.request()));
            edit("commitMessage", "Next commit draft");
            edit("mergeMessage", "");
            gate.release();
            awaitIdle();
            assertSuccess(locale, operation);
            assertDrafts(Map.of("commitMessage", "Next commit draft", "mergeMessage", ""));
            assertSelections(SELECTIONS);
            assertThat(hidden("versioningError")).isTrue();
            assertThat(text("versioningLive")).isEqualTo(text("versioningInfo"));
            if (operation == Operation.MATERIALIZATION) {
                assertThat(hidden("applyMaterialize")).isTrue();
                assertThat(text("materializePreview")).isEmpty();
            }
            if (operation == Operation.MERGE) assertThat(text("mergeResult")).contains(label(locale, "merged"));
        }
    }

    @ParameterizedTest(name = "{0}: completed {1} remains successful when follow-up reading fails")
    @MethodSource("localesAndOperations")
    void completedMutationRemainsSuccessfulWhenFollowUpReadFails(String locale, Operation operation) {
        prepare(locale);
        prepareOperation(operation);
        responses.readFailure = "Repository read unavailable";
        trigger(operation);
        awaitError("Repository read unavailable");
        assertSuccess(locale, operation);
        assertThat(text("versioningError"))
                .isEqualTo(label(locale, "refreshFailed") + " Repository read unavailable");
        assertThat(text("versioningLive")).isEqualTo(text("versioningError"));
        assertDrafts(DRAFTS);
        assertSelections(SELECTIONS);
    }

    @ParameterizedTest(name = "{0}: rejected {1} preserves drafts and never reports success")
    @MethodSource("localesAndOperations")
    void rejectedMutationPreservesDraftsAndDoesNotStartSuccessRefresh(String locale, Operation operation) {
        prepare(locale);
        prepareOperation(operation);
        int reads = responses.readCount.get();
        responses.mutationFailure = "Mutation rejected: stale HEAD";
        trigger(operation);
        awaitError(responses.mutationFailure);
        assertThat(hidden("versioningInfo")).isTrue();
        assertThat(text("versioningInfo")).isEmpty();
        assertThat(text("versioningError")).isEqualTo(responses.mutationFailure);
        assertThat(driver.switchTo().activeElement()).isEqualTo(element("versioningError"));
        assertThat(responses.readCount.get()).as("A rejected mutation cannot launch a success refresh").isEqualTo(reads);
        assertDrafts(DRAFTS);
        assertSelections(SELECTIONS);
        if (operation == Operation.MATERIALIZATION) assertThat(hidden("applyMaterialize")).isFalse();
        if (operation == Operation.MERGE) assertThat(text("mergeResult")).isEmpty();
    }

    @ParameterizedTest(name = "{0}: unsuccessful merge retry clears earlier success; same branch = {1}")
    @MethodSource("localesAndEmpty")
    void unsuccessfulMergeRetryClearsThePreviousSuccess(String locale, boolean sameBranch) {
        prepare(locale);
        trigger(Operation.MERGE);
        wait.until(ignored -> text("mergeResult").contains(label(locale, "merged")) && hidden("versioningBusy"));
        if (sameBranch) edit("mergeSource", element("mergeTarget").getDomProperty("value"));
        else responses.mutationFailure = "Merge conflict";
        int mutations = responses.mutationCount.get();
        trigger(Operation.MERGE);
        String expected = sameBranch ? label(locale, "sourceTargetDiffer") : "Merge conflict";
        awaitError(expected);
        assertThat(text("mergeResult")).as("An earlier merge cannot label this attempt successful").isEmpty();
        assertThat(hidden("versioningInfo")).isTrue();
        assertThat(text("versioningError")).isEqualTo(expected);
        assertThat(responses.mutationCount.get()).isEqualTo(mutations + (sameBranch ? 0 : 1));
    }

    private static Stream<Arguments> localesAndEmpty() {
        return Stream.of("en", "de").flatMap(locale -> Stream.of(false, true).map(empty -> Arguments.of(locale, empty)));
    }

    private static Stream<Arguments> localesAndOperations() {
        return Stream.of("en", "de").flatMap(locale -> Stream.of(Operation.values()).map(operation -> Arguments.of(locale, operation)));
    }

    private static void open(String locale) {
        navigate(locale);
        wait.until(ignored -> text("versioningProject").contains("PORT-42") && hidden("versioningBusy"));
        assertThat(text("versioningError")).isEmpty();
    }

    private static void navigate(String locale) {
        page.open("portfolio-versioning", PREFIX, "/projects/42/versioning", locale);
    }

    private static void configureResponses() {
        page.clearRoutes();
        page.clearRequests();
        RecoveryResponses current = responses;
        page.route("GET", PREFIX + "/api/projects/42", request -> PortfolioClientTestPage.json(200,
                Map.of("id", 42, "projectKey", "PORT-42", "title", "Reviewed portfolio")));
        page.route("GET", PREFIX + "/api/account/me", request -> PortfolioClientTestPage.json(200,
                Map.of("architectureMutationAllowed", true)));
        page.route("GET", PREFIX + "/api/projects/git/export", request -> PortfolioClientTestPage.json(200, current.export()));
        page.route("GET", PREFIX + "/api/git/state", request -> {
            current.readCount.incrementAndGet();
            ReadGate gate = current.readGate;
            if (gate != null) {
                gate.started.countDown();
                try {
                    if (!gate.response.await(20, TimeUnit.SECONDS)) {
                        return PortfolioClientTestPage.json(500, Map.of("detail", "JUnit did not release the repository read"));
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    return PortfolioClientTestPage.json(500, Map.of("detail", "Repository read interrupted"));
                }
            }
            return current.readFailure == null ? PortfolioClientTestPage.json(200, current.repository())
                    : PortfolioClientTestPage.json(503, Map.of("detail", current.readFailure));
        });
        page.route("GET", PREFIX + "/api/projects/git/materialize-preview", request -> PortfolioClientTestPage.json(200,
                Map.of("targetHead", "review-head-123456789", "changed", true,
                        "addedLines", 1, "removedLines", 0, "destructiveChangePossible", false,
                        "addedPreview", List.of("requirement REVIEW"))));
        Map<String, Object> results = Map.of(
                "commit", Map.of("commitId", "commit-123456789"),
                "materialize", Map.of("branch", "review", "commitId", "restore-123456789"),
                "merge", Map.of("strategy", "GIT", "mergeCommitId", "merge-123456789"));
        results.forEach((operation, result) -> page.route("POST", PREFIX + "/api/projects/git/" + operation, request -> {
            current.mutationCount.incrementAndGet();
            current.mutationRequest = JSON.readTree(request.body());
            return current.mutationFailure == null ? PortfolioClientTestPage.json(200, result)
                    : PortfolioClientTestPage.json(409, Map.of("detail", current.mutationFailure));
        }));
    }

    private static void prepare(String locale) {
        open(locale);
        editAll(SELECTIONS);
        editAll(DRAFTS);
    }

    private static void editAll(Map<String, String> values) { values.forEach(PortfolioVersioningRecoveryIT::edit); }

    private static void edit(String id, String value) {
        WebElement field = element(id);
        if (field.getTagName().equals("select")) {
            List<String> options = new Select(field).getOptions().stream()
                    .map(option -> option.getDomProperty("value")).toList();
            int selected = options.indexOf(value);
            assertThat(selected).as("The requested branch is present in %s", id).isNotNegative();
            // Native keyboard selection remains available during the read's
            // busy indicator, just like typing into an already focused draft.
            field.sendKeys(Keys.HOME);
            for (int index = 0; index < selected; index++) field.sendKeys(Keys.ARROW_DOWN);
            field.sendKeys(Keys.TAB);
        } else {
            field.sendKeys(Keys.chord(Keys.CONTROL, "a"));
            field.sendKeys(Keys.BACK_SPACE);
            if (!value.isEmpty()) field.sendKeys(value);
        }
    }

    private static void assertDrafts(Map<String, String> expected) {
        expected.forEach((id, value) -> assertThat(element(id).getDomProperty("value"))
                .as("%s retains the user's exact value", id).isEqualTo(value));
    }

    private static void assertSelections(Map<String, String> expected) {
        expected.forEach((id, value) -> assertThat(new Select(element(id)).getFirstSelectedOption().getDomProperty("value"))
                .as("%s retains its independent branch", id).isEqualTo(value));
    }

    private static void assertSuccess(String locale, Operation operation) {
        assertThat(hidden("versioningInfo")).as("Confirmed mutation success stays visible").isFalse();
        assertThat(text("versioningInfo")).startsWith(label(locale, operation.success));
    }

    private static void prepareOperation(Operation operation) {
        if (operation != Operation.MATERIALIZATION) return;
        click(By.id("previewMaterialize"));
        wait.until(ignored -> !hidden("applyMaterialize") && hidden("versioningBusy"));
    }

    private static void trigger(Operation operation) {
        if (operation == Operation.MATERIALIZATION) {
            click(By.id("applyMaterialize"));
            driver.switchTo().alert().accept();
        } else {
            click(By.cssSelector("#" + operation.form + " button[type='submit']"));
        }
    }

    private static void refresh() {
        int reads = responses.readCount.get();
        click(By.id("refreshPreview"));
        wait.until(ignored -> responses.readCount.get() > reads && hidden("versioningBusy"));
    }

    private static void click(By selector) {
        new WebDriverWait(driver, Duration.ofSeconds(20))
                .ignoring(ElementClickInterceptedException.class)
                .ignoring(StaleElementReferenceException.class)
                .withMessage("Native click on " + selector)
                .until(browser -> {
                    WebElement control = browser.findElement(selector);
                    if (!control.isDisplayed() || !control.isEnabled()) return false;
                    driver.executeScript("arguments[0].scrollIntoView({behavior:'instant',block:'center',inline:'nearest'});", control);
                    if (!Boolean.TRUE.equals(driver.executeScript("""
                            const element = arguments[0], bounds = element.getBoundingClientRect();
                            const target = document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
                            return target === element || element.contains(target);
                            """, control))) return false;
                    control.click();
                    return true;
                });
    }

    private static void awaitIdle() { wait.until(ignored -> hidden("versioningBusy")); }
    private static void awaitError(String expected) { wait.until(ignored -> text("versioningError").contains(expected) && hidden("versioningBusy")); }
    private static WebElement element(String id) { return driver.findElement(By.id(id)); }
    private static String text(String id) { return element(id).getDomProperty("textContent"); }
    private static boolean hidden(String id) { return List.of(element(id).getDomAttribute("class").split("\\s+")).contains("d-none"); }

    private static String label(String locale, String key) {
        return switch (key) {
            case "committed" -> locale.equals("de") ? "Das Portfolio wurde erfolgreich committed." : "Portfolio committed successfully.";
            case "materialized" -> locale.equals("de") ? "Der Branch wurde erfolgreich materialisiert." : "Branch materialized successfully.";
            case "merged" -> locale.equals("de") ? "Die Branches wurden erfolgreich zusammengeführt." : "Branches merged successfully.";
            case "refreshFailed" -> locale.equals("de") ? "Die Portfoliovorschau konnte nicht aktualisiert werden." : "The portfolio preview could not be refreshed.";
            case "sourceTargetDiffer" -> locale.equals("de") ? "Quell- und Zielbranch müssen verschieden sein" : "Source and target branch must differ";
            case "refresh" -> locale.equals("de") ? "Aktualisieren" : "Refresh";
            default -> throw new IllegalArgumentException(key);
        };
    }

    private enum Operation {
        COMMIT("commitForm", "committed"), MATERIALIZATION(null, "materialized"), MERGE("mergeForm", "merged");
        final String form;
        final String success;
        Operation(String form, String success) { this.form = form; this.success = success; }
        Map<String, String> request() {
            return switch (this) {
                case COMMIT -> Map.of("branch", "release", "message", COMMIT_DRAFT.trim());
                case MATERIALIZATION -> Map.of("branch", "review", "expectedHead", "review-head-123456789");
                case MERGE -> Map.of("sourceBranch", "proposal", "targetBranch", "main", "message", MERGE_DRAFT.trim());
            };
        }
    }

    private static final class ReadGate implements AutoCloseable {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch response = new CountDownLatch(1);
        void awaitStarted() throws InterruptedException {
            assertThat(started.await(20, TimeUnit.SECONDS)).as("The real HTTP repository read has started").isTrue();
        }
        void release() { response.countDown(); }
        @Override public void close() { release(); }
    }

    private static final class RecoveryResponses {
        final AtomicInteger readCount = new AtomicInteger();
        final AtomicInteger mutationCount = new AtomicInteger();
        volatile String activeBranch = "main";
        volatile String dsl = "project PORT-42";
        volatile String readFailure;
        volatile String mutationFailure;
        volatile tools.jackson.databind.JsonNode mutationRequest;
        volatile ReadGate readGate;

        ReadGate deferRead() { readGate = new ReadGate(); return readGate; }
        void releaseRead() { if (readGate != null) readGate.release(); }

        Map<String, Object> repository() {
            return Map.of("currentBranch", activeBranch, "headCommit", "baseline-123456789",
                    "branches", List.of("main", "review", "release", "proposal"));
        }

        Map<String, Object> export() {
            return Map.of("activeBranch", "main", "dsl", dsl, "workspaceId", "shared",
                    "projectCount", 1, "requirementCount", 3, "solutionCount", 2, "productCount", 1);
        }
    }

}

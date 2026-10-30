package com.taxonomy;

import com.taxonomy.versioning.service.RequirementHistoryService;
import com.taxonomy.workspace.signing.CommitSignature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.taxonomy.PortfolioClientTestPage.json;
import static org.assertj.core.api.Assertions.assertThat;

/** Real templates and native interactions; only the HTTP data is an authored fixture. */
@Tag("browser")
@Tag("ci-app-shard-browser")
class RequirementLifecycleBrowserIT {
    private static final String CURRENT = "a".repeat(40);
    private static final String HISTORICAL = "b".repeat(40);
    private static final String HISTORY = "/api/architecture/lifecycle/history";
    private static final String PINS = "repositoryId=control-architecture&workspaceId=control-workspace"
            + "&workspaceScopeKey=control-workspace&branch=review%2Fcurrent";
    private static final String OBSERVE_HISTORY = """
            window.lifecycleHistoryResponses=0;
            const realFetch=window.fetch.bind(window);
            window.fetch=function(input,options){
                return realFetch(input,options).then(async response=>{
                    if(new URL(response.url,location.href).pathname.endsWith('/lifecycle/history')){
                        await response.clone().text();window.lifecycleHistoryResponses++;
                    }
                    return response;
                });
            };
            """;
    private static PortfolioClientTestPage page;
    private static WebDriverWait wait;

    @BeforeAll static void openBrowser(@TempDir Path downloads) throws Exception {
        page = new PortfolioClientTestPage(downloads);
        wait = new WebDriverWait(page.driver(), Duration.ofSeconds(20));
    }

    @AfterAll static void closeBrowser() { if (page != null) page.close(); }

    @ParameterizedTest @ValueSource(strings = {"en", "de"})
    void initialArchitectureLoadCannotOrphanTheRequirementSubmit(String locale) throws Exception {
        prepareProjects(1366);
        var seen = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var overviewReads = new AtomicInteger();
        var accepted = new AtomicBoolean();
        String text = "Keep the same explicit requirement submission while its architecture context loads.";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        var requirement = Map.of("id", 21, "projectId", 1, "requirementKey", "REQ-NEW", "title", "New scoped requirement",
                "status", "DRAFT", "reviewStatus", "PROPOSED", "currentVersion",
                Map.of("id", 121, "versionNumber", 1, "text", text, "contentHash", hash));
        page.route("GET", "/api/architecture/lifecycle", request -> {
            if (overviewReads.incrementAndGet() == 1) {
                seen.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) return json(503, Map.of("detail", "Initial context was not released"));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return json(503, Map.of("detail", "Initial context interrupted"));
                }
            }
            return json(200, overview(false));
        });
        page.route("POST", "/api/architecture/lifecycle/intake-review", request -> json(200,
                Map.of("status", "COMPLETE", "inputHash", hash, "scope", overview(false).get("context"), "candidates", List.of())));
        page.route("POST", "/api/architecture/editor/requirements", request -> {
            accepted.set(true);
            return json(201, Map.of("projectId", 1, "requirementId", 21, "requirement", requirement,
                    "publication", Map.of("state", "CHECKPOINTED", "context", overview(false).get("context"))));
        });
        page.route("GET", "/api/projects/1/portfolio", request -> json(200,
                Map.of("project", project(1), "metrics", Map.of(), "requirements", accepted.get() ? List.of(requirement) : List.of(),
                        "solutions", List.of(), "conflicts", List.of(), "taxonomyNodes", List.of())));
        try {
            // An unpinned project page becomes usable before its independent architecture read finishes.
            page.open("projects", "", "/projects", locale,
                    "localStorage.clear();sessionStorage.clear();history.replaceState(null,'','?lang=" + locale + "&projectId=1');");
            assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
            wait.until(ExpectedConditions.textToBe(By.id("selectedProjectKey"), "PROJECT"));
            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("portfolioBusy")));
            click(By.cssSelector("[data-bs-target='#requirementModal']"));
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("requirementModal")));
            page.driver().findElement(By.id("requirementKey")).sendKeys("REQ-NEW");
            page.driver().findElement(By.id("requirementTitle")).sendKeys("New scoped requirement");
            page.driver().findElement(By.id("requirementText")).sendKeys(text);
            click(By.cssSelector("#requirementForm button[type='submit']"));
            String loading = locale.equals("de") ? "Architekturkontext wird" : "Loading the architecture context";
            wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector("#requirementForm .requirement-intake-review"), loading));
            assertThat(page.requests()).filteredOn(request -> request.method().equals("POST")
                    && (request.path().endsWith("/intake-review") || request.path().endsWith("/editor/requirements"))).isEmpty();
            assertThat(page.driver().findElements(By.cssSelector("#requirementForm [data-intake-choice]"))).isEmpty();
            assertThat(page.driver().findElement(By.id("requirementText")).getDomProperty("value")).isEqualTo(text);

            release.countDown();
            click(By.cssSelector("#requirementForm > .requirement-intake-review > [data-intake-choice='NEW']"));
            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("requirementModal")));
            wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector("#requirementsTable tbody"), "REQ-NEW"));
            var reviews = page.requests().stream().filter(request -> request.path().endsWith("/intake-review")).toList();
            assertThat(reviews).hasSize(1);
            assertThat(query(reviews.getFirst())).containsEntry("repositoryId", "control-architecture")
                    .containsEntry("workspaceId", "control-workspace").containsEntry("branch", "review/current");
            var writes = page.requests().stream().filter(request -> request.method().equals("POST")
                    && request.path().endsWith("/editor/requirements")).toList();
            assertThat(writes).hasSize(1);
            assertThat(writes.getFirst().header("If-Match")).isEqualTo("\"workspace-revision-4\"");
            var command = tools.jackson.databind.json.JsonMapper.builder().build().readTree(writes.getFirst().body());
            assertThat(command.path("context").path("workspaceScopeKey").asString()).isEqualTo("control-workspace");
            assertThat(command.path("requirement").path("text").asString()).isEqualTo(text);
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"en", "de"})
    void historicalTextVersionDoesNotRelabelTheCurrentRequirement(String locale) {
        new PortfolioRequirementFixture(page);
        page.driver().manage().window().setSize(new Dimension(1366, 900));
        page.route("GET", "/api/architecture/lifecycle", request ->
                json(200, overview(query(request).containsKey("commit"))));
        page.open("requirement-detail", "", "/projects/1/requirements/2", locale,
                bootstrap(locale, "&commit=" + HISTORICAL));
        wait.until(ExpectedConditions.textToBe(By.id("requirementHeading"), "Pump control"));
        wait.until(ignored -> Boolean.TRUE.equals(page.driver().executeScript("""
                return window.TaxonomyArchitectureLifecycle?.state()?.source==='GIT_CHECKPOINT'
                    && window.TaxonomyAppChrome?.getContext().requirement?.version===2;
                """)));
        assertThat(page.driver().findElement(By.id("applicationCurrentContext")).getText())
                .contains("REQ-2", "Version 2");
        var current = page.driver().findElement(By.cssSelector("#architectureLifecycle [data-lifecycle-current]"));
        assertThat(current.isDisplayed()).as("Recovery to the current stand remains visible while details are closed").isTrue();
        expand();
        var row = page.driver().findElement(By.cssSelector("#architectureLifecycle [data-requirement-key='REQ-2']"));
        assertThat(row.findElement(By.cssSelector("[data-lifecycle-version]")).getText()).isEqualTo("1");
        assertThat(row.findElement(By.cssSelector("[data-lifecycle-action='review']")).isEnabled()).isFalse();
        assertThat(page.driver().findElement(By.id("currentText")).getText()).contains("Stored text");
        click(By.cssSelector("#architectureLifecycle [data-lifecycle-current]"));
        wait.until(ignored -> Boolean.TRUE.equals(page.driver().executeScript(
                "return TaxonomyArchitectureLifecycle.state()?.source==='WORKSPACE_AND_PORTFOLIO';")));
        assertThat(page.driver().getCurrentUrl()).doesNotContain("commit=");
        assertThat(page.driver().findElement(By.cssSelector("#architectureLifecycle [data-requirement-key='REQ-2'] [data-lifecycle-version]")).getText())
                .isEqualTo("2");
        assertThat(page.driver().findElement(By.id("applicationCurrentContext")).getText()).contains("Version 2");
    }

    @ParameterizedTest @ValueSource(strings = {"en", "de"})
    void lateHistoryCannotAppearUnderAnotherSelectedProject(String locale) throws Exception {
        prepareProjects(1366);
        var seen = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        page.route("GET", HISTORY, request -> {
            seen.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) return json(503, Map.of("detail", "History release was not received"));
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); return json(503, Map.of()); }
            return json(200, history("Project one history\n\nOnly belongs to the previous project."));
        });
        openProjects(locale);
        expand();
        try {
            click(By.cssSelector("#architectureLifecycle [data-requirement-key='REQ-12'] [data-lifecycle-action='history']"));
            assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
            click(By.cssSelector(".project-select[data-project-id='2']"));
            wait.until(ExpectedConditions.textToBe(By.id("selectedProjectKey"), "OTHER"));
            wait.until(ignored -> lifecycleRows().size() == 7);
            release.countDown();
            wait.until(ignored -> ((Number) page.driver().executeScript("return window.lifecycleHistoryResponses;")).intValue() == 1);
            assertThat(page.driver().findElement(By.cssSelector("[data-lifecycle-history-list]")).isDisplayed()).isFalse();
            assertThat(page.driver().findElement(By.id("architectureLifecycle")).getText()).doesNotContain("Project one history");
            assertThat(page.requests()).filteredOn(request -> request.path().equals(HISTORY))
                    .singleElement().satisfies(request -> assertThat(query(request))
                            .containsEntry("projectKey", "PROJECT").containsEntry("requirementKey", "REQ-12"));
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(strings = {"en", "de"})
    void twelfthRequirementHasItsOwnHistoryAndReadableCommitSubject(String locale) {
        prepareProjects(1366);
        page.route("GET", HISTORY, request -> json(200,
                history("Review requirement twelve\n\nRetain this complete explanation.\nSecond detail line.")));
        openProjects(locale);
        expand();
        click(By.cssSelector("#architectureLifecycle [data-requirement-key='REQ-12'] [data-lifecycle-action='history']"));
        var link = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[data-lifecycle-history-list] li > a")));
        assertThat(link.getText()).isEqualTo("Review requirement twelve");
        assertThat(link.getDomAttribute("href")).contains("repositoryId=control-architecture", "workspaceScopeKey=control-workspace",
                "workspaceId=control-workspace", "branch=review%2Fcurrent", "commit=" + CURRENT, "lang=" + locale);
        var details = page.driver().findElement(By.cssSelector("[data-lifecycle-history-list] li > details"));
        assertThat(details.getDomProperty("open")).isEqualTo("false");
        details.findElement(By.tagName("summary")).sendKeys(Keys.ENTER);
        assertThat(details.getText()).contains("Retain this complete explanation.", "Second detail line.", HISTORICAL, "UNSIGNED");
        assertThat(page.requests()).filteredOn(request -> request.path().equals(HISTORY))
                .singleElement().satisfies(request -> assertThat(query(request))
                        .containsEntry("projectKey", "PROJECT").containsEntry("requirementKey", "REQ-12")
                        .containsEntry("workspaceScopeKey", "control-workspace").containsEntry("branch", "review/current"));
    }

    @ParameterizedTest @ValueSource(ints = {1366, 390})
    void summaryCountsTheTwelveSelectedProjectRequirementsRatherThanAllNineteen(int width) {
        prepareProjects(width);
        openProjects("en");
        var summary = page.driver().findElement(By.cssSelector("#architectureLifecycle > details > summary"));
        assertThat(summary.getText()).startsWith("12 requirements").doesNotContain("19");
        summary.sendKeys(Keys.ENTER);
        assertThat(lifecycleRows()).hasSize(12);
        assertThat(lifecycleRows()).extracting(row -> row.getDomAttribute("data-requirement-key"))
                .containsExactly("REQ-1", "REQ-2", "REQ-3", "REQ-4", "REQ-5", "REQ-6", "REQ-7", "REQ-8", "REQ-9", "REQ-10", "REQ-11", "REQ-12");
        assertThat(page.driver().executeScript("return document.documentElement.scrollWidth<=innerWidth+1;")).isEqualTo(true);
        click(By.cssSelector(".project-select[data-project-id='2']"));
        wait.until(ExpectedConditions.textToBe(By.id("selectedProjectKey"), "OTHER"));
        wait.until(ignored -> lifecycleRows().size() == 7);
        assertThat(summary.getText()).startsWith("7 requirements");
    }

    private static void prepareProjects(int width) {
        page.clearRoutes(); page.clearRequests();
        page.driver().manage().window().setSize(new Dimension(width, 900));
        page.route("GET", "/api/projects", request -> json(200, List.of(project(1), project(2))));
        page.route("GET", "/api/products", request -> json(200, List.of()));
        page.route("GET", "/api/account/me", request -> json(200, Map.of("username", "qa-client", "architectureMutationAllowed", true)));
        page.route("GET", "/api/architecture/lifecycle", request -> json(200, overview(false)));
        for (int id : List.of(1, 2)) {
            page.route("GET", "/api/projects/" + id, request -> json(200, project(id)));
            page.route("GET", "/api/projects/" + id + "/analysis-jobs", request -> json(200, List.of()));
            page.route("GET", "/api/projects/" + id + "/portfolio", request -> json(200,
                    Map.of("project", project(id), "metrics", Map.of(), "requirements", List.of(), "solutions", List.of(),
                            "conflicts", List.of(), "taxonomyNodes", List.of())));
        }
    }

    private static void openProjects(String locale) {
        page.open("projects", "", "/projects", locale, bootstrap(locale, "&projectId=1") + OBSERVE_HISTORY);
        wait.until(ExpectedConditions.textToBe(By.id("selectedProjectKey"), "PROJECT"));
        wait.until(ignored -> lifecycleRows().size() == 12);
        var context = page.driver().findElement(By.id("applicationContextDisclosure"));
        if (!"true".equals(context.getDomProperty("open"))) {
            page.driver().findElement(By.id("applicationContextSummary")).sendKeys(Keys.ENTER);
        }
    }

    private static String bootstrap(String locale, String additional) {
        return "localStorage.clear();sessionStorage.clear();history.replaceState(null,'','?lang=" + locale + "&" + PINS + additional + "');";
    }

    private static Map<String, Object> project(int id) {
        return Map.of("id", id, "projectKey", id == 1 ? "PROJECT" : "OTHER", "title", id == 1 ? "Control system" : "Other system",
                "status", "ACTIVE", "requirementCount", id == 1 ? 12 : 7, "solutionCount", 0);
    }

    private static Map<String, Object> overview(boolean historical) {
        var requirements = IntStream.rangeClosed(1, 19).mapToObj(number -> Map.<String, Object>ofEntries(
                Map.entry("id", "control-requirement-" + number), Map.entry("projectId", number <= 12 ? 1 : 2),
                Map.entry("projectKey", number <= 12 ? "PROJECT" : "OTHER"), Map.entry("requirementId", number),
                Map.entry("requirementKey", "REQ-" + number), Map.entry("title", "Control requirement " + number),
                Map.entry("version", historical ? 1 : 2), Map.entry("versionId", number == 2 ? (historical ? 11 : 12) : 1000 + number),
                Map.entry("contentHash", "a".repeat(64)), Map.entry("text", "Authored requirement " + number),
                Map.entry("realization", Map.of("state", "OPEN")), Map.entry("contributors", List.of()), Map.entry("links", List.of()))).toList();
        return Map.of("context", Map.of("repositoryId", "control-architecture", "workspaceScopeKey", "control-workspace",
                        "branch", "review/current", "actor", "qa-client", "writeMode", "WORKSPACE", "revision", historical ? 1 : 4,
                        "commit", historical ? HISTORICAL : CURRENT), "architectureTitle", "Control architecture",
                "source", historical ? "GIT_CHECKPOINT" : "WORKSPACE_AND_PORTFOLIO", "projectionState", historical ? "HISTORICAL" : "READY",
                "publicationState", "CHECKPOINTED", "mayEdit", !historical, "projects", List.of(project(1), project(2)),
                "dependencies", List.of(), "requirements", requirements);
    }

    private static RequirementHistoryService.Page history(String message) {
        var change = new RequirementHistoryService.Change("PROJECT", "REQ-12", "control-requirement-12",
                List.of(RequirementHistoryService.ChangeKind.REQUIREMENT_CHANGED), "1", "2", "OPEN", "OPEN");
        var entry = new RequirementHistoryService.Entry(CURRENT, List.of(HISTORICAL), "QA reviewer",
                Instant.parse("2026-10-07T09:00:00Z"), message, CommitSignature.unsigned(), List.of(change));
        return new RequirementHistoryService.Page(CURRENT, List.of(entry), 0, null, 1,
                RequirementHistoryService.Coverage.COMPLETE, "LIVE_GIT");
    }

    private static Map<String, String> query(PortfolioClientTestPage.Request request) {
        var values = new LinkedHashMap<String, String>();
        if (request.query() != null) for (String entry : request.query().split("&")) {
            var pair = entry.split("=", 2);
            values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.length == 1 ? "" : pair[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private static List<WebElement> lifecycleRows() {
        return page.driver().findElements(By.cssSelector("#architectureLifecycle tbody tr"));
    }

    private static void expand() { page.driver().findElement(By.cssSelector("#architectureLifecycle > details > summary")).sendKeys(Keys.ENTER); }

    private static void click(By selector) {
        var element = wait.until(ExpectedConditions.elementToBeClickable(selector));
        page.driver().executeScript("arguments[0].scrollIntoView({block:'center',behavior:'instant'});", element);
        element.click();
    }
}

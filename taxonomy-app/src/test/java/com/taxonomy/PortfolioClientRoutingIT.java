package com.taxonomy;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.portfolio.dto.PortfolioDtos;
import com.taxonomy.portfolio.model.PortfolioTypes;
import com.taxonomy.shared.config.I18nConfig;
import com.taxonomy.shared.controller.I18nApiController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maven/Failsafe-owned routing contracts against actual browser modules,
 * Thymeleaf templates, native DOM events and a real HTTP fixture server.
 */
@Tag("browser")
@Tag("ui-acceptance")
class PortfolioClientRoutingIT {
    private static final List<String> PREFIXES = List.of("", "/taxonomy", "/teams/blue/taxonomy");
    private static final List<PageContract> PAGES = List.of(
            new PageContract("requirement-detail", "/requirements/7", "requirementHeading", "detailError"),
            new PageContract("portfolio-reports", "/reports", "reportsProject", "reportsError"),
            new PageContract("portfolio-versioning", "/versioning", "versioningProject", "versioningError"),
            new PageContract("portfolio-matrices", "/matrices", "matrixProject", "matrixError"),
            new PageContract("portfolio-import", "/import", "importProject", "importError"));
    private static final List<String> BRANCHES = List.of("main", "review/portfolio", "release/2026.10");
    private static final String JOB_STORAGE = "taxonomy.portfolio.analysisJobs.v2";
    private static final Instant LABEL_TIME = Instant.parse("2026-10-07T09:00:00Z");
    private static final String LABEL_TITLE = "ACTIVE <pump> & \"Planning\"";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> JOB = Map.of("id", "job-7", "status", "SUCCESS", "items", List.of());
    private static final String OBSERVE_FETCH = """
            window.portfolioContractRequests = [];
            const observedFetch = window.fetch.bind(window);
            window.fetch = function (input, init) {
                window.portfolioContractRequests.push({
                    url: input instanceof Request ? input.url : String(input),
                    method: String(init?.method || (input instanceof Request ? input.method : 'GET')).toUpperCase(),
                    credentials: init?.credentials || (input instanceof Request ? input.credentials : '')
                });
                return observedFetch(input, init);
            };
            """;

    @TempDir static Path downloads;
    private static PortfolioClientTestPage page;
    private static RemoteWebDriver driver;
    private static WebDriverWait wait;
    private static HttpServer otherApplication;
    private static final AtomicInteger otherApplicationRequests = new AtomicInteger();
    private static String otherOrigin;

    @BeforeAll static void startBrowserAndOtherApplication() throws Exception {
        // A second local origin proves that the production fetch adapter never
        // intercepts another application's 202, without contacting the Internet.
        otherApplication = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        otherApplication.createContext("/", exchange -> {
            otherApplicationRequests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = JSON.writeValueAsBytes(JOB);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "Location");
            exchange.getResponseHeaders().set("Location", "/taxonomy/api/projects/42/analysis-jobs/job-7");
            exchange.sendResponseHeaders(202, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        otherApplication.start();
        String runtime = System.getProperty("taxonomy.test.browser", "");
        boolean local = runtime.equals("local") || (runtime.isBlank()
                && !System.getProperty("webdriver.chrome.driver", "").isBlank());
        if (!local) Testcontainers.exposeHostPorts(otherApplication.getAddress().getPort());
        page = new PortfolioClientTestPage(downloads);
        driver = page.driver();
        driver.manage().window().setSize(new Dimension(1440, 1000));
        wait = new WebDriverWait(driver, Duration.ofSeconds(20));
        otherOrigin = "http://" + URI.create(page.origin()).getHost() + ":" + otherApplication.getAddress().getPort();
    }

    @AfterAll static void stopBrowserAndOtherApplication() throws Exception {
        try { if (page != null) page.close(); }
        finally { if (otherApplication != null) otherApplication.stop(0); }
    }

    @Test void everyStandalonePageLoadsTheSharedUrlBootstrapBeforeFeatureCode() throws Exception {
        Map<String, String> features = new LinkedHashMap<>();
        features.put("projects", "taxonomy-portfolio");
        PAGES.forEach(contract -> features.put(contract.template(), contract.template()));
        for (var entry : features.entrySet()) {
            String html;
            try (var source = new ClassPathResource("templates/" + entry.getKey() + ".html").getInputStream()) {
                html = new String(source.readAllBytes(), StandardCharsets.UTF_8);
            }
            int bootstrap = html.indexOf("@{/js/taxonomy-i18n.js}");
            assertThat(bootstrap).as("%s uses the existing URL bootstrap", entry.getKey()).isNotNegative();
            assertThat(bootstrap).isLessThan(html.indexOf("@{/js/api/portfolio-api.js}"));
            assertThat(bootstrap).isLessThan(html.indexOf("@{/js/portfolio/" + entry.getValue() + ".js}"));
        }
    }

    @ParameterizedTest(name = "{1} initializes its project under [{0}]")
    @MethodSource("prefixesAndPages")
    void standalonePagesInitializeUnderTheirMountedContext(String prefix, PageContract contract) {
        installRoutes(prefix);
        open(contract.template(), prefix, "/projects/42" + contract.suffix(), "");
        if (contract.template().equals("requirement-detail")) {
            wait.until(ignored -> text("requirementKey").equals("REQ-7")
                    && text(contract.heading()).equals(requirement().get("title")) && hidden("detailBusy"));
        } else {
            wait.until(ignored -> text(contract.heading()).contains("P-42"));
        }
        assertThat(text(contract.error())).as("The initializer completes without a hidden page error").isEmpty();
        assertThat(requests("GET", prefix + "/api/projects/42")).hasSize(1);
        assertThat(href("portfolioBack")).isEqualTo(prefix + "/projects?lang=de");
        if (contract.template().equals("requirement-detail")) {
            assertThat(href("matrixLink")).isEqualTo(prefix + "/projects/42/matrices?lang=de");
        }
        if (contract.template().equals("portfolio-reports")) {
            assertThat(href("versioningLink")).isEqualTo(prefix + "/projects/42/versioning?lang=de");
        }
        if (contract.template().equals("portfolio-versioning")) {
            assertThat(href("reportsLink")).isEqualTo(prefix + "/projects/42/reports?lang=de");
        }
    }

    @ParameterizedTest(name = "RepositoryState string branches render at [{0}]")
    @ValueSource(strings = {"", "/taxonomy", "/teams/blue/taxonomy"})
    void versioningRendersRealRepositoryStateBranchStrings(String prefix) {
        installRoutes(prefix);
        open("portfolio-versioning", prefix, "/projects/42/versioning", "");
        wait.until(ignored -> text("versioningProject").contains("P-42") && hidden("versioningBusy"));
        assertThat(text("versioningError")).isEmpty();
        assertThat(requests("GET", prefix + "/api/git/state")).hasSize(1);
        for (String id : List.of("commitBranch", "materializeBranch", "mergeSource", "mergeTarget")) {
            assertThat(new Select(element(id)).getOptions())
                    .extracting(option -> List.of(option.getText(), option.getDomProperty("value")))
                    .as("%s exposes every backend string as its visible label and submitted value", id)
                    .containsExactlyElementsOf(BRANCHES.stream().map(branch -> List.of(branch, branch)).toList());
        }
        assertThat(text("activeBranch")).isEqualTo("main");
        assertThat(href("reportsLink")).isEqualTo(prefix + "/projects/42/reports?lang=de");
    }

    @ParameterizedTest(name = "report downloads and matrix detail navigation retain [{0}]")
    @ValueSource(strings = {"", "/taxonomy", "/teams/blue/taxonomy"})
    void reportDownloadAndMatrixDetailNavigationRetainContext(String prefix) throws Exception {
        installRoutes(prefix);
        String filename = "portfolio-contract-" + Integer.toUnsignedString(prefix.hashCode()) + ".csv";
        page.route("GET", prefix + "/api/projects/42/reports/csv", request ->
                new PortfolioClientTestPage.Response(200, "text/csv", "requirement,coverage\nREQ-7,80\n")
                        .withHeader("Content-Disposition", "attachment; filename=" + filename));
        page.route("GET", prefix + "/api/projects/42/reports/html", request ->
                new PortfolioClientTestPage.Response(200, "text/html", "<!doctype html><title>Reviewed report</title><p>REQ-7</p>"));
        open("portfolio-reports", prefix, "/projects/42/reports", "");
        wait.until(ignored -> text("reportsProject").contains("P-42") && hidden("reportsBusy"));
        new Select(element("reportScope")).selectByValue("requirement");
        new Select(element("reportRequirement")).selectByValue("7");
        click(By.cssSelector(".report-download[data-format='csv']"));
        wait.until(ignored -> !requests("GET", prefix + "/api/projects/42/reports/csv").isEmpty());
        assertThat(requests("GET", prefix + "/api/projects/42/reports/csv").getFirst().query())
                .isEqualTo("requirementId=7&matrix=taxonomy");
        wait.until(ignored -> page.browser().downloadedFiles().contains(filename));
        assertThat(Files.readString(page.browser().download(filename))).contains("REQ-7,80");
        assertThat(driver.getCurrentUrl()).isEqualTo(page.origin() + prefix + "/projects/42/reports?lang=de");
        click(By.id("previewReport"));
        wait.until(ignored -> element("printReport").isEnabled());
        assertThat(requests("GET", prefix + "/api/projects/42/reports/html")).hasSize(1);
        assertThat(requests("GET", prefix + "/api/projects/42/reports/html").getFirst().query()).isEqualTo("requirementId=7");
        assertSameOriginCredentials(prefix + "/api/projects/42/reports/html?requirementId=7");

        open("portfolio-matrices", prefix, "/projects/42/matrices", "");
        wait.until(ignored -> text("matrixProject").contains("P-42") && hidden("matrixBusy"));
        click(By.cssSelector("#taxonomyMatrix table .matrix-drilldown[data-row='REQ-7'][data-column='NODE-1']"));
        WebElement link = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#cellDetailBody a")));
        assertThat(link.getDomAttribute("href")).isEqualTo(prefix + "/projects/42/requirements/7?lang=de");
    }

    @ParameterizedTest(name = "selected-project tools and requirement links retain [{0}]")
    @ValueSource(strings = {"", "/taxonomy", "/teams/blue/taxonomy"})
    void selectedProjectToolsAndRequirementLinksKeepContext(String prefix) {
        installRoutes(prefix);
        page.route("GET", prefix + "/api/projects", request -> PortfolioClientTestPage.json(200, List.of()));
        open("projects", prefix, "/projects", "");
        wait.until(ignored -> hidden("portfolioBusy"));
        assertThat(hidden("projectTools")).isTrue();
        page.route("GET", prefix + "/api/projects", request -> PortfolioClientTestPage.json(200, List.of(project(42), project(63))));
        open("projects", prefix, "/projects", "");
        for (int id : List.of(42, 63)) {
            if (id == 63) click(By.cssSelector(".project-select[data-project-id='63']"));
            wait.until(ignored -> text("selectedProjectKey").equals("P-" + id) && hidden("portfolioBusy"));
            assertThat(hidden("projectTools")).isFalse();
            Map.of("Import", "import", "Matrices", "matrices", "Reports", "reports", "Versioning", "versioning")
                    .forEach((name, path) -> assertThat(href("project" + name + "Link"))
                            .isEqualTo(prefix + "/projects/" + id + "/" + path + "?lang=de"));
            WebElement row = driver.findElement(By.cssSelector("#requirementsTable tbody tr"));
            WebElement link = row.findElement(By.tagName("a"));
            assertThat(link.getDomAttribute("href")).isEqualTo(prefix + "/projects/" + id + "/requirements/7?lang=de");
            assertThat(link.getText()).isEqualTo("Stop <pump> & \"verify\"");
            assertThat(link.getDomAttribute("aria-label")).contains("REQ-7");
            assertThat(link.findElements(By.tagName("pump"))).isEmpty();
            for (String action : List.of("requirement-analyze", "requirement-snapshots", "requirement-confirm")) {
                assertThat(row.findElements(By.className(action))).as("%s remains available", action).hasSize(1);
            }
        }
        // A failed project switch must clear the previously rendered targets
        // through the actual project-select UI.
        page.route("GET", prefix + "/api/projects/42/portfolio", request ->
                PortfolioClientTestPage.json(503, Map.of("detail", "Project read unavailable")));
        click(By.cssSelector(".project-select[data-project-id='42']"));
        wait.until(ignored -> text("portfolioError").contains("Project read unavailable") && hidden("portfolioBusy"));
        assertThat(hidden("projectTools")).isTrue();
        assertThat(href("projectImportLink")).as("A stale selected-project target is removed").isNull();
    }

    @ParameterizedTest(name = "import completion stores its job and returns to [{0}]")
    @ValueSource(strings = {"", "/taxonomy", "/teams/blue/taxonomy"})
    void importCompletionStoresAUsableAnalysisJobAndReturnsToContext(String prefix) {
        installRoutes(prefix);
        prepareImport(prefix, "/api/projects/42/analysis-jobs/job-7");
        click(By.id("confirmImport"));
        wait.until(ignored -> !hidden("importInfo") && hidden("importBusy"));
        assertThat(text("importError")).isEmpty();
        JsonNode stored = readJobs();
        assertThat(stored.size()).isEqualTo(1);
        assertThat(stored.get(0).path("url").asText()).isEqualTo(page.origin() + prefix + "/api/projects/42/analysis-jobs/job-7");
        assertThat(stored.get(0).path("projectId").asInt()).isEqualTo(42);
        awaitImportReturn(prefix);
    }

    @ParameterizedTest(name = "analysis-job registration and real polling stay within [{0}]")
    @ValueSource(strings = {"", "/taxonomy", "/teams/blue/taxonomy"})
    void analysisJobRegistrationAndPollingStayInsideTheirApplication(String prefix) {
        installRoutes(prefix);
        openJobPage(prefix, "");
        assertThat(registerJob("/api/projects/42/analysis-jobs/job-7", runningJob())).isTrue();
        assertThat(readJobs().get(0).path("url").asText()).isEqualTo(page.origin() + prefix + "/api/projects/42/analysis-jobs/job-7");
        assertThat(readJobs().get(0).path("projectId").asInt()).isEqualTo(42);
        wait.until(ignored -> readJobs().get(0).path("job").path("status").asText().equals("SUCCESS"));
        assertThat(requests("GET", prefix + "/api/projects/42/analysis-jobs/job-7")).hasSize(1);
        assertSameOriginCredentials(page.origin() + prefix + "/api/projects/42/analysis-jobs/job-7");
    }

    @Test void routeMatchingRemovesOnlyTheExactMountedPrefix() {
        for (PageContract contract : PAGES) {
            for (String path : List.of("/projects/42" + contract.suffix(),
                    "/taxonomy-other/projects/42" + contract.suffix(),
                    "/elsewhere/taxonomy/projects/42" + contract.suffix(),
                    "/taxonomy/projects/42" + contract.suffix() + "/extra")) {
                installRoutes("/taxonomy");
                page.openAt(contract.template(), "/taxonomy", path, "de", "localStorage.clear();" + OBSERVE_FETCH);
                assertThat(driver.executeScript("return window.TaxonomyI18n.getBasePath();")).isEqualTo("/taxonomy");
                awaitTranslationContinuations();
                assertThat(driver.executeScript("""
                        return window.portfolioContractRequests.filter(request =>
                            request.url === '/taxonomy/api/projects/42');
                        """))
                        .as("%s cannot initiate its project fetch for %s", contract.template(), path)
                        .isEqualTo(List.of());
                assertThat(requests("GET", "/taxonomy/api/projects/42"))
                        .as("%s must not initialize for %s", contract.template(), path).isEmpty();
                if (!contract.template().equals("requirement-detail")) assertThat(text(contract.heading())).isBlank();
            }
        }
    }

    @Test void backgroundSubmissionsRecognizeLegacyRootAndMountedPathsOnly() {
        String prefix = "/taxonomy";
        installRoutes(prefix);
        List<String> paths = List.of("/api/projects/42/analyses", "/taxonomy/api/projects/42/analyses",
                "/taxonomy/api/projects/42/requirements/7/analyses");
        for (String path : List.of(prefix + "/api/projects/42/analyses", prefix + "/api/projects/42/requirements/7/analyses",
                prefix + "/taxonomy-other/api/projects/42/analyses")) {
            page.route("POST", path, request -> PortfolioClientTestPage.json(202, JOB)
                    .withHeader("Location", prefix + "/api/projects/42/analysis-jobs/job-7"));
        }
        openJobPage(prefix, "");
        for (String path : paths) {
            assertThat(postStatus(path)).as("Accepted same-application work reaches the nonblocking adapter").isEqualTo(200L);
            assertThat(readJobs().get(0).path("projectId").asInt()).isEqualTo(42);
        }
        int previous = otherApplicationRequests.get();
        assertThat(postStatus(otherOrigin + "/taxonomy/api/projects/42/analyses")).isEqualTo(202L);
        assertThat(otherApplicationRequests.get()).isEqualTo(previous + 1);
        assertThat(postStatus("/taxonomy-other/api/projects/42/analyses")).isEqualTo(202L);
    }

    @Test void externalSiblingAndMalformedJobLocationsAreNotStoredOrPolled() {
        String prefix = "/taxonomy";
        installRoutes(prefix);
        openJobPage(prefix, "");
        int externalRequests = otherApplicationRequests.get();
        for (String location : invalidJobLocations()) {
            assertThat(registerJob(location, JOB)).as("Reject job location %s", location).isFalse();
        }
        driver.executeAsyncScript("window.setTimeout(arguments[arguments.length - 1], 1700);");
        assertThat(readJobs().size()).isZero();
        assertThat(page.requests().stream().filter(request -> request.path().contains("/analysis-jobs/"))).isEmpty();
        assertThat(otherApplicationRequests.get()).as("Rejected locations cannot schedule a background poll")
                .isEqualTo(externalRequests);

        // The importer must apply the same URL boundary plus this page's own
        // project identity. Exercise its real confirm control and Location header.
        for (String location : Stream.concat(invalidJobLocations().stream(),
                Stream.of("/taxonomy/api/projects/63/analysis-jobs/job-7")).toList()) {
            installRoutes(prefix);
            prepareImport(prefix, location);
            click(By.id("confirmImport"));
            wait.until(ignored -> !hidden("importInfo") && hidden("importBusy"));
            assertThat(readJobs().size()).as("An imported job must belong to this project and application: %s", location).isZero();
            // Keep this fixture's review saved before opening the next case.
            // The redirect itself is verified separately by the three import cases.
            driver.executeScript("localStorage.setItem(arguments[0], arguments[1]);",
                    "taxonomy.portfolio.importDraft.42", PortfolioClientTestPage.json(importDraft()));
        }
    }

    @Test void restoredJobHistoryValidatesApplicationAndRecoversProjectIdentityBeforePolling() {
        String prefix = "/taxonomy";
        installRoutes(prefix);
        String valid = page.origin() + prefix + "/api/projects/42/analysis-jobs/job-7";
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("url", valid); entry.put("projectId", null); entry.put("job", runningJob());
        String stored = PortfolioClientTestPage.json(List.of(entry,
                Map.of("url", otherOrigin + prefix + "/api/projects/42/analysis-jobs/job-7", "job", runningJob())));
        int externalRequests = otherApplicationRequests.get();
        openJobPage(prefix, "localStorage.setItem(" + PortfolioClientTestPage.json(JOB_STORAGE) + ", "
                + PortfolioClientTestPage.json(stored) + ");");
        wait.until(ignored -> readJobs().size() == 1 && readJobs().get(0).path("job").path("status").asText().equals("SUCCESS"));
        assertThat(readJobs().get(0).path("projectId").asInt()).isEqualTo(42);
        assertThat(readJobs().get(0).path("url").asText()).isEqualTo(valid);
        assertThat(requests("GET", prefix + "/api/projects/42/analysis-jobs/job-7")).hasSize(1);
        assertThat(otherApplicationRequests.get()).as("Invalid restored history cannot poll another origin").isEqualTo(externalRequests);
    }

    @Test void jobProjectIdentityComesFromTheResourceAfterTheExactApplicationPrefix() {
        String prefix = "/api/projects/99/analysis-jobs/tenant";
        installRoutes(prefix);
        openJobPage(prefix, "");
        assertThat(registerJob("/api/projects/42/analysis-jobs/job-7", JOB)).isTrue();
        assertThat(readJobs().get(0).path("projectId").asInt()).isEqualTo(42);
    }

    @Test void projectNavigationUsesOneCompactLabelledToolbarDisclosure() {
        installRoutes("");
        page.route("GET", "/api/projects", request -> PortfolioClientTestPage.json(200, List.of()));
        open("projects", "", "/projects", "");
        wait.until(ignored -> hidden("portfolioBusy"));
        assertThat(hidden("projectTools")).isTrue();
        assertThat(element("projectTools").getDomAttribute("class")).contains("dropdown");
        assertThat(element("projectToolsToggle").getDomAttribute("data-bs-toggle")).isEqualTo("dropdown");
        assertThat(driver.findElements(By.cssSelector("#projectTools .dropdown-menu"))).hasSize(1);
        assertThat(driver.findElement(By.cssSelector("#projectTools .dropdown-menu")).getDomAttribute("aria-labelledby"))
                .isEqualTo("projectToolsToggle");
        for (String name : List.of("Import", "Matrices", "Reports", "Versioning")) {
            assertThat(driver.findElements(By.cssSelector("#projectTools #project" + name + "Link"))).hasSize(1);
        }
    }

    @ParameterizedTest(name = "Project counts use localized singular/plural labels [{0}]")
    @MethodSource("projectCountLabels")
    void projectCountsUseLocalizedSingularAndPlural(String locale, List<String> expected) {
        String prefix = "/taxonomy";
        installRoutes(prefix);
        var projects = List.of(
                labelProject(40, PortfolioTypes.ProjectStatus.ACTIVE, 0, 0),
                labelProject(41, PortfolioTypes.ProjectStatus.ACTIVE, 1, 2),
                labelProject(42, PortfolioTypes.ProjectStatus.ACTIVE, 2, 1));
        page.route("GET", prefix + "/api/projects", request -> PortfolioClientTestPage.json(200, projects));
        for (var project : projects) {
            page.route("GET", prefix + "/api/projects/" + project.id() + "/portfolio", request ->
                    PortfolioClientTestPage.json(200, labelPortfolio(project, List.of(), List.of(), List.of())));
            page.route("GET", prefix + "/api/projects/" + project.id() + "/analysis-jobs", request ->
                    PortfolioClientTestPage.json(200, List.of()));
        }
        page.open("projects", prefix, "/projects", locale, "localStorage.clear();\n" + OBSERVE_FETCH);
        wait.until(ignored -> text("selectedProjectKey").equals("P-40") && hidden("portfolioBusy"));
        assertThat(driver.findElements(By.cssSelector("#projectList .project-select > .small.opacity-75")))
                .extracting(WebElement::getText)
                .as("Counts preserve noun capitalization and distinguish zero, one and two")
                .containsExactlyElementsOf(expected);
        assertThat(text("portfolioError")).isEmpty();
    }

    @ParameterizedTest(name = "Project statuses use shared translations without changing user data [{0}]")
    @MethodSource("projectStatusLabels")
    void projectStatusesUseSharedTranslationsWithoutChangingData(String locale, List<String> expected) {
        String prefix = "/taxonomy";
        installRoutes(prefix);
        var projects = new ArrayList<Object>();
        for (var status : PortfolioTypes.ProjectStatus.values()) {
            var project = labelProject(40 + status.ordinal(), status, 0, 0);
            projects.add(project);
            page.route("GET", prefix + "/api/projects/" + project.id() + "/portfolio", request ->
                    PortfolioClientTestPage.json(200, labelPortfolio(project, List.of(), List.of(), List.of())));
            page.route("GET", prefix + "/api/projects/" + project.id() + "/analysis-jobs", request ->
                    PortfolioClientTestPage.json(200, List.of()));
        }
        var future = labelProject(45, PortfolioTypes.ProjectStatus.ACTIVE, 0, 0);
        ObjectNode futureProject = (ObjectNode) JSON.readTree(PortfolioClientTestPage.json(future));
        futureProject.put("status", "FUTURE_REVIEW");
        ObjectNode futurePortfolio = (ObjectNode) JSON.readTree(
                PortfolioClientTestPage.json(labelPortfolio(future, List.of(), List.of(), List.of())));
        futurePortfolio.set("project", futureProject);
        projects.add(futureProject);
        page.route("GET", prefix + "/api/projects/45/portfolio", request ->
                PortfolioClientTestPage.json(200, futurePortfolio));
        page.route("GET", prefix + "/api/projects/45/analysis-jobs", request ->
                PortfolioClientTestPage.json(200, List.of()));
        page.route("GET", prefix + "/api/projects", request -> PortfolioClientTestPage.json(200, projects));

        page.open("projects", prefix, "/projects", locale, "localStorage.clear();\n" + OBSERVE_FETCH);
        wait.until(ignored -> text("selectedProjectKey").equals("P-40") && hidden("portfolioBusy"));
        assertThat(driver.findElements(By.cssSelector("#projectList .project-select .badge")))
                .extracting(WebElement::getText).containsExactlyElementsOf(expected);
        assertThat(driver.findElements(By.cssSelector("#projectList .project-select > .small.mt-1:not(.opacity-75)")))
                .extracting(WebElement::getText).containsOnly(LABEL_TITLE);
        assertThat(driver.findElements(By.cssSelector("#projectList pump"))).isEmpty();

        for (int index = 0; index < expected.size(); index++) {
            int projectId = 40 + index;
            click(By.cssSelector(".project-select[data-project-id='" + projectId + "']"));
            wait.until(ignored -> text("selectedProjectKey").equals("P-" + projectId) && hidden("portfolioBusy"));
            assertThat(text("selectedProjectStatus")).isEqualTo(expected.get(index));
            assertThat(text("selectedProjectTitle")).isEqualTo(LABEL_TITLE);
            assertThat(driver.findElements(By.cssSelector("#selectedProjectTitle pump"))).isEmpty();
        }
        assertThat(text("portfolioError")).isEmpty();
    }

    @ParameterizedTest(name = "Portfolio enum labels retain their machine values [{0}]")
    @ValueSource(strings = {"de", "en"})
    void portfolioEnumsUseSharedLabelsAndKeepTheirMachineValues(String locale) {
        String prefix = "/taxonomy";
        boolean german = locale.equals("de");
        installRoutes(prefix);
        var project = labelProject(42, PortfolioTypes.ProjectStatus.ACTIVE, 3, 1);
        var requirements = Stream.of(PortfolioTypes.ReviewStatus.values())
                .map(status -> labelRequirement(7 + status.ordinal(), status)).toList();
        var product = labelProduct();
        var solution = labelSolution(product);
        var conflict = new PortfolioDtos.ConflictView(51L, 42L, 7L, "REQ-7", 8L, "REQ-8",
                PortfolioTypes.ConflictType.DATA_LOCATION, PortfolioTypes.ConflictStatus.RESOLVED,
                "Reviewed data location", "Recorded evidence", 0.8, "Reviewed resolution",
                LABEL_TIME, "qa-reviewer", LABEL_TIME);
        page.route("GET", prefix + "/api/projects", request -> PortfolioClientTestPage.json(200, List.of(project)));
        page.route("GET", prefix + "/api/projects/42/portfolio", request ->
                PortfolioClientTestPage.json(200, labelPortfolio(project, requirements, List.of(solution), List.of(conflict))));
        page.route("GET", prefix + "/api/products", request -> PortfolioClientTestPage.json(200, List.of(product)));
        page.open("projects", prefix, "/projects", locale, "localStorage.clear();\n" + OBSERVE_FETCH);
        wait.until(ignored -> text("selectedProjectKey").equals("P-42") && hidden("portfolioBusy"));

        assertThat(driver.findElements(By.cssSelector("#requirementsTable tbody td:nth-child(5) .badge")))
                .extracting(WebElement::getText)
                .containsExactlyElementsOf(german
                        ? List.of("Vorgeschlagen", "Bestätigt", "Verworfen")
                        : List.of("Proposed", "Confirmed", "Rejected"));

        click(By.id("solutions-tab"));
        wait.until(ignored -> driver.findElement(By.cssSelector("#solutionsList .portfolio-solution-card")).isDisplayed());
        assertThat(driver.findElement(By.cssSelector("#solutionsList h4")).getText()).isEqualTo(LABEL_TITLE);
        assertThat(driver.findElements(By.cssSelector("#solutionsList pump"))).isEmpty();
        assertThat(driver.findElements(By.cssSelector("#solutionsList .portfolio-card-meta strong")))
                .extracting(WebElement::getText)
                .containsExactlyElementsOf(german ? List.of("Wiederverwenden", "Ausgewählt") : List.of("Reuse", "Selected"));
        var action = new Select(driver.findElement(By.cssSelector(".solution-action-select")));
        assertThat(action.getOptions()).extracting(WebElement::getText)
                .containsExactlyElementsOf(german
                        ? List.of("Unentschieden", "Bereits erfüllt", "Wiederverwenden", "Ändern", "Erstellen", "Beschaffen",
                                "Organisatorisch", "Stilllegen oder ersetzen")
                        : List.of("Undecided", "Satisfied As Is", "Reuse", "Change", "Create", "Procure",
                                "Organizational", "Retire Or Replace"));
        assertThat(action.getOptions()).extracting(option -> option.getDomProperty("value"))
                .containsExactly("UNDECIDED", "SATISFIED_AS_IS", "REUSE", "CHANGE", "CREATE", "PROCURE",
                        "ORGANIZATIONAL", "RETIRE_OR_REPLACE");
        action.selectByValue("PROCURE");
        assertThat(action.getFirstSelectedOption().getDomProperty("value")).isEqualTo("PROCURE");

        String candidateDetails = "#solutionsList details:has(> form.add-product-candidate[data-project-solution-id='61'])";
        assertThat(driver.findElements(By.cssSelector(candidateDetails))).hasSize(1);
        click(By.cssSelector(candidateDetails + " > summary"));
        wait.until(ignored -> driver.findElement(By.cssSelector(candidateDetails + " .product-candidate-review")).isDisplayed());
        assertThat(driver.findElement(By.cssSelector(candidateDetails + " .badge")).getText())
                .isEqualTo(german ? "Bestätigt" : "Confirmed");
        assertThat(driver.findElement(By.cssSelector(candidateDetails + " .small.text-body-secondary")).getText())
                .isEqualTo(german ? "80% · In engerer Auswahl" : "80% · Shortlisted");

        click(By.id("products-tab"));
        wait.until(ignored -> driver.findElement(By.cssSelector("#productsList .portfolio-product-card")).isDisplayed());
        assertThat(driver.findElement(By.cssSelector("#productsList .badge")).getText())
                .isEqualTo(german ? "Support beendet" : "End Of Support");
        assertThat(driver.findElement(By.cssSelector("#productsList .portfolio-card-meta strong")).getText())
                .isEqualTo(german ? "SaaS" : "Saas");
        click(By.cssSelector("#productsList details > summary"));
        assertThat(driver.findElement(By.cssSelector("#productsList details[open]")).getText())
                .contains(german ? "80% · Verworfen" : "80% · Rejected");

        click(By.id("conflicts-tab"));
        wait.until(ignored -> driver.findElement(By.cssSelector("#conflictsList .portfolio-conflict-card")).isDisplayed());
        assertThat(driver.findElements(By.cssSelector("#conflictsList .badge"))).extracting(WebElement::getText)
                .containsExactlyElementsOf(german ? List.of("Datenstandort", "Gelöst") : List.of("Data Location", "Resolved"));
        assertThat(text("portfolioError")).isEmpty();
    }

    @Test void persistedJobsAreDiscoveredAfterDelayedTranslationsWithoutLosingFormDrafts() {
        String prefix = "/taxonomy";
        installRoutes(prefix);
        var releaseDictionary = new CountDownLatch(1);
        var releaseDiscovery = new CountDownLatch(1);
        var translations = new I18nApiController(new I18nConfig().messageSource()).getTranslations("de");
        page.route("GET", prefix + "/api/i18n/de", request -> {
            awaitFixtureResponse(releaseDictionary, "German dictionary");
            return PortfolioClientTestPage.json(200, translations);
        });
        page.route("GET", prefix + "/api/projects/42/analysis-jobs", request -> {
            awaitFixtureResponse(releaseDiscovery, "persisted project job list");
            return PortfolioClientTestPage.json(200, List.of(persistedJob(42)));
        });

        try {
            open("projects", prefix, "/projects", "");
            wait.until(ignored -> !requests("GET", prefix + "/api/i18n/de").isEmpty());
            double domReady = ((Number) driver.executeScript(
                    "return performance.getEntriesByType('navigation')[0].domContentLoadedEventEnd;")).doubleValue();
            assertThat(domReady).as("The actual document reached DOMContentLoaded").isPositive();
            awaitDiscoveryWindowElapsed(domReady);
            assertThat(driver.executeScript("return window.TaxonomyI18n.isLoaded();")).isEqualTo(false);
            assertThat(driver.executeScript("return localStorage.getItem('taxonomy.portfolio.projectId');")).isNull();
            assertThat(requests("GET", prefix + "/api/projects/42/analysis-jobs"))
                    .as("No project has been selected while the real dictionary response is held").isEmpty();
            Object loading = driver.executeScript("""
                    const busy = document.getElementById('portfolioBusy');
                    const rect = busy.getBoundingClientRect();
                    const style = getComputedStyle(busy);
                    const submits = [...document.querySelectorAll('button[type="submit"]')];
                    return {
                        busyVisible: rect.width > 0 && rect.height > 0
                            && style.display !== 'none' && style.visibility !== 'hidden',
                        allSubmitsDisabled: submits.length > 0 && submits.every(button => button.disabled)
                    };
                    """);
            assertThat(loading)
                    .as("Existing loading feedback and submit protection remain active during a slow dictionary")
                    .isEqualTo(Map.of("busyVisible", true, "allSubmitsDisabled", true));
            assertThat(driver.getCurrentUrl()).isEqualTo(page.origin() + prefix + "/projects?lang=de");
            assertThat(page.requests()).filteredOn(request ->
                            !request.method().equals("GET") && !request.method().equals("HEAD"))
                    .as("Startup does not submit a form or write portfolio data").isEmpty();

            releaseDictionary.countDown();
            wait.until(ignored -> text("selectedProjectKey").equals("P-42") && hidden("portfolioBusy"));
            assertThat(text("selectedProjectStatus")).isEqualTo("Aktiv");
            click(By.cssSelector("[data-bs-target='#projectModal']"));
            wait.until(ignored -> element("projectTitle").isDisplayed());
            String draft = "Unsaved ACTIVE planning draft";
            element("projectTitle").sendKeys(draft);
            releaseDiscovery.countDown();
            click(By.cssSelector("#projectModal .btn-close"));
            wait.until(ignored -> !element("projectModal").isDisplayed());

            awaitPersistedJob(prefix, 42, "stored-42");
            assertThat(requests("GET", prefix + "/api/projects/42/analysis-jobs")).isNotEmpty();
            assertThat(element("projectTitle").getDomProperty("value")).isEqualTo(draft);
            assertThat(text("portfolioError")).isEmpty();
        } finally {
            releaseDictionary.countDown();
            releaseDiscovery.countDown();
            awaitTranslationContinuations();
            wait.until(ignored -> text("selectedProjectKey").equals("P-42") && hidden("portfolioBusy"));
        }
    }

    @Test void selectingAnotherProjectDiscoversItsPersistedJobsAfterStartup() {
        String prefix = "";
        installRoutes(prefix);
        for (int projectId : List.of(42, 63)) {
            page.route("GET", prefix + "/api/projects/" + projectId + "/analysis-jobs", request ->
                    PortfolioClientTestPage.json(200, List.of(persistedJob(projectId))));
        }
        open("projects", prefix, "/projects", "");
        wait.until(ignored -> text("selectedProjectKey").equals("P-42") && hidden("portfolioBusy"));
        awaitPersistedJob(prefix, 42, "stored-42");
        double firstSelection = ((Number) driver.executeScript("return performance.now();")).doubleValue();
        awaitDiscoveryWindowElapsed(firstSelection);

        element("projectFilter").sendKeys("Emergency");
        click(By.cssSelector(".project-select[data-project-id='63']"));
        wait.until(ignored -> text("selectedProjectKey").equals("P-63") && hidden("portfolioBusy"));
        awaitPersistedJob(prefix, 63, "stored-63");
        assertThat(requests("GET", prefix + "/api/projects/63/analysis-jobs")).isNotEmpty();
        assertThat(element("projectFilter").getDomProperty("value")).isEqualTo("Emergency");
        assertThat(text("portfolioError")).isEmpty();
    }

    private static Stream<Arguments> projectCountLabels() {
        return Stream.of(
                Arguments.of("de", List.of("0 Anforderungen · 0 Lösungen", "1 Anforderung · 2 Lösungen", "2 Anforderungen · 1 Lösung")),
                Arguments.of("en", List.of("0 requirements · 0 solutions", "1 requirement · 2 solutions", "2 requirements · 1 solution")));
    }

    private static Stream<Arguments> projectStatusLabels() {
        return Stream.of(
                Arguments.of("de", List.of("In Planung", "Aktiv", "Pausiert", "Abgeschlossen", "Archiviert", "Future Review")),
                Arguments.of("en", List.of("Planning", "Active", "On Hold", "Completed", "Archived", "Future Review")));
    }

    private static PortfolioDtos.ProjectView labelProject(long id, PortfolioTypes.ProjectStatus status,
                                                         int requirements, int solutions) {
        return new PortfolioDtos.ProjectView(id, "P-" + id, LABEL_TITLE, "Reviewed portfolio",
                status, "qa-reviewer", "shared", null, null, null, null, LABEL_TIME, LABEL_TIME,
                requirements, solutions, 0);
    }

    private static PortfolioDtos.ProjectPortfolioView labelPortfolio(
            PortfolioDtos.ProjectView project, List<PortfolioDtos.RequirementView> requirements,
            List<PortfolioDtos.ProjectSolutionView> solutions, List<PortfolioDtos.ConflictView> conflicts) {
        var matrix = new PortfolioDtos.MatrixView(List.of(), List.of(), Map.of());
        return new PortfolioDtos.ProjectPortfolioView(project,
                new PortfolioDtos.PortfolioMetrics(requirements.size(), 0, 0, requirements.size(),
                        solutions.size(), Map.of(), 0, 0, conflicts.size(), 0),
                requirements, List.of(), solutions, conflicts, matrix, matrix, matrix);
    }

    private static PortfolioDtos.RequirementView labelRequirement(long id, PortfolioTypes.ReviewStatus review) {
        var version = new PortfolioDtos.RequirementVersionView(id + 100, 1, "Stop the pump safely.",
                "content-hash-" + id, "Reviewed text", "qa-reviewer", LABEL_TIME, null);
        return new PortfolioDtos.RequirementView(id, 42L, "REQ-" + id, LABEL_TITLE,
                PortfolioTypes.RequirementStatus.APPROVED, 60, PortfolioTypes.Criticality.MEDIUM,
                PortfolioTypes.RequirementType.FUNCTIONAL, review, "qa-reviewer", version.id(),
                null, LABEL_TIME, LABEL_TIME, version);
    }

    private static PortfolioDtos.ProductView labelProduct() {
        var coverage = new PortfolioDtos.TaxonomyCoverageView(91L, "CP-1", 80,
                "Recorded coverage evidence", PortfolioTypes.ReviewStatus.REJECTED, "qa-reviewer", LABEL_TIME);
        return new PortfolioDtos.ProductView(71L, "PRODUCT-71", "Example", "Control", "Reviewed product", "1.0",
                PortfolioTypes.ProductStatus.END_OF_SUPPORT, null, "Reviewed license", PortfolioTypes.OperatingModel.SAAS,
                "Reviewed platform", "Reviewed security", "Reviewed compliance", null, null, null,
                "Recorded source", LABEL_TIME, "qa-reviewer", LABEL_TIME, LABEL_TIME, List.of(coverage));
    }

    private static PortfolioDtos.ProjectSolutionView labelSolution(PortfolioDtos.ProductView product) {
        var solution = new PortfolioDtos.SolutionView(31L, "SOLUTION-31", LABEL_TITLE, "Reviewed solution",
                PortfolioTypes.SolutionType.APPLICATION, PortfolioTypes.OperatingModel.SAAS,
                PortfolioTypes.LifecycleStatus.ACTIVE, 3, "qa-reviewer", "Example",
                null, null, null, null, Map.of(), LABEL_TIME, LABEL_TIME, List.of());
        var candidate = new PortfolioDtos.SolutionProductCandidateView(81L, 61L, product, 80,
                null, "Reviewed strengths", null, null, 0.8, PortfolioTypes.ReviewStatus.CONFIRMED,
                PortfolioTypes.ProductSelectionStatus.SHORTLISTED, "qa-reviewer", LABEL_TIME);
        return new PortfolioDtos.ProjectSolutionView(61L, 42L, solution,
                PortfolioTypes.ProjectSolutionStatus.SELECTED, PortfolioTypes.ActionStatus.REUSE, 60,
                "Reviewed rationale", "qa-reviewer", LABEL_TIME, LABEL_TIME, List.of(), List.of(candidate));
    }

    private static void awaitDiscoveryWindowElapsed(double since) {
        wait.until(ignored -> Boolean.TRUE.equals(driver.executeScript(
                "return performance.now() - arguments[0] > 4500;", since)));
    }

    private static void awaitFixtureResponse(CountDownLatch gate, String resource) {
        try {
            if (!gate.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release " + resource);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding " + resource, interrupted);
        }
    }

    private static void awaitPersistedJob(String prefix, int projectId, String jobId) {
        String expected = page.origin() + prefix + "/api/projects/" + projectId + "/analysis-jobs/" + jobId;
        try {
            new WebDriverWait(driver, Duration.ofSeconds(6))
                    .until(ignored -> renderedJobUrls().contains(expected));
        } catch (TimeoutException absent) {
            // Report missing rendered content as the contract assertion below.
        }
        assertThat(renderedJobUrls()).as("The selected project's persisted job is rendered").contains(expected);
    }

    private static List<String> renderedJobUrls() {
        Object result = driver.executeScript("""
                return [...document.querySelectorAll('#portfolioJobList .portfolio-job')].filter(card => {
                    const rect = card.getBoundingClientRect();
                    const style = getComputedStyle(card);
                    return rect.width > 0 && rect.height > 0
                        && style.display !== 'none' && style.visibility !== 'hidden';
                }).map(card => card.dataset.jobUrl);
                """);
        return ((List<?>) result).stream().map(String.class::cast).toList();
    }

    private static PortfolioDtos.AnalysisJobView persistedJob(int projectId) {
        var item = new PortfolioDtos.AnalysisJobItemView(701L, 7L, "REQ-7", 11L, 1,
                PortfolioTypes.AnalysisStatus.SUCCESS, "stored-snapshot-" + projectId, 1,
                LABEL_TIME, LABEL_TIME, null);
        return new PortfolioDtos.AnalysisJobView("stored-" + projectId, (long) projectId,
                PortfolioTypes.AnalysisStatus.SUCCESS, "persisted-" + projectId, null, 25,
                "qa-reviewer", "shared", LABEL_TIME, LABEL_TIME, LABEL_TIME,
                1, 1, 0, 0, null, List.of(item));
    }

    private static Stream<Arguments> prefixesAndPages() {
        return PREFIXES.stream().flatMap(prefix -> PAGES.stream().map(contract -> Arguments.of(prefix, contract)));
    }

    private static void open(String template, String prefix, String path, String setup) {
        page.open(template, prefix, path, "de", "localStorage.clear();\n" + OBSERVE_FETCH + setup);
    }

    private static void openJobPage(String prefix, String setup) {
        String html = """
                <!doctype html><html lang="de"><head><meta charset="utf-8"><title>Portfolio job URL contract</title></head>
                <body><main><section aria-labelledby="portfolioMetricsHeading"><h1 id="portfolioMetricsHeading">Jobs</h1></section></main>
                <div id="portfolioStatus" role="status"></div><div id="portfolioInfo"></div><div id="portfolioError" role="alert"></div>
                <script>localStorage.clear();%s%s</script>
                <script src="%s/js/taxonomy-i18n.js"></script>
                <script src="%s/js/shared/taxonomy-utils.js"></script>
                <script src="%s/js/api/portfolio-api.js"></script>
                <script src="%s/js/portfolio/taxonomy-portfolio-async.js"></script></body></html>
                """.formatted(OBSERVE_FETCH, setup, prefix, prefix, prefix, prefix);
        page.openHtml(prefix, "/projects", html);
        wait.until(ignored -> Boolean.TRUE.equals(driver.executeScript("return typeof window.taxonomyPortfolioRegisterJob === 'function';")));
    }

    private static void prepareImport(String prefix, String location) {
        page.route("POST", prefix + "/api/projects/42/requirements/import-review", request ->
                PortfolioClientTestPage.json(200, Map.of("analysisJob", JOB)).withHeader("Location", location));
        open("portfolio-import", prefix, "/projects/42/import", "localStorage.setItem('taxonomy.portfolio.importDraft.42', "
                + PortfolioClientTestPage.json(PortfolioClientTestPage.json(importDraft())) + ");");
        wait.until(ignored -> text("importProject").contains("P-42") && !hidden("restoreDraft"));
        click(By.id("restoreDraft"));
        wait.until(ignored -> !hidden("reviewStep"));
        click(By.id("reviewSummaryButton"));
        wait.until(ignored -> !hidden("summaryStep"));
    }

    private static void awaitImportReturn(String prefix) {
        // An actual beforeunload dialog, if shown, belongs to the native browser;
        // accepting it allows the production 1200ms return navigation to finish.
        try { new WebDriverWait(driver, Duration.ofSeconds(3)).until(ExpectedConditions.alertIsPresent()).accept(); }
        catch (TimeoutException | NoAlertPresentException absent) { /* Navigation may complete without a dialog. */ }
        wait.until(ignored -> driver.getCurrentUrl().equals(page.origin() + prefix + "/projects?lang=de"));
    }

    private static Map<String, Object> importDraft() {
        return Map.of("projectId", 42, "fileName", "reviewed-requirements.docx", "totalPages", 1, "warnings", List.of(),
                "candidates", List.of(Map.of("id", 1, "key", "REQ-8", "title", "Close valve", "text", "Close the valve.",
                        "type", "FUNCTIONAL", "priority", 60, "criticality", "MEDIUM", "decision", "NEW", "origin", "RULE")));
    }

    private static List<String> invalidJobLocations() {
        String authority = page.origin().substring("http://".length());
        return List.of(otherOrigin + "/taxonomy/api/projects/42/analysis-jobs/job-7",
                "//elsewhere.example.test/api/projects/42/analysis-jobs/job-7",
                "/taxonomy-other/api/projects/42/analysis-jobs/job-7",
                page.origin() + "/api/projects/42/analysis-jobs/job-7",
                "/taxonomy/api/projects/42/analysis-jobs/job-7/retry-failed",
                "/taxonomy/api/projects/42/analysis-jobs/job-7?next=/outside",
                "/taxonomy/api/projects/42/analysis-jobs/job-7#fragment",
                "http://user:secret@" + authority + "/taxonomy/api/projects/42/analysis-jobs/job-7",
                "https://[", "/taxonomy/api/projects/42/analysis-jobs/job%2Foutside");
    }

    private static boolean registerJob(String location, Map<String, Object> job) {
        return Boolean.TRUE.equals(driver.executeScript("return window.taxonomyPortfolioRegisterJob(arguments[0], arguments[1]);", location, job));
    }

    private static long postStatus(String path) {
        Object value = driver.executeAsyncScript("""
                const complete = arguments[arguments.length - 1];
                fetch(arguments[0], {method: 'POST'}).then(response => complete(response.status),
                    error => complete(String(error)));
                """, path);
        assertThat(value).as("A real HTTP request completed for %s", path).isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }

    private static JsonNode readJobs() {
        return JSON.readTree((String) driver.executeScript("return localStorage.getItem(arguments[0]) || '[]';", JOB_STORAGE));
    }

    private static void assertSameOriginCredentials(String url) {
        Object credentials = driver.executeScript("""
                return window.portfolioContractRequests.filter(request => request.url === arguments[0])
                    .map(request => request.credentials);
                """, url);
        assertThat(credentials).isEqualTo(List.of("same-origin"));
    }

    private static void awaitTranslationContinuations() {
        Object result = driver.executeAsyncScript("""
                const complete = arguments[arguments.length - 1];
                window.TaxonomyI18n.ready().then(() => queueMicrotask(() => complete(true)),
                    error => complete(String(error)));
                """);
        assertThat(result).as("Existing asynchronous page initialization has passed its i18n readiness boundary").isEqualTo(true);
    }

    private static List<PortfolioClientTestPage.Request> requests(String method, String path) {
        return page.requests().stream().filter(request -> request.method().equals(method) && request.path().equals(path)).toList();
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

    private static WebElement element(String id) { return driver.findElement(By.id(id)); }
    private static String text(String id) { return element(id).getDomProperty("textContent"); }
    private static String href(String id) { return element(id).getDomAttribute("href"); }
    private static boolean hidden(String id) { return List.of(element(id).getDomAttribute("class").split("\\s+")).contains("d-none"); }

    private static Map<String, Object> project(int id) {
        return Map.of("id", id, "projectKey", "P-" + id, "title", "Emergency control", "status", "ACTIVE",
                "requirementCount", 1, "solutionCount", 0);
    }

    private static Map<String, Object> requirement() {
        return Map.of("id", 7, "requirementKey", "REQ-7", "title", "Stop <pump> & \"verify\"", "reviewStatus", "PROPOSED",
                "currentVersion", Map.of("id", 11, "versionNumber", 1, "text", "Stop the pump before closing the valve."));
    }

    private static Map<String, Object> portfolio(int id) {
        return Map.of("project", project(id), "requirements", List.of(requirement()), "metrics", Map.of(),
                "requirementTaxonomyMatrix", Map.of("rows", List.of("REQ-7"), "columns", List.of("NODE-1"),
                        "values", Map.of("REQ-7", Map.of("NODE-1", 80))));
    }

    private static Map<String, Object> runningJob() { return Map.of("id", "job-7", "status", "RUNNING", "items", List.of()); }

    private static void installRoutes(String prefix) {
        page.clearRoutes();
        page.clearRequests();
        Map<String, Object> routes = new LinkedHashMap<>();
        routes.put("/api/projects", List.of(project(42), project(63)));
        routes.put("/api/account/me", Map.of("architectureMutationAllowed", true));
        routes.put("/api/products", List.of());
        for (int id : List.of(42, 63)) {
            routes.put("/api/projects/" + id, project(id));
            routes.put("/api/projects/" + id + "/portfolio", portfolio(id));
            routes.put("/api/projects/" + id + "/requirements", List.of(requirement()));
            routes.put("/api/projects/" + id + "/analysis-jobs", List.of());
        }
        routes.put("/api/projects/42/requirements/7", requirement());
        for (String suffix : List.of("versions", "snapshots", "reformulations")) {
            routes.put("/api/projects/42/requirements/7/" + suffix, List.of());
        }
        routes.put("/api/projects/42/analysis-jobs/job-7", JOB);
        routes.put("/api/git/state", repository());
        routes.put("/api/projects/git/export", Map.of("workspaceId", "shared", "username", "qa-reviewer",
                "activeBranch", "main", "headCommit", "0123456789012345678901234567890123456789", "dsl", "project P-42",
                "projectCount", 1, "requirementCount", 1, "solutionCount", 0, "productCount", 0,
                "exportedAt", "2026-10-07T09:00:00Z"));
        routes.forEach((path, body) -> page.route("GET", prefix + path, request -> PortfolioClientTestPage.json(200, body)));
    }

    private static Map<String, Object> repository() {
        // RepositoryState.branches is List<String>; object-shaped fixtures hide
        // the actual DTO mismatch that this browser regression must detect.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentBranch", "main");
        result.put("headCommit", "0123456789012345678901234567890123456789");
        result.put("headTimestamp", "2026-10-07T09:00:00Z");
        result.put("headAuthor", "qa-reviewer");
        result.put("headMessage", "Reviewed portfolio");
        result.put("branches", BRANCHES);
        result.put("operationInProgress", false); result.put("operationKind", null);
        result.put("projectionCommit", result.get("headCommit")); result.put("projectionBranch", "main");
        result.put("projectionTimestamp", result.get("headTimestamp")); result.put("projectionStale", false);
        result.put("indexCommit", result.get("headCommit")); result.put("indexStale", false);
        result.put("totalCommits", 3); result.put("databaseBacked", false);
        return result;
    }

    private record PageContract(String template, String suffix, String heading, String error) {
        @Override public String toString() { return template; }
    }
}

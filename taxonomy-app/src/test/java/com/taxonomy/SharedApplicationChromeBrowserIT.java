package com.taxonomy;

import com.taxonomy.portfolio.dto.CopilotDtos.AiAutomationStatus;
import com.taxonomy.portfolio.model.AiCostPolicy;
import com.taxonomy.portfolio.model.AnalysisAutomationProfile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.taxonomy.PortfolioClientTestPage.json;
import static org.assertj.core.api.Assertions.assertThat;

/** Shared chrome contracts use the production Thymeleaf, translations and native browser. */
@Tag("browser")
@Tag("ci-app-shard-browser")
class SharedApplicationChromeBrowserIT {
    private static PortfolioClientTestPage page;
    private static WebDriverWait wait;
    private static final String PINS = "projectId=42&repositoryId=architecture-a&workspaceId=private-a"
            + "&workspaceScopeKey=private-a&branch=review%2Fcurrent&sourceProjectId=12"
            + "&sourceRequirementId=7&sourceRequirementVersionId=9&sourceSnapshotId=frozen-7"
            + "&sourceRepositoryId=architecture-source&sourceWorkspaceId=source-space"
            + "&sourceWorkspaceScopeKey=source-space&sourceBranch=review%2Fsource";

    record View(String template, String path, String active, String retainedControl) { }

    static Stream<Object[]> views() {
        return Stream.of(
                new View("index", "/", "analysis", "fileMenuButton"),
                new View("projects", "/projects", "projects", "projectFilter"),
                new View("requirement-detail", "/projects/42/requirements/7", "projects", "newVersionButton"),
                new View("architecture-workbench", "/architecture/workbench", "architecture", "printArchitectureView"),
                new View("architecture-editor", "/architecture/editor", "architecture", "editorRefresh"),
                new View("portfolio-versioning", "/projects/42/versioning", "projects", "commitMessage"))
                .flatMap(view -> Stream.of(new Object[]{view, "en", 1440}, new Object[]{view, "de", 390}));
    }

    @BeforeAll static void openBrowser(@TempDir Path downloads) throws Exception {
        page = new PortfolioClientTestPage(downloads);
        wait = new WebDriverWait(page.driver(), Duration.ofSeconds(20));
    }

    @AfterAll static void closeBrowser() { if (page != null) page.close(); }

    private static void open(View view, String locale, int width) {
        open(view, locale, width, List.of("portfolio", "architecture"));
    }

    private static void open(View view, String locale, int width, List<String> features) {
        page.clearRoutes();
        page.clearRequests();
        page.driver().manage().window().setSize(new Dimension(width, 1000));
        page.route("GET", "/taxonomy/api/projects", request -> json(200, List.of()));
        page.route("GET", "/taxonomy/api/capabilities", request -> json(200, Map.of("features", features)));
        page.openAt(view.template(), "/taxonomy", "/taxonomy" + view.path(), locale,
                """
                (function () {
                    const selected=new URL(location.href);
                    if (!selected.searchParams.has('repositoryId')) { localStorage.clear();sessionStorage.clear(); }
                    new URLSearchParams('%s').forEach((value,key) => {
                        if (!selected.searchParams.has(key)) selected.searchParams.set(key,value);
                    });
                    if (!selected.hash) selected.hash='retained-selection';
                    history.replaceState(null,'',selected);
                }());
                """.formatted(PINS),
                Map.of("projectId", 42, "snapshotId", "frozen-7"));
        wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript(
                "return !!window.TaxonomyI18n && TaxonomyI18n.isLoaded();")));
        if (view.template().equals("index")) {
            // Complete the real first-run dialog before testing the page behind it.
            wait.until(ExpectedConditions.elementToBeClickable(By.id("onboardingDismiss"))).click();
            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
        }
    }

    @ParameterizedTest(name = "{0} shares navigation in {1} at {2}px")
    @MethodSource("views")
    void existingViewsShareAccessibleNavigationWithoutLosingTheirControls(View view, String locale, int width) {
        open(view, locale, width);
        var navigation = page.driver().findElements(By.id("applicationNavigation"));
        assertThat(navigation).as("Every existing view has the same application navigation").hasSize(1);
        assertThat(navigation.getFirst().getAccessibleName()).isNotBlank();
        var links = page.driver().findElements(By.cssSelector(".taxonomy-app-primary [data-app-route]"));
        assertThat(links).extracting(link -> link.getDomAttribute("data-app-route"))
                .containsExactly("projects", "analysis", "architecture");
        assertThat(page.driver().findElement(By.cssSelector("[data-app-route='" + view.active() + "']"))
                .getDomAttribute("aria-current")).isEqualTo("page");
        for (var link : links) {
            assertThat(link.isDisplayed()).isTrue();
            assertThat(link.getAccessibleName()).isNotBlank();
            assertThat(link.getDomAttribute("href"))
                    .startsWith("/taxonomy/")
                    .contains("repositoryId=architecture-a", "workspaceId=private-a", "workspaceScopeKey=private-a",
                            "branch=review%2Fcurrent", "sourceSnapshotId=frozen-7", "sourceRepositoryId=architecture-source",
                            "sourceBranch=review%2Fsource", "lang=" + locale);
        }
        assertThat(links.get(2).getDomAttribute("href")).startsWith("/taxonomy/architecture/editor?");
        assertThat(page.driver().findElements(By.id(view.retainedControl()))).hasSize(1);
        assertThat(page.driver().executeScript("""
                const nav=document.getElementById('applicationNavigation'), r=nav.getBoundingClientRect();
                return [...nav.querySelectorAll('.taxonomy-app-primary a')].every(a => {
                    const b=a.getBoundingClientRect();
                    return b.width>=44 && b.height>=44 && b.left>=0 && b.right<=innerWidth+1;
                }) && r.right<=innerWidth+1;
                """)).as("Navigation stays usable without horizontal clipping").isEqualTo(true);
        if (view.template().equals("index")) {
            assertThat(page.driver().findElement(By.id("projectMenuButton")).isDisplayed()).isTrue();
            assertThat(page.driver().findElements(By.id("mainNavTabs"))).hasSize(1);
            assertThat(page.driver().findElement(By.cssSelector("#applicationNavigation .context-help")).getDomAttribute("href"))
                    .as("Analysis help reuses the existing documented section")
                    .endsWith("#help/USER_GUIDE/guide-section-4");
        }
        if (view.template().equals("architecture-workbench")) {
            assertThat(page.driver().findElements(By.id("architectureExportOptions"))).hasSize(1);
        }
    }

    @ParameterizedTest @ValueSource(ints = {1440, 390})
    void expandedRequirementsKeepWorkbenchControlsReachable(int width) {
        open(new View("architecture-workbench", "/architecture/workbench", "architecture", "printArchitectureView"), "en", width);
        var requirements = java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(number -> Map.<String, Object>ofEntries(
                Map.entry("id", "requirement-" + number), Map.entry("projectId", 42), Map.entry("projectKey", "P"),
                Map.entry("requirementId", number), Map.entry("requirementKey", "REQ-" + number),
                Map.entry("title", "Communication requirement " + number), Map.entry("version", 1), Map.entry("versionId", 100 + number),
                Map.entry("contentHash", "fixture-hash-" + number), Map.entry("text", "Authored layout fixture " + number),
                Map.entry("realization", Map.of("state", "OPEN")), Map.entry("contributors", List.of()), Map.entry("links", List.of()))).toList();
        page.route("GET", "/taxonomy/api/architecture/lifecycle", request -> json(200, Map.of(
                "context", Map.of("repositoryId", "architecture-a", "workspaceScopeKey", "private-a", "branch", "review/current", "commit", "a".repeat(40), "revision", 4),
                "architectureTitle", "Communication architecture", "source", "WORKSPACE_AND_PORTFOLIO", "projectionState", "READY",
                "publicationState", "CHECKPOINTED", "mayEdit", false,
                "projects", List.of(), "dependencies", List.of(), "requirements", requirements)));
        wait.until(driver -> !driver.findElements(By.cssSelector("#architectureLifecycle > details > summary")).isEmpty());
        page.driver().executeAsyncScript("const done=arguments[arguments.length-1];TaxonomyArchitectureLifecycle.refresh().then(done);");
        page.driver().findElement(By.cssSelector("#architectureLifecycle > details > summary")).click();
        assertThat(page.driver().findElements(By.cssSelector("#architectureLifecycle tbody tr"))).hasSize(12);
        assertThat(page.driver().executeScript("""
                const controls=document.querySelector('.architecture-controls').getBoundingClientRect();
                const main=document.querySelector('.workbench-grid').getBoundingClientRect();
                return controls.top>=0 && controls.bottom<=innerHeight && main.height>=200;
                """)).as("Expanded requirement history leaves the architecture controls in the viewport").isEqualTo(true);
        var last = page.driver().findElement(By.cssSelector("#architectureLifecycle [data-requirement-key='REQ-12'] [data-lifecycle-action='history']"));
        page.driver().executeScript("arguments[0].scrollIntoView({block:'nearest',behavior:'instant'});", last);
        assertThat(page.driver().executeScript("const r=arguments[0].getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight;", last))
                .as("The last requirement remains reachable in the bounded header").isEqualTo(true);
    }

    @Test void forcedColorsKeepAnalysisMenuTextReadable() {
        var cdp = (HasCdp) new Augmenter().augment(page.driver());
        cdp.executeCdpCommand("Emulation.setEmulatedMedia", Map.of("features",
                List.of(Map.of("name", "forced-colors", "value", "active"))));
        try {
            open(new View("index", "/", "analysis", "fileMenuButton"), "en", 1366);
            assertThat(page.driver().executeScript("return matchMedia('(forced-colors: active)').matches;")).isEqualTo(true);
            for (String id : List.of("fileMenuButton", "projectMenuButton", "main-tab-help", "aboutBtn")) {
                var button = page.driver().findElement(By.id(id));
                assertThat(button.isDisplayed()).as("%s remains visible in forced colors", id).isTrue();
                Number ratio = (Number) page.driver().executeScript("""
                        const element=arguments[0],style=getComputedStyle(element);
                        const luminance=color=>{
                            const rgb=color.match(/[\\d.]+/g).slice(0,3).map(Number).map(v=>{
                                v/=255;return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);
                            });
                            return rgb[0]*.2126+rgb[1]*.7152+rgb[2]*.0722;
                        };
                        let parent=element,background=style.backgroundColor;
                        while(parent.parentElement && (background==='transparent' || background==='rgba(0, 0, 0, 0)')) {
                            parent=parent.parentElement;background=getComputedStyle(parent).backgroundColor;
                        }
                        const first=luminance(style.color),second=luminance(background);
                        return (Math.max(first,second)+.05)/(Math.min(first,second)+.05);
                        """, button);
                assertThat(ratio.doubleValue()).as("%s foreground/background contrast", id).isGreaterThanOrEqualTo(4.5);
            }
        } finally {
            cdp.executeCdpCommand("Emulation.setEmulatedMedia", Map.of("features",
                    List.of(Map.of("name", "forced-colors", "value", "none"))));
        }
    }

    @ParameterizedTest @ValueSource(ints = {1920, 1366, 1024, 390})
    void loadedWorkbenchReservesDiagramSpaceWithCurrentAndSourceContext(int width) throws IOException {
        page.clearRoutes(); page.clearRequests();
        page.driver().manage().window().setSize(new Dimension(width, width == 1920 ? 1080 : width == 390 ? 844 : 768));
        page.route("GET", "/taxonomy/api/capabilities", request -> json(200, Map.of("features", List.of("portfolio", "architecture"))));
        var project = Map.of("id", 42, "projectKey", "QA-PROJECT", "title", "Communication architecture contribution", "status", "ACTIVE");
        page.route("GET", "/taxonomy/api/projects", request -> json(200, List.of(project)));
        page.route("GET", "/taxonomy/api/architecture/lifecycle", request -> json(200, Map.of(
                "context", Map.of("repositoryId", "qa-architecture", "workspaceScopeKey", "qa-context-workspace",
                        "branch", "qa-context-main", "commit", "a".repeat(40), "revision", 4),
                "architectureTitle", "Communication architecture", "source", "WORKSPACE_AND_PORTFOLIO", "projectionState", "READY",
                "publicationState", "CHECKPOINTED", "mayEdit", false,
                "projects", List.of(project), "dependencies", List.of(), "requirements", List.of())));
        page.route("GET", "/taxonomy/api/projects/42/architecture-workbench/qa-locale", request -> json(200, Map.ofEntries(
                Map.entry("projectId", 42), Map.entry("projectKey", "QA-PROJECT"), Map.entry("requirementId", 7),
                Map.entry("requirementKey", "QA-REQ-7"), Map.entry("requirementTitle", "Communication access requirement"),
                Map.entry("requirementVersionNumber", 1), Map.entry("snapshotId", "qa-locale"), Map.entry("snapshotStatus", "SUCCESS"),
                Map.entry("requirementText", "Original English requirement\n" + "Original authored requirement detail\n".repeat(80)),
                Map.entry("provider", "QA"), Map.entry("policyTitleKey", "archview.policy.title.defaultImpact"),
                Map.entry("branchName", "qa-verifikation"), Map.entry("commitSha", "0123456789".repeat(4)),
                Map.entry("workspaceId", "qa-context-workspace"),
                Map.entry("exportProvenance", Map.of("repositoryId", "qa-architecture", "requirementVersionId", 11)),
                Map.entry("scene", Map.of("title", "archview.policy.title.defaultImpact", "nodes", List.of(
                        Map.of("id", "QA-01", "label", "Original English architecture title", "type", "BP", "layer", "BP",
                                "x", 40, "y", 80, "width", 260, "height", 90, "relevance", .9, "anchor", true),
                        Map.of("id", "QA-02", "label", "Original distant architecture title", "type", "BP", "layer", "BP",
                                "x", 2200, "y", 1800, "width", 260, "height", 90, "relevance", .7, "anchor", false)), "edges", List.of())),
                Map.entry("elements", Map.of("QA-01", Map.of("directScore", 90, "reviewStatus", "PROPOSED", "presenceReason", "Original English evidence"))),
                Map.entry("relations", Map.of()))));
        page.openAt("architecture-workbench", "/taxonomy", "/taxonomy/architecture/workbench", "de", """
                localStorage.clear();sessionStorage.clear();
                history.replaceState(null,'','?projectId=42&snapshotId=qa-locale&repositoryId=qa-architecture&workspaceId=qa-context-workspace&workspaceScopeKey=qa-context-workspace&branch=qa-context-main&lang=de');
                """, Map.of("projectId", 42, "snapshotId", "qa-locale"));
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#architectureCanvas .architecture-node")));
        wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("""
                const c=window.TaxonomyAppChrome?.getContext();
                return c?.architecture?.title==='Communication architecture' && c?.source?.snapshotId==='qa-locale';
                """)));
        assertThat(page.driver().findElement(By.id("architectureAlert")).isDisplayed()).isFalse();
        var geometry = (Map<?, ?>) page.driver().executeScript("""
                const rect=selector=>{const r=document.querySelector(selector).getBoundingClientRect();
                    return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height};};
                const canvas=rect('#architectureCanvasShell'),details=rect('.workbench-detail-panel');
                const desktop=innerWidth>900;
                return {innerWidth,innerHeight,canvas,details,header:rect('.taxonomy-app-chrome'),
                    valid:document.documentElement.scrollWidth<=innerWidth+1 && (desktop
                        ? canvas.height>=innerHeight*.5 && canvas.bottom<=innerHeight+1 && details.bottom<=innerHeight+1
                        : canvas.top<=innerHeight*.7 && [...document.querySelectorAll('.architecture-control,.architecture-toggle,.architecture-search')]
                            .filter(e=>!e.hidden).every(e=>e.getBoundingClientRect().height>=44))};
                """);
        if (!Boolean.TRUE.equals(geometry.get("valid"))) {
            Path evidence = Path.of("target", "coherent-lifecycle", "loaded-workbench-" + width);
            Files.createDirectories(evidence);
            Files.write(evidence.resolve("viewport.png"), page.driver().getScreenshotAs(OutputType.BYTES));
            Files.writeString(evidence.resolve("geometry.json"), PortfolioClientTestPage.json(geometry));
            Files.writeString(evidence.resolve("page.html"), page.driver().getPageSource());
        }
        assertThat(geometry.get("valid")).as("The fully loaded snapshot keeps the existing CI diagram and inspector viewport gates: %s", geometry).isEqualTo(true);
        assertThat(page.driver().findElement(By.id("requirementText")).getText()).contains("Original authored requirement detail");
        assertThat(page.driver().findElement(By.id("architectureProvenance")).getText()).contains("qa-locale");
    }

    @Test void languageAndSourceReturnKeepTheCompleteSelection() {
        open(new View("architecture-editor", "/architecture/editor", "architecture", "editorRefresh"), "en", 390);
        var german = page.driver().findElements(By.cssSelector("#applicationNavigation a[hreflang='de']"));
        assertThat(german).hasSize(1);
        assertThat(german.getFirst().getDomAttribute("href"))
                .contains("projectId=42", "repositoryId=architecture-a", "workspaceId=private-a",
                        "sourceSnapshotId=frozen-7", "sourceRequirementVersionId=9", "lang=de")
                .endsWith("#retained-selection");
        german.getFirst().click();
        wait.until(ExpectedConditions.urlContains("lang=de"));
        assertThat(page.driver().getCurrentUrl()).contains("sourceSnapshotId=frozen-7", "lang=de")
                .endsWith("#retained-selection");
        Object source = page.driver().executeScript("return TaxonomyArchitectureWorkflow.editorSourceLinks().snapshot;");
        assertThat(source.toString()).contains("projectId=12", "snapshotId=frozen-7", "repositoryId=architecture-source",
                "workspaceId=source-space", "branch=review%2Fsource");
    }

    @Test void lifecycleReadsReceiveAllScopePinsAndExplicitOtherContextsStayUntouched() {
        open(new View("architecture-editor", "/architecture/editor", "architecture", "editorRefresh"), "en", 1440);
        Object scoped = page.driver().executeScript("""
                return TaxonomyArchitectureWorkflow.scopeUrl('/taxonomy/api/architecture/lifecycle?projectId=42');
                """);
        assertThat(scoped.toString()).contains("repositoryId=architecture-a", "workspaceId=private-a",
                "workspaceScopeKey=private-a", "branch=review%2Fcurrent");
        Object different = page.driver().executeScript("""
                return TaxonomyArchitectureWorkflow.scopeUrl('/taxonomy/api/architecture/editor/context?repositoryId=another');
                """);
        assertThat(different).isEqualTo("/taxonomy/api/architecture/editor/context?repositoryId=another");
        page.route("GET", "/taxonomy/api/architecture/lifecycle", request -> json(200, Map.of()));
        page.driver().executeAsyncScript("""
                const done=arguments[arguments.length-1];
                fetch('/api/architecture/lifecycle?projectId=42').then(r=>r.json()).then(done);
                """);
        assertThat(page.requests().stream().filter(request -> request.path().endsWith("/architecture/lifecycle")).toList())
                .isNotEmpty().allSatisfy(request -> assertThat(request.query()).contains("repositoryId=architecture-a",
                        "workspaceId=private-a", "workspaceScopeKey=private-a", "branch=review%2Fcurrent"));
    }

    @Test void editorAndPortfolioStateSpellingsUseTheSameGermanStatusLabel() {
        open(new View("architecture-editor", "/architecture/editor", "architecture", "editorRefresh"), "de", 1440);
        page.driver().executeScript("""
                const host=document.createElement('section');host.id='german-entity-status';document.body.append(host);
                TaxonomyAppChrome.renderEntity(host,{kind:'element',key:'authored-element',title:'Autorisierter Entwurf',status:'draft'});
                """);
        assertThat(page.driver().findElement(By.cssSelector("#german-entity-status .taxonomy-entity-status")).getText())
                .as("The editor's lowercase state uses the same German label as the portfolio enum")
                .isEqualTo("Entwurf");
    }

    @Test void primaryNavigationHonorsTheExistingOptionalFeatureAvailability() {
        open(new View("index", "/", "analysis", "fileMenuButton"), "en", 1440, List.of());
        wait.until(driver -> "true".equals(page.driver().findElement(By.id("projectOpenAction")).getDomProperty("hidden")));
        assertThat(page.driver().findElement(By.cssSelector("[data-app-route='projects']")).isDisplayed())
                .as("The shared project destination follows the same availability as the existing Project menu")
                .isFalse();
        assertThat(page.driver().findElement(By.cssSelector("[data-app-route='architecture']")).isDisplayed()).isFalse();
        assertThat(page.driver().findElement(By.cssSelector("[data-app-route='analysis']")).isDisplayed()).isTrue();
        assertThat(page.driver().findElement(By.id("fileMenuButton")).isDisplayed()).isTrue();
    }

    @Test void selectingProjectSnapshotsSeparatesCurrentTextFromEvidenceAndClearsPreviousSource() {
        page.clearRoutes(); page.clearRequests();
        page.driver().manage().window().setSize(new Dimension(1440, 1000));
        var first = Map.of("id", 42, "projectKey", "P-42", "title", "Current contribution", "status", "ACTIVE",
                "requirementCount", 2, "solutionCount", 0);
        var later = Map.of("id", 63, "projectKey", "P-63", "title", "Later contribution", "status", "ACTIVE",
                "requirementCount", 0, "solutionCount", 0);
        var requirement = Map.of("id", 7, "requirementKey", "REQ-7", "title", "Access requirement", "reviewStatus", "PROPOSED",
                "currentVersion", Map.of("id", 22, "versionNumber", 2, "text", "Changed current requirement"));
        var other = Map.of("id", 8, "requirementKey", "REQ-8", "title", "Separate requirement", "reviewStatus", "PROPOSED",
                "currentVersion", Map.of("id", 33, "versionNumber", 3, "text", "Separate current requirement"));
        var summary = Map.of("id", "frozen-7", "projectId", 42, "requirementId", 7, "requirementVersionId", 11,
                "requirementVersionNumber", 1, "status", "SUCCESS", "workspaceId", "private-a", "branchName", "review/current");
        page.route("GET", "/taxonomy/api/projects", request -> json(200, List.of(first, later)));
        page.route("GET", "/taxonomy/api/products", request -> json(200, List.of()));
        page.route("GET", "/taxonomy/api/projects/42/portfolio", request -> json(200,
                Map.of("project", first, "requirements", List.of(requirement, other), "metrics", Map.of())));
        page.route("GET", "/taxonomy/api/projects/63/portfolio", request -> json(200,
                Map.of("project", later, "requirements", List.of(), "metrics", Map.of())));
        page.route("GET", "/taxonomy/api/projects/42/requirements/7/analyses", request -> json(200, List.of(summary)));
        page.route("GET", "/taxonomy/api/projects/42/requirements/8/analyses", request -> json(200, List.of()));
        page.route("GET", "/taxonomy/api/projects/42/requirements/7/analyses/frozen-7", request -> json(200, summary));
        page.route("GET", "/taxonomy/api/projects/42/snapshots/frozen-7", request -> json(200,
                Map.of("summary", summary, "elementMappings", List.of())));
        page.open("projects", "/taxonomy", "/projects", "en", """
                localStorage.clear();sessionStorage.clear();
                history.replaceState(null,'','?lang=en&projectId=42&requirementId=7&requirementVersionId=22&repositoryId=architecture-a&workspaceId=private-a&workspaceScopeKey=private-a&branch=review%2Fcurrent');
                """);
        wait.until(ExpectedConditions.textToBe(By.id("selectedProjectKey"), "P-42"));
        click(By.cssSelector(".requirement-snapshots[data-requirement-id='7']"));
        wait.until(ExpectedConditions.presenceOfElementLocated(By.id("inventoryComparison")));
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("portfolioBusy")));
        assertThat(page.driver().executeScript("""
                const c=TaxonomyAppChrome.getContext();
                return [c.requirement?.id,c.requirement?.key,c.requirement?.version,
                    c.source?.requirementVersionId,c.source?.requirementVersion,c.source?.snapshotId].join('|');
                """)).isEqualTo("7|REQ-7|2|11|1|frozen-7");
        assertThat(page.driver().findElement(By.cssSelector("[data-app-route='architecture']")).getDomAttribute("href"))
                .contains("sourceSnapshotId=frozen-7", "sourceRequirementVersionId=11");
        click(By.id("requirements-tab"));
        click(By.cssSelector(".requirement-snapshots[data-requirement-id='8']"));
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("portfolioBusy")));
        assertThat(page.driver().executeScript("return TaxonomyAppChrome.getContext().requirement?.key;")).isEqualTo("REQ-8");
        assertThat(page.driver().executeScript("return TaxonomyAppChrome.getContext().source;")).isNull();
        assertThat(page.driver().findElement(By.id("snapshotDetail")).getText()).doesNotContain("frozen-7");
        click(By.cssSelector(".project-select[data-project-id='63']"));
        wait.until(ExpectedConditions.textToBe(By.id("selectedProjectKey"), "P-63"));
        assertThat(page.driver().executeScript("return TaxonomyAppChrome.getContext().requirement;")).isNull();
        assertThat(page.driver().findElement(By.cssSelector("[data-app-route='architecture']")).getDomAttribute("href"))
                .contains("projectId=63").doesNotContain("sourceSnapshotId", "sourceRequirementId", "requirementId=", "requirementVersionId=");
    }

    private static void click(By locator) {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("portfolioBusy")));
        var element = wait.until(ExpectedConditions.elementToBeClickable(locator));
        page.driver().executeScript("arguments[0].scrollIntoView({block:'center',behavior:'instant'});", element);
        element.click();
    }

    @Test void sharedEntityDetailsKeepCurrentStateSeparateFromFrozenEvidenceAndRetainDraftFocus() {
        open(new View("portfolio-versioning", "/projects/42/versioning", "projects", "commitMessage"), "en", 1440);
        assertThat(page.driver().executeScript("return !!window.TaxonomyAppChrome;"))
                .as("Existing pages expose a common entity and context renderer").isEqualTo(true);
        var draft = page.driver().findElement(By.id("commitMessage"));
        draft.sendKeys("Keep this unsaved description");
        page.driver().executeScript("""
                TaxonomyAppChrome.updateContext({
                    architecture:{repositoryId:'architecture-a',branch:'review/current',workspaceScopeKey:'private-a',modelStand:'revision-14'},
                    project:{id:42,key:'PROJECT-42',title:'Later contribution'},
                    requirement:{id:7,key:'REQ-7',title:'Access requirement',version:2},
                    editable:true,
                    source:{projectId:12,requirementId:7,requirementVersionId:9,requirementVersion:1,snapshotId:'frozen-7'}
                });
                const host=document.createElement('section');host.id='entity-contract';document.body.append(host);
                TaxonomyAppChrome.renderEntity(host,{kind:'requirement',key:'REQ-7',title:'Access requirement',
                    status:'REVIEW_REQUIRED',version:2,details:[{label:'Reviewed version',value:'1'}]});
                """);
        assertThat(draft.getDomProperty("value")).isEqualTo("Keep this unsaved description");
        assertThat(page.driver().switchTo().activeElement()).isEqualTo(draft);
        assertThat(page.driver().findElement(By.id("applicationCurrentContext")).getText())
                .contains("architecture-a", "review/current", "revision-14", "PROJECT-42", "REQ-7", "2");
        assertThat(page.driver().findElement(By.id("applicationSourceContext")).getText())
                .contains("frozen-7", "1").doesNotContain("revision-14");
        assertThat(page.driver().findElement(By.id("entity-contract")).getText())
                .contains("REQ-7", "Access requirement", "Review required", "Reviewed version", "1");
        assertThat(page.driver().findElements(By.cssSelector("#entity-contract dl dt"))).isNotEmpty();
        assertThat(page.driver().executeScript("""
                let changes=0;
                const listener=()=>changes++;
                document.addEventListener('taxonomy:entity-context-changed',listener);
                const copy=TaxonomyAppChrome.getContext();
                copy.requirement.title='A caller must not mutate displayed state';
                TaxonomyAppChrome.updateContext({requirement:{version:2}});
                TaxonomyAppChrome.updateContext({requirement:{version:3}});
                TaxonomyAppChrome.updateContext({requirement:{version:3}});
                document.removeEventListener('taxonomy:entity-context-changed',listener);
                return changes===1 && TaxonomyAppChrome.getContext().requirement.title==='Access requirement';
                """)).as("Only genuine state changes notify feature integrations").isEqualTo(true);
        page.driver().executeScript("document.querySelector('[data-app-route=projects]').focus();");
        new Actions(page.driver()).sendKeys(Keys.TAB).perform();
        assertThat(page.driver().switchTo().activeElement().getDomAttribute("data-app-route")).isEqualTo("analysis");
    }

    private static final String COMPACT_REPOSITORY = "05f0a13b-7669-492d-a3e8-f10ce133dacf";
    private static final String COMPACT_WORKSPACE = "01737bd0-9d7f-466c-be5b-5fbc0c1fbdb7";
    private static final String COMPACT_SOURCE_WORKSPACE = "b85f3485-151a-46c3-9506-4597f3bb67fb";
    private static final String COMPACT_SNAPSHOT = "b3de5cd8-2d12-44ab-8185-c8784a879bdd";
    private static final String COMPACT_COMMIT = "ab".repeat(20);

    @ParameterizedTest @ValueSource(strings = {"en", "de"})
    void requirementBusinessContextKeepsTechnicalIdentityBehindItsDisclosure(String locale) {
        openCompactRequirement(locale, 1366, true);
        var current = page.driver().findElement(By.id("applicationCurrentContext"));
        var source = page.driver().findElement(By.id("applicationSourceContext"));
        assertThat(current.getText())
                .as("Business context names the selected architecture, project and current requirement version")
                .contains("Control architecture", "PROJECT", "REQ-2", "2")
                .doesNotContain(COMPACT_REPOSITORY, COMPACT_WORKSPACE, COMPACT_COMMIT, "912345");
        assertThat(source.getText())
                .as("The older analysis remains visibly separate from the current requirement")
                .contains("PROJECT", "REQ-2", "1")
                .doesNotContain(COMPACT_SOURCE_WORKSPACE, COMPACT_SNAPSHOT, "812345", COMPACT_COMMIT);
        var technical = page.driver().findElement(By.id("applicationTechnicalContext"));
        assertThat(technical.isDisplayed()).as("Full identities remain available on demand").isTrue();
        assertThat(technical.getDomProperty("open")).isEqualTo("false");
        var summary = technical.findElement(By.tagName("summary"));
        assertThat(summary.getAccessibleName()).isNotBlank();
        summary.sendKeys(Keys.ENTER);
        assertThat(technical.getDomProperty("open")).isEqualTo("true");
        assertThat(page.driver().findElement(By.id("applicationTechnicalDetails")).getText())
                .contains(COMPACT_REPOSITORY, COMPACT_WORKSPACE, COMPACT_COMMIT, "912345",
                        COMPACT_SOURCE_WORKSPACE, COMPACT_SNAPSHOT, "812345");
        summary.sendKeys(Keys.ENTER);
        assertThat(technical.getDomProperty("open")).isEqualTo("false");
        assertThat(page.driver().switchTo().activeElement()).isEqualTo(summary);
    }

    @ParameterizedTest @ValueSource(ints = {1366, 390})
    void collapsedLifecycleLeavesTheSelectedRequirementAsThePrimaryWork(int width) throws IOException {
        openCompactRequirement("en", width, false);
        var lifecycle = page.driver().findElement(By.id("architectureLifecycle"));
        var disclosure = lifecycle.findElement(By.xpath("./details"));
        var summary = disclosure.findElement(By.xpath("./summary"));
        assertThat(disclosure.getDomProperty("open")).isEqualTo("false");
        assertThat(lifecycle.findElements(By.tagName("button")))
                .as("Current-state lifecycle commands belong to the closed disclosure, not a second toolbar")
                .noneMatch(button -> button.isDisplayed());
        var geometry = (Map<?, ?>) page.driver().executeScript("""
                const summary=arguments[0].getBoundingClientRect();
                const heading=document.getElementById('requirementHeading').getBoundingClientRect();
                const action=document.getElementById('newVersionButton').getBoundingClientRect();
                const valid=summary.height>=44 && (innerWidth>=768 || action.height>=44)
                    && document.documentElement.scrollWidth<=innerWidth+1
                    && scrollY===0 && (innerWidth<1000 ||
                        (heading.top>=0 && heading.top<=innerHeight/2 && heading.bottom<=innerHeight
                            && action.top>=0 && action.bottom<=innerHeight));
                const bounds=element=>{
                    const r=element.getBoundingClientRect(),s=getComputedStyle(element);
                    return {tag:element.tagName,id:element.id,classes:element.className,
                        top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height,
                        minWidth:s.minWidth,minHeight:s.minHeight,overflowX:s.overflowX,
                        whiteSpace:s.whiteSpace,position:s.position,font:s.font};
                };
                return {valid,innerWidth,innerHeight,outerWidth,outerHeight,scrollX,scrollY,
                    documentWidth:document.documentElement.scrollWidth,bodyWidth:document.body.scrollWidth,
                    active:document.activeElement?.id||document.activeElement?.tagName,
                    summary:bounds(arguments[0]),heading:bounds(document.getElementById('requirementHeading')),
                    action:bounds(document.getElementById('newVersionButton')),
                    regions:['applicationNavigation','applicationContext','architectureLifecycle','requirementMain']
                        .map(id=>bounds(document.getElementById(id))),
                    overflowing:[...document.body.querySelectorAll('*')].filter(element=>{
                        const r=element.getBoundingClientRect();
                        return r.width>0 && r.height>0 && (r.right>innerWidth+1 || r.left< -1);
                    }).slice(0,30).map(bounds)};
                """, summary);
        if (!Boolean.TRUE.equals(geometry.get("valid"))) {
            Path evidence = Path.of("target", "coherent-lifecycle", "chrome-layout-" + width);
            Files.createDirectories(evidence);
            Files.write(evidence.resolve("viewport.png"), page.driver().getScreenshotAs(OutputType.BYTES));
            Files.writeString(evidence.resolve("geometry.json"), PortfolioClientTestPage.json(geometry));
            Files.writeString(evidence.resolve("page.html"), page.driver().getPageSource());
        }
        assertThat(geometry.get("valid"))
                .as("The compact context leaves the selected requirement and its action visible without shrinking touch targets: %s", geometry)
                .isEqualTo(true);
        summary.sendKeys(Keys.ENTER);
        assertThat(disclosure.getDomProperty("open")).isEqualTo("true");
        assertThat(lifecycle.findElements(By.cssSelector("tbody tr"))).hasSize(19);
        var create = lifecycle.findElement(By.cssSelector("[data-lifecycle-action='new-architecture']"));
        assertThat(create.isDisplayed()).isTrue();
        assertThat(page.driver().executeScript("""
                return arguments[0].getBoundingClientRect().height>=44
                    && document.documentElement.scrollWidth<=innerWidth+1;
                """, create)).as("Expanded commands remain full-size and do not create mobile page overflow").isEqualTo(true);
        summary.sendKeys(Keys.ENTER);
        assertThat(disclosure.getDomProperty("open")).isEqualTo("false");
        assertThat(create.isDisplayed()).isFalse();
        assertThat(page.driver().switchTo().activeElement()).isEqualTo(summary);
    }

    private static void openCompactRequirement(String locale, int width, boolean withSource) {
        var fixture = new PortfolioRequirementFixture(page);
        page.driver().manage().window().setSize(new Dimension(width, width < 1000 ? 844 : 768));
        var currentVersion = PortfolioRequirementFixture.version(912345, 2, "Current control requirement");
        var previousVersion = PortfolioRequirementFixture.version(812345, 1, "Earlier control requirement");
        var requirement = new java.util.LinkedHashMap<>(fixture.requirement.get());
        requirement.put("currentVersionId", 912345);
        requirement.put("currentVersion", currentVersion);
        fixture.requirement.set(requirement);
        fixture.versions.set(List.of(currentVersion, previousVersion));
        page.route("GET", "/api/ai-automation", request -> json(200, new AiAutomationStatus(
                AiCostPolicy.METERED, false, false, false, false, null, null,
                AnalysisAutomationProfile.FULL, AnalysisAutomationProfile.FULL, 1, 1, 100,
                "No provider configured for this browser fixture.", List.of(), List.of("Human review remains required."))));
        page.route("GET", PortfolioRequirementFixture.REQUIREMENT + "/copilot/latest", request ->
                new PortfolioClientTestPage.Response(204, "application/json", ""));
        var requirements = java.util.stream.IntStream.rangeClosed(1, 19).mapToObj(number -> Map.<String, Object>ofEntries(
                Map.entry("id", "control-requirement-" + number), Map.entry("projectId", 1), Map.entry("projectKey", "PROJECT"),
                Map.entry("requirementId", number), Map.entry("requirementKey", "REQ-" + number),
                Map.entry("title", number == 2 ? "Pump control" : "Control requirement " + number),
                Map.entry("version", 2), Map.entry("versionId", number == 2 ? 912345 : 1000 + number),
                Map.entry("contentHash", "fixture-hash-" + number), Map.entry("text", "Authored browser fixture " + number),
                Map.entry("realization", Map.of("state", "REVIEW_REQUIRED")), Map.entry("contributors", List.of()), Map.entry("links", List.of()))).toList();
        page.route("GET", "/api/architecture/lifecycle", request -> json(200, Map.of(
                "context", Map.of("repositoryId", COMPACT_REPOSITORY, "workspaceScopeKey", COMPACT_WORKSPACE,
                        "branch", "draft", "actor", "qa-client", "writeMode", "WORKSPACE",
                        "commit", COMPACT_COMMIT, "revision", 4),
                "architectureTitle", "Control architecture", "source", "WORKSPACE_AND_PORTFOLIO", "projectionState", "READY",
                "publicationState", "CHECKPOINTED", "mayEdit", true,
                "projects", List.of(PortfolioRequirementFixture.project()), "dependencies", List.of(), "requirements", requirements)));
        if (withSource) {
            var snapshot = Map.ofEntries(
                    Map.entry("id", COMPACT_SNAPSHOT), Map.entry("projectId", 1), Map.entry("requirementId", 2),
                    Map.entry("requirementVersionId", 812345), Map.entry("requirementVersionNumber", 1),
                    Map.entry("status", "SUCCESS"), Map.entry("workspaceId", COMPACT_SOURCE_WORKSPACE),
                    Map.entry("branchName", "review/source"), Map.entry("provider", "fixture"),
                    Map.entry("createdAt", "2026-10-07T09:00:00Z"));
            page.route("GET", PortfolioRequirementFixture.REQUIREMENT + "/snapshots", request -> json(200, List.of(snapshot)));
            page.route("GET", PortfolioRequirementFixture.ROOT + "/snapshots/" + COMPACT_SNAPSHOT, request ->
                    json(200, Map.of("summary", snapshot, "elementMappings", List.of(), "relations", List.of())));
        }
        page.open("requirement-detail", "", "/projects/1/requirements/2", locale, """
                localStorage.clear();sessionStorage.clear();
                history.replaceState(null,'','?lang=%s&repositoryId=%s&workspaceId=%s&workspaceScopeKey=%s&branch=draft');
                """.formatted(locale, COMPACT_REPOSITORY, COMPACT_WORKSPACE, COMPACT_WORKSPACE));
        wait.until(ExpectedConditions.textToBe(By.id("requirementHeading"), "Pump control"));
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("detailBusy")));
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("copilotPolicy"),
                "No provider configured for this browser fixture."));
        assertThat(page.driver().findElement(By.id("copilotError")).isDisplayed())
                .as("The complete read fixture does not create an unrelated startup error or move focus").isFalse();
        wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("""
                const c=window.TaxonomyAppChrome?.getContext();
                return c?.architecture?.title==='Control architecture' && c?.requirement?.versionId===912345
                    && !!window.TaxonomyArchitectureLifecycle?.state()?.requirements?.length;
                """)));
        if (withSource) wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript(
                "return TaxonomyAppChrome.getContext().source?.snapshotId===arguments[0];", COMPACT_SNAPSHOT)));
        assertThat(page.driver().findElement(By.id("detailError")).isDisplayed()).isFalse();
    }
}

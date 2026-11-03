package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.taxonomy.PortfolioClientTestPage.json;
import static org.assertj.core.api.Assertions.assertThat;

/** Full production Thymeleaf composition and scripts, with deterministic source HTTP fixtures. */
@Tag("browser")
@Tag("ci-app-shard-browser")
class ArchitectureWorkspaceBrowserIT {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TEXT = "Retained analysis A: secure hospital communications.";
    private static final String COMMIT = "a".repeat(40);
    private static PortfolioClientTestPage page;
    private static WebDriverWait wait;

    @RegisterExtension static final TestWatcher failureEvidence = new TestWatcher() {
        @Override public void testFailed(ExtensionContext context, Throwable failure) {
            if (page == null) return;
            try {
                Path directory = Path.of("target", "unified-architecture-acceptance", "failures",
                        context.getRequiredTestMethod().getName() + "-" + context.getDisplayName().replaceAll("[^A-Za-z0-9_-]", "_"));
                Files.createDirectories(directory);
                Files.write(directory.resolve("viewport.png"), page.driver().getScreenshotAs(OutputType.BYTES));
                Files.writeString(directory.resolve("page.html"), page.driver().getPageSource());
                Files.writeString(directory.resolve("state.json"), json(page.driver().executeScript("""
                        return {url:location.href,source:window.TaxonomyArchitectureWorkspace?.getSource(),
                            session:window.TaxonomyAnalysisSession?.state(),errors:window.__integrationErrors,
                            lensBounds:(()=>{const r=document.getElementById('architectureSourceLensTitle')?.getBoundingClientRect();return r?{top:r.top,bottom:r.bottom,innerHeight,scrollY}:null;})(),
                            sourceError:document.getElementById('architectureWorkspaceError')?.textContent,
                            editorError:document.getElementById('editorError')?.textContent,
                            lensError:document.getElementById('architectureSourceLensError')?.textContent};
                        """)));
                Files.writeString(directory.resolve("requests.json"), json(page.requests()));
            } catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
        }
    };

    @BeforeAll static void openBrowser(@TempDir Path downloads) throws Exception {
        page = new PortfolioClientTestPage(downloads);
        wait = new WebDriverWait(page.driver(), Duration.ofSeconds(20));
    }

    @AfterAll static void closeBrowser() { if (page != null) page.close(); }

    private static Map<String, String> query(String query) {
        var result = new LinkedHashMap<String, String>();
        if (query != null) for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(String value) { return JSON.readValue(value, Map.class); }

    private static Map<String, Object> scope(String repository, String workspace, String branch) {
        return Map.of("repositoryId", repository, "workspaceScopeKey", workspace,
                "workspaceId", workspace.equals("__shared__") ? "" : workspace, "branch", branch);
    }

    private static Map<String, Object> scene(String title) {
        return Map.of("width", 1100, "height", 420, "nodes", List.of(
                Map.of("id", "CP-1000", "label", title, "type", "Capabilities", "layer", 0,
                        "anchor", true, "x", 100, "y", 100, "width", 230, "height", 86),
                Map.of("id", "CR-1000", "label", "Supporting service", "type", "Services", "layer", 1,
                        "anchor", false, "x", 550, "y", 100, "width", 230, "height", 86)),
                "edges", List.of(Map.of("id", "supports", "sourceId", "CP-1000", "targetId", "CR-1000",
                        "relationType", "SUPPORTS", "sourceX", 330, "sourceY", 143, "targetX", 550, "targetY", 143)));
    }

    private static Map<String, Object> editorView(Map<String, String> parameters) {
        var context = new LinkedHashMap<String, Object>();
        context.putAll(scope(parameters.getOrDefault("repositoryId", "repository-B"),
                parameters.getOrDefault("workspaceScopeKey", "private-B"), parameters.getOrDefault("branch", "draft/B")));
        context.putAll(Map.of("actor", "acceptance", "revision", 7, "commit", COMMIT, "writeMode", "PRIVATE_WORKSPACE"));
        var element = Map.of("id", "CP-1000", "type", "capability", "title", "Working capability B",
                "description", "Editable architecture fixture", "properties", Map.of(), "extensions", Map.of());
        return Map.of("document", Map.of("context", context, "source", "WORKSPACE_REVISION", "projectionState", "READY",
                        "dsl", "capability CP-1000 { title \"Working capability B\" }", "history", List.of(), "versions", List.of()),
                "mayEdit", true, "model", Map.of("elements", List.of(element), "relations", List.of(), "evidence", List.of(),
                        "packages", List.of(), "views", List.of(), "requirements", List.of()), "scene", scene("Working capability B"),
                "searchIndex", List.of(Map.of("id", "CP-1000", "text", "cp-1000 working capability b")),
                "schema", Map.of("elementTypes", List.of("capability"), "relationTypes", List.of("SUPPORTS"),
                        "relationStatuses", List.of("proposed", "accepted", "rejected"), "layoutStrategy", "stable", "layoutRevision", 0));
    }

    private static Map<String, Object> snapshot(Map<String, String> parameters) {
        return Map.ofEntries(Map.entry("projectId", 42), Map.entry("snapshotId", "snapshot-B"),
                Map.entry("projectKey", "PROJECT-B"), Map.entry("requirementId", 7), Map.entry("requirementKey", "REQ-B"),
                Map.entry("requirementVersionNumber", 2), Map.entry("requirementTitle", "Frozen requirement B"),
                Map.entry("requirementText", "Frozen requirement B\n" + "Retained authored detail\n".repeat(60)),
                Map.entry("snapshotStatus", "SUCCESS"), Map.entry("policyTitleKey", "archview.policy.title.defaultImpact"),
                Map.entry("workspaceId", parameters.getOrDefault("workspaceId", "")),
                Map.entry("branchName", "review/B"), Map.entry("commitSha", COMMIT),
                Map.entry("exportProvenance", Map.of("repositoryId", "repository-B", "requirementVersionId", 11)),
                Map.entry("scene", scene("Frozen capability B")), Map.entry("elements", Map.of()),
                Map.entry("relations", Map.of()), Map.entry("warnings", List.of()));
    }

    private static void open(String locale, int width) {
        page.clearRoutes(); page.clearRequests();
        var cdp = (HasCdp) new Augmenter().augment(page.driver());
        cdp.executeCdpCommand("Emulation.setDeviceMetricsOverride",
                Map.of("width", width, "height", width == 390 ? 844 : 1000, "deviceScaleFactor", 1, "mobile", false));
        cdp.executeCdpCommand("Emulation.setEmulatedMedia", Map.of("features",
                List.of(Map.of("name", "prefers-reduced-motion", "value", "reduce"))));
        var architecture = Map.of("viewTitle", "archview.policy.title.defaultImpact", "anchors", List.of("CP-1000"),
                "includedElements", List.of(
                        Map.of("nodeCode", "CP-1000", "title", "Analysis capability A", "taxonomySheet", "CP", "relevance", .9, "anchor", true),
                        Map.of("nodeCode", "CR-1000", "title", "Analysis service A", "taxonomySheet", "CR", "relevance", .7, "anchor", false)),
                "includedRelationships", List.of(Map.of("sourceCode", "CP-1000", "targetCode", "CR-1000", "relationType", "SUPPORTS")));
        var payload = new AtomicReference<>(Map.<String, Object>ofEntries(Map.entry("businessText", TEXT),
                Map.entry("lastAnalyzedText", TEXT), Map.entry("storedBusinessText", TEXT), Map.entry("draftState", "ACTIVE"),
                Map.entry("lastAnalysisStatus", "SUCCESS"), Map.entry("scores", Map.of("CP-1000", 90, "CR-1000", 70)),
                Map.entry("architectureView", architecture), Map.entry("currentView", "list")));
        var version = new AtomicInteger(3);
        page.route("GET", "/taxonomy/api/taxonomy", request -> json(200, List.of(
                Map.of("code", "CP", "name", "Capabilities", "children", List.of(Map.of("code", "CP-1000", "name", "Analysis capability A", "children", List.of()))),
                Map.of("code", "CR", "name", "Core Services", "children", List.of(Map.of("code", "CR-1000", "name", "Analysis service A", "children", List.of()))))));
        page.route("GET", "/taxonomy/api/capabilities", request -> json(200, Map.of("features", List.of("portfolio", "architecture", "analysis"))));
        page.route("GET", "/taxonomy/api/ai-status", request -> json(200, Map.of("available", false, "provider", "NONE", "availableProviders", List.of())));
        page.route("GET", "/taxonomy/api/projects", request -> json(200, List.of()));
        page.route("GET", "/taxonomy/api/analysis-runs", request -> json(200, List.of()));
        page.route("GET", "/taxonomy/api/workspace/current", request -> json(200, Map.of("workspaceId", "analysis-A")));
        page.route("GET", "/taxonomy/api/analysis-drafts/analysis-A", request -> json(200,
                Map.of("workspaceId", "analysis-A", "version", version.get(), "payload", payload.get())));
        page.route("PUT", "/taxonomy/api/analysis-drafts/analysis-A", request -> {
            var body = read(request.body());
            if (((Number) body.get("expectedVersion")).intValue() != version.get()) return json(409, Map.of("code", "VERSION_CONFLICT"));
            payload.set(read(JSON.writeValueAsString(body.get("payload"))));
            return json(200, Map.of("workspaceId", "analysis-A", "version", version.incrementAndGet(), "payload", payload.get()));
        });
        page.route("GET", "/taxonomy/api/architecture/lifecycle", request -> {
            var pins = query(request.query());
            var context = new LinkedHashMap<String, Object>();
            context.putAll(scope(pins.getOrDefault("repositoryId", "repository-A"), pins.getOrDefault("workspaceScopeKey", "analysis-A"),
                    pins.getOrDefault("branch", "draft/A")));
            context.putAll(Map.of("commit", COMMIT, "revision", 7));
            return json(200, Map.of("context", context, "architectureTitle", "Exact fixture architecture", "source", "WORKSPACE_AND_PORTFOLIO",
                    "projectionState", "READY", "publicationState", "CHECKPOINTED", "mayEdit", true,
                    "projects", List.of(), "dependencies", List.of(), "requirements", List.of()));
        });
        page.route("GET", "/taxonomy/api/architecture/editor", request -> json(200, editorView(query(request.query()))));
        page.route("GET", "/taxonomy/api/projects/42/architecture-workbench/snapshot-B", request -> json(200, snapshot(query(request.query()))));
        page.route("POST", "/taxonomy/api/architecture/focus/suggestions", request -> {
            var body = read(request.body());
            return json(200, Map.of("source", body.get("source"), "sourceId", body.get("sourceId"), "sourceHash", "b".repeat(64),
                    "method", "LOCAL_TEXT_UNASSESSED", "outcome", "LOCAL_CANDIDATES", "modelCalls", 0,
                    "candidateCount", 1, "suggestions", List.of(Map.of("id", "source-hypothesis", "sourceId", "CP-1000", "targetId", "CR-1000",
                            "relationType", "SUPPORTS", "rationale", "Deterministic retained evidence", "evidence", List.of(), "properties", Map.of()))));
        });
        page.open("index", "/taxonomy", "/", locale, """
                localStorage.clear();sessionStorage.clear();
                history.replaceState(null,'','?lang=%s&repositoryId=repository-A&workspaceId=analysis-A&workspaceScopeKey=analysis-A&branch=draft%%2FA#analyze');
                window.__integrationErrors=[];window.__sameDocument=crypto.randomUUID();
                addEventListener('error',e=>{if(e.error)__integrationErrors.push(String(e.error));});
                addEventListener('unhandledrejection',e=>__integrationErrors.push(String(e.reason)));
                """.formatted(locale));
        wait.until(ExpectedConditions.elementToBeClickable(By.id("onboardingDismiss"))).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
        wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("""
                return window.TaxonomyI18n?.isLoaded() && window.TaxonomyAnalysisSession?.state().ready
                    && document.getElementById('businessText').value===arguments[0];
                """, TEXT)));
    }

    private static void source(String kind, Map<String, Object> source) {
        assertThat(page.driver().executeAsyncScript("""
                const done=arguments[arguments.length-1];
                TaxonomyArchitectureWorkspace.open(arguments[0],{source:arguments[1]}).then(done,e=>done(String(e)));
                """, kind, source)).isEqualTo(true);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("tab-architecture")));
        assertAgreement(kind, source);
    }

    private static void assertAgreement(String kind, Map<String, Object> expected) {
        var agreement = (Map<?, ?>) page.driver().executeScript("""
                const source=TaxonomyArchitectureWorkspace.getSource(),url=new URL(location.href);
                return {kind:source.kind,urlKind:url.searchParams.get('architectureSource'),hash:url.hash,
                    source:Object.fromEntries(['repositoryId','workspaceId','workspaceScopeKey','branch'].map(k=>[k,source[k]])),
                    url:Object.fromEntries(['repositoryId','workspaceId','workspaceScopeKey','branch'].map(k=>[k,url.searchParams.get(k)]))};
                """);
        assertThat(agreement.get("kind")).isEqualTo(kind);
        assertThat(agreement.get("urlKind")).isEqualTo(kind);
        assertThat(agreement.get("hash")).isEqualTo("#architecture");
        var exact = new LinkedHashMap<String, Object>();
        List.of("repositoryId", "workspaceId", "workspaceScopeKey", "branch").forEach(key -> exact.put(key, expected.get(key)));
        assertThat(agreement.get("source")).isEqualTo(exact);
        assertThat(agreement.get("url")).isEqualTo(exact);
    }

    private static Map<String, Object> snapshotSource(String workspace) {
        var source = new LinkedHashMap<>(scope("repository-B", workspace, "review/B"));
        source.putAll(Map.of("projectId", "42", "requirementId", "7", "requirementVersionId", "11", "snapshotId", "snapshot-B"));
        return source;
    }

    private static void noScriptErrors() {
        assertThat(page.driver().executeScript("return window.__integrationErrors;")).isEqualTo(List.of());
    }

    @ParameterizedTest @CsvSource({"en,1440", "de,390"})
    void renderedApplicationHasOneNavigationAndLensOpensAnalysisInPlace(String locale, int width) throws IOException {
        open(locale, width);
        assertThat(page.driver().findElements(By.cssSelector("[data-app-route]")))
                .extracting(element -> element.getDomAttribute("data-app-route"))
                .containsExactly("projects", "analysis", "architecture", "graph", "versions", "dsl-editor");
        assertThat(page.driver().executeScript("""
                const counts=new Map();document.querySelectorAll('[id]').forEach(e=>counts.set(e.id,(counts.get(e.id)||0)+1));
                return [...counts].filter(([,count])=>count>1).map(([id])=>id);
                """)).as("Real Thymeleaf fragments must not introduce duplicate control IDs").isEqualTo(List.of());
        for (String id : List.of("architectureWorkspace", "architectureEditor", "architectureCanvas", "architectureSourceLens", "applicationNavigation"))
            assertThat(page.driver().findElements(By.id(id))).hasSize(1);
        assertThat(page.driver().executeScript("""
                const paths=[...document.scripts].filter(s=>s.src).map(s=>new URL(s.src).pathname);
                const suffixes=['shared/architecture-diagram.js','architecture-editor-renderer.js','architecture-editor.js',
                    'architecture-workbench.js','shared/architecture-source-lens.js','shared/architecture-workspace.js'];
                const positions=suffixes.map(suffix=>paths.findIndex(path=>path.endsWith('/'+suffix)));
                return suffixes.every(suffix=>paths.filter(path=>path.endsWith('/'+suffix)).length===1)
                    && positions.every((value,index)=>index===0||value>positions[index-1]);
                """)).as("Shared renderer, source adapters and workspace load once in dependency order").isEqualTo(true);
        assertThat(page.requests().stream().filter(request -> request.path().endsWith("/architecture/editor")
                || request.path().contains("/architecture-workbench/")).toList()).isEmpty();
        Object document = page.driver().executeScript("return __sameDocument;");
        var lens = page.driver().findElement(By.id("analysisLensAction"));
        lens.sendKeys(Keys.ENTER);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("architectureSourceLens")));
        wait.until(ExpectedConditions.elementToBeClickable(By.id("architectureSourceLensEnabled")));
        assertAgreement("analysis", scope("repository-A", "analysis-A", "draft/A"));
        assertThat(page.driver().findElement(By.id("businessText")).getDomProperty("value")).isEqualTo(TEXT);
        assertThat(page.driver().executeScript("return __sameDocument;")).isEqualTo(document);
        assertThat(page.driver().findElement(By.id("architectureWorkingSource")).isDisplayed()).isFalse();
        assertThat(page.driver().executeScript("return document.activeElement.id;")).isEqualTo("architectureSourceLensTitle");
        wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("""
                const rect=document.getElementById('architectureSourceLensTitle').getBoundingClientRect();
                // Native scrolling rounds to integer CSS pixels (observed top: -0.109375px).
                return rect.top>=-1 && rect.bottom<=innerHeight+1;
                """)));
        assertThat(page.driver().executeScript("return document.documentElement.scrollWidth<=innerWidth+1;")).isEqualTo(true);
        Path directory = Path.of("target", "unified-architecture-acceptance", locale + "-" + width);
        Files.createDirectories(directory);
        Files.write(directory.resolve("analysis-lens.png"), page.driver().getScreenshotAs(OutputType.BYTES));
        noScriptErrors();
    }

    @ParameterizedTest @ValueSource(strings = {"__shared__", "private-B"})
    void exactSourcesRetainSnapshotSelectionViewportAndAnalysisInput(String workspace) {
        open("en", 1440);
        var snapshot = snapshotSource(workspace);
        source("snapshot", snapshot);
        var node = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("#architectureCanvas [data-node-id='CP-1000']")));
        node.sendKeys(Keys.ENTER);
        page.driver().executeScript("TaxonomyArchitectureWorkspace.getDiagram().restoreViewport({x:23,y:41,k:.8});");
        assertThat(page.driver().findElement(By.id("architectureDetails")).getText()).contains("Frozen capability B");
        var working = scope("repository-B", "private-B", "draft/B");
        source("working", working);
        assertThat(page.driver().findElement(By.id("editorError")).isDisplayed()).isFalse();
        assertThat(page.driver().findElement(By.id("editorDsl")).getDomProperty("value")).contains("Working capability B");
        var contextSummary = page.driver().findElement(By.id("applicationContextSummary"));
        contextSummary.sendKeys(Keys.ENTER);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("architectureLifecycle")));
        assertThat(page.driver().executeScript("return TaxonomyAppChrome.getContext().architecture.workspaceScopeKey;"))
                .as("Working lifecycle controls expose the selected working scope").isEqualTo("private-B");
        assertThat(page.driver().findElement(By.id("editorCheckpoint")).isEnabled()).isTrue();
        contextSummary.sendKeys(Keys.ENTER);
        new Select(page.driver().findElement(By.id("architectureSource"))).selectByValue("snapshot");
        wait.until(driver -> "snapshot".equals(page.driver().executeScript("return TaxonomyArchitectureWorkspace.getSource().kind;")));
        assertAgreement("snapshot", snapshot);
        assertThat(page.driver().findElement(By.cssSelector("#architectureCanvas [data-node-id='CP-1000']")).getDomAttribute("aria-pressed")).isEqualTo("true");
        assertThat(page.driver().executeScript("return TaxonomyArchitectureWorkspace.getDiagram().captureViewport();"))
                .isEqualTo(Map.of("x", 23L, "y", 41L, "k", .8));
        assertThat(page.requests().stream().filter(request -> request.path().contains("/architecture-workbench/")).toList())
                .hasSize(1).allSatisfy(request -> {
                    assertThat(query(request.query())).containsEntry("workspaceScopeKey", workspace)
                            .containsEntry("workspaceId", workspace.equals("__shared__") ? "" : workspace)
                            .containsEntry("repositoryId", "repository-B").containsEntry("branch", "review/B");
                    assertThat(request.header("X-Taxonomy-Workspace-Id")).isEqualTo(workspace.equals("__shared__") ? "" : workspace);
                });
        new Select(page.driver().findElement(By.id("architectureSource"))).selectByValue("analysis");
        wait.until(driver -> "analysis".equals(page.driver().executeScript("return TaxonomyArchitectureWorkspace.getSource().kind;")));
        assertAgreement("analysis", scope("repository-A", "analysis-A", "draft/A"));
        assertThat(page.driver().findElement(By.id("businessText")).getDomProperty("value")).isEqualTo(TEXT);
        assertThat(page.driver().executeScript("return TaxonomyState.currentArchView.includedElements[0].title;")).isEqualTo("Analysis capability A");
        noScriptErrors();
    }

    @Test void hiddenWorkingDraftAndRefusedHistoryNavigationKeepUrlAndDisplayedSourceTogether() {
        open("en", 1440);
        var first = scope("repository-B", "private-B", "draft/B");
        var next = scope("repository-C", "private-C", "draft/C");
        source("working", first);
        page.driver().findElement(By.id("editorTitle")).sendKeys("Retain my unaccepted draft");
        page.driver().executeScript("window.__guardCalls=0;window.confirm=()=>{__guardCalls++;return false;};");
        source("analysis", scope("repository-A", "analysis-A", "draft/A"));
        assertThat(page.driver().executeAsyncScript("""
                const done=arguments[arguments.length-1];TaxonomyArchitectureWorkspace.open('working',{source:arguments[0]}).then(done);
                """, next)).isEqualTo(false);
        assertAgreement("analysis", scope("repository-A", "analysis-A", "draft/A"));
        assertThat(page.driver().executeScript("return __guardCalls;")).isEqualTo(1L);
        source("working", first);
        assertThat(page.driver().findElement(By.id("editorTitle")).getDomProperty("value")).isEqualTo("Retain my unaccepted draft");
        page.driver().executeScript("window.confirm=()=>true;");
        source("working", next);
        page.driver().findElement(By.id("editorTitle")).sendKeys("Draft for C");
        page.driver().executeScript("window.confirm=()=>{__guardCalls++;return false;};");
        page.driver().navigate().back();
        wait.until(driver -> ((Number) page.driver().executeScript("return __guardCalls;")).intValue() == 2);
        assertAgreement("working", next);
        assertThat(page.driver().findElement(By.id("editorTitle")).getDomProperty("value")).isEqualTo("Draft for C");
        page.driver().executeScript("window.confirm=()=>true;");
        source("working", first);
        page.driver().navigate().back();
        wait.until(driver -> "private-C".equals(page.driver().executeScript("return TaxonomyArchitectureWorkspace.getSource().workspaceScopeKey;")));
        assertAgreement("working", next);
        page.driver().navigate().forward();
        wait.until(driver -> "private-B".equals(page.driver().executeScript("return TaxonomyArchitectureWorkspace.getSource().workspaceScopeKey;")));
        assertAgreement("working", first);
        noScriptErrors();
    }

    @Test void snapshotLensIsReadOnlyPrintRetainsEvidenceAndAdoptionRemainsAnExplicitPreview() {
        open("de", 390);
        var snapshot = snapshotSource("__shared__");
        source("snapshot", snapshot);
        String evidence = (String) page.driver().executeScript("return JSON.stringify(TaxonomyArchitectureWorkspace.getData());");
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("#architectureCanvas [data-node-id='CP-1000']"))).sendKeys(Keys.ENTER);
        wait.until(ExpectedConditions.elementToBeClickable(By.id("architectureSourceLensEnabled")));
        var useAi = page.driver().findElement(By.id("architectureSourceLensUseAi"));
        if (useAi.isSelected()) useAi.click();
        page.driver().findElement(By.id("architectureSourceLensEnabled")).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.id("architectureSourceLensInspect"))).click();
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("#architectureSourceLensSuggestions button"))).click();
        assertThat(page.driver().findElement(By.id("architectureSourceLensReview")).getText())
                .contains("Deterministic retained evidence", "snapshot-B", "b".repeat(64));
        assertThat(page.driver().findElements(By.cssSelector("#architectureSourceLensReview [data-focus-action='accept'], #architectureSourceLensReview [data-focus-action='undo']")))
                .as("Frozen evidence has no writable editor acceptance controls").isEmpty();
        var investigation = page.requests().stream().filter(request -> request.path().endsWith("/architecture/focus/suggestions")).toList();
        assertThat(investigation).hasSize(1).allSatisfy(request -> {
            var body = read(request.body());
            assertThat(body.get("useAi")).isEqualTo(false);
            assertThat(body.get("sourceId")).isEqualTo("CP-1000");
            assertThat(body).doesNotContainKeys("context", "graph", "model");
            assertThat(request.header("If-Match")).isNull();
            assertThat(request.header("X-Taxonomy-Workspace-Id")).isEmpty();
            assertThat(body.get("source")).isEqualTo(Map.of("kind", "ANALYSIS_SNAPSHOT", "repositoryId", "repository-B",
                    "workspaceScopeKey", "__shared__", "branch", "review/B", "projectId", 42, "requirementId", 7,
                    "requirementVersionId", 11, "snapshotId", "snapshot-B"));
        });
        assertThat(page.driver().executeScript("return JSON.stringify(TaxonomyArchitectureWorkspace.getData());")).isEqualTo(evidence);

        page.driver().findElement(By.cssSelector("#architectureExportOptions > summary")).sendKeys(Keys.ENTER);
        for (String id : List.of("downloadArchitectureSvg", "downloadArchitecturePdf", "downloadArchitectureWord",
                "downloadDecisionWord", "downloadArchitectureArchiMate", "downloadArchitectureVisio")) {
            assertThat(page.driver().findElement(By.id(id)).isDisplayed()).as("%s remains reachable in the embedded snapshot", id).isTrue();
            assertThat(page.driver().findElement(By.id(id)).isEnabled()).isTrue();
        }
        page.driver().findElement(By.cssSelector("#architectureExportOptions > summary")).sendKeys(Keys.ESCAPE);
        assertThat(page.driver().findElement(By.id("printArchitectureView")).isEnabled()).isTrue();
        Object viewport = page.driver().executeScript("return TaxonomyArchitectureWorkspace.getDiagram().captureViewport();");
        page.driver().executeScript("window.dispatchEvent(new Event('beforeprint'));");
        assertThat(page.driver().findElements(By.cssSelector("#architecturePrintDiagram .architecture-node"))).hasSize(2);
        assertThat(page.driver().findElement(By.cssSelector("#architecturePrintDiagram .architecture-viewport")).getDomAttribute("transform")).isNull();
        assertThat(page.driver().findElement(By.id("architectureProvenance")).getDomProperty("textContent")).contains("snapshot-B", "review/B");
        page.driver().executeScript("window.dispatchEvent(new Event('afterprint'));");
        assertThat(page.driver().findElements(By.cssSelector("#architecturePrintDiagram svg"))).isEmpty();
        assertThat(page.driver().executeScript("return TaxonomyArchitectureWorkspace.getDiagram().captureViewport();")).isEqualTo(viewport);

        page.driver().findElement(By.cssSelector("#architectureSourceLensReview [data-focus-action='adopt']")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("snapshotAdoption")));
        assertThat(page.driver().findElement(By.id("adoptionPreview")).isDisplayed()).isTrue();
        assertThat(page.driver().findElement(By.id("adoptionAccept")).isDisplayed()).isFalse();
        assertThat(page.driver().getCurrentUrl()).contains("architectureSource=working", "sourceSnapshotId=snapshot-B",
                "sourceWorkspaceScopeKey=__shared__", "sourceRepositoryId=repository-B", "sourceRequirementVersionId=11");
        assertThat(page.requests().stream().filter(request -> request.path().endsWith("/editor/commands")
                || request.path().contains("/snapshot-adoptions")).toList()).isEmpty();
        for (String id : List.of("editorRefresh", "editorCheckpoint", "editorNew", "editorPreviewElement", "editorSavePackage"))
            assertThat(page.driver().findElement(By.id(id)).isEnabled()).as("%s remains available in the working source", id).isTrue();
        for (String id : List.of("editorPlanningForm", "editorRelationForm", "editorHistory", "editorVersions", "editorResumeCheckpoint", "editorRecoverVersion"))
            assertThat(page.driver().findElements(By.id(id))).as("%s survives fragment composition", id).hasSize(1);
        noScriptErrors();
    }
}

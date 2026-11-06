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
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.interactions.Actions;
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
                            snapshotLayout:window.__snapshotLayout,
                            fullscreen:window.__fullscreenEvidence,
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

    @ParameterizedTest @ValueSource(strings = {"en", "de"})
    void ownerOptsIntoTheExistingWorkingCopyWithAnExplicitPermissionNotice(String locale) {
        open(locale, 1440);
        var shared = new java.util.concurrent.atomic.AtomicBoolean();
        page.route("GET", "/taxonomy/api/architecture/editor", request -> {
            var result = new LinkedHashMap<>(editorView(query(request.query())));
            var document = new LinkedHashMap<>((Map<String,Object>) result.get("document"));
            var context = new LinkedHashMap<>((Map<String,Object>) document.get("context"));
            context.put("writeMode",shared.get() ? "SHARED_WORKSPACE" : "PRIVATE_WORKSPACE");
            document.put("context",context);result.put("document",document);
            result.put("collaboration",Map.of("enabled",shared.get(),"mayManage",true));
            return json(200,result);
        });
        page.route("POST", "/taxonomy/api/architecture/editor/collaboration", request -> {
            shared.set(Boolean.TRUE.equals(read(request.body()).get("enabled")));
            return json(200,Map.of("enabled",shared.get(),"mayManage",true));
        });
        source("working",scope("repository-B","private-B","draft/B"));
        assertThat(page.driver().findElement(By.id("editorCollaborationNotice")).getText())
                .contains(locale.equals("de") ? "Operationshistorie" : "operation history");
        page.driver().findElement(By.id("editorCollaborationToggle")).click();
        wait.until(driver -> shared.get() && page.driver().findElement(By.id("editorCollaborationLink")).isDisplayed());
        String link = page.driver().findElement(By.id("editorCollaborationLink")).getDomProperty("value");
        assertThat(link).contains("repositoryId=repository-B","workspaceId=private-B","branch=draft%2FB","architectureSource=working")
                .doesNotContain("revision=","commit=");
        var request = page.requests().stream().filter(item -> item.path().endsWith("/editor/collaboration")).findFirst().orElseThrow();
        assertThat(read(request.body())).containsEntry("enabled",true);
        assertThat((Map<String,Object>) read(request.body()).get("context")).containsEntry("revision",7);
        page.driver().findElement(By.id("editorCollaborationToggle")).click();
        wait.until(driver -> !shared.get() && !page.driver().findElement(By.id("editorCollaborationLink")).isDisplayed());
        noScriptErrors();
    }

    @ParameterizedTest @ValueSource(strings = {"en", "de"})
    void sharedRefreshRetainsTheDraftAndNeverTurnsADeletedUpdateIntoCreation(String locale) {
        open(locale, 1440);
        var revision = new AtomicInteger(7);
        page.route("GET", "/taxonomy/api/architecture/editor", request -> {
            var result = new LinkedHashMap<>(editorView(query(request.query())));
            var document = new LinkedHashMap<>((Map<String, Object>) result.get("document"));
            var context = new LinkedHashMap<>((Map<String, Object>) document.get("context"));
            context.put("writeMode", "SHARED_WORKSPACE"); context.put("revision", revision.get());
            document.put("context", context); result.put("document", document);
            result.put("collaboration", Map.of("enabled", true, "mayManage", false));
            if (revision.get() == 8) {
                var model = new LinkedHashMap<>((Map<String, Object>) result.get("model"));
                model.put("elements", List.of()); result.put("model", model);
                result.put("searchIndex", List.of());
                result.put("scene", Map.of("width", 1100, "height", 420, "nodes", List.of(), "edges", List.of()));
            }
            return json(200, result);
        });
        page.route("POST", "/taxonomy/api/architecture/editor/preview", request ->
                json(404, Map.of("code", "NOT_FOUND", "detail", "The edited element was deleted", "dependencies", List.of())));
        var target = new LinkedHashMap<>(scope("repository-B", "private-B", "draft/B"));
        target.put("element", "CP-1000"); source("working", target);
        var title = page.driver().findElement(By.id("editorTitle")); title.clear(); title.sendKeys("Retained local edit");
        page.driver().findElement(By.id("editorRationale")).sendKeys("Keep my target after another editor deletes it");
        revision.set(8);
        page.driver().executeScript("window.confirm=()=>true; document.getElementById('editorRefresh').click();");
        wait.until(driver -> Long.valueOf(8).equals(page.driver().executeScript("return TaxonomyArchitectureWorkspace.getData()?.document?.context?.revision;")));
        assertThat(page.driver().findElement(By.id("editorTitle")).getDomProperty("value")).isEqualTo("Retained local edit");
        assertThat(page.driver().executeScript("return TaxonomyArchitectureWorkspace.getSource().element;")).isEqualTo("CP-1000");
        page.driver().executeScript("document.getElementById('editorForm').requestSubmit();");
        wait.until(driver -> page.requests().stream().anyMatch(request -> request.path().endsWith("/editor/preview")));
        var request = page.requests().stream().filter(item -> item.path().endsWith("/editor/preview")).findFirst().orElseThrow();
        assertThat(read(request.body())).containsEntry("kind", "UPDATE_ELEMENT").containsEntry("id", "CP-1000");
        assertThat(page.driver().findElement(By.id("editorTitle")).getDomProperty("value")).isEqualTo("Retained local edit");
        noScriptErrors();
    }

    private static void resize(int width, int height) {
        ((HasCdp) new Augmenter().augment(page.driver())).executeCdpCommand("Emulation.setDeviceMetricsOverride",
                Map.of("width", width, "height", height, "deviceScaleFactor", 1, "mobile", false));
    }

    @ParameterizedTest @ValueSource(strings = {"en", "de"})
    void savedPackageScreenshotWaitsForTheNativeSmoothScroll(String locale) throws IOException {
        open(locale, 1600);
        resize(1600, 957);
        page.route("GET", "/taxonomy/api/architecture/editor", request -> {
            var view = new LinkedHashMap<>(editorView(query(request.query())));
            var model = new LinkedHashMap<>((Map<String, Object>) view.get("model"));
            model.put("packages", List.of(Map.of("id", "saved-package", "title", "Browser reviewed package",
                    "description", "Saved package for native screenshot inspection", "parentId", "", "position", 0)));
            view.put("model", model);
            return json(200, view);
        });
        var working = scope("repository-B", "private-B", "draft/B");
        source("working", working);
        new Select(page.driver().findElement(By.id("editorPackage"))).selectByValue("saved-package");
        assertThat(page.driver().findElement(By.id("editorPackageTitle")).getDomProperty("value"))
                .isEqualTo("Browser reviewed package");
        var cdp = (HasCdp) new Augmenter().augment(page.driver());
        cdp.executeCdpCommand("Emulation.setEmulatedMedia", Map.of("features",
                List.of(Map.of("name", "prefers-reduced-motion", "value", "no-preference"))));
        page.driver().executeScript("scrollTo({top:0,behavior:'instant'});");
        assertThat(page.driver().executeScript("return getComputedStyle(document.documentElement).scrollBehavior;"))
                .as("The original Bootstrap motion preference remains active").isEqualTo("smooth");
        assertThat(page.driver().executeScript("return document.getElementById('editorPackageTitle').getBoundingClientRect().top>innerHeight;"))
                .as("The saved package starts below the screenshot viewport").isEqualTo(true);

        ScenarioBrowserWalkthrough.showNativePackagePanel(page.driver(), new WebDriverWait(page.driver(), Duration.ofSeconds(45)));

        var geometry = page.driver().executeScript("""
                return {innerWidth,innerHeight,scrollY,scrollBehavior:getComputedStyle(document.documentElement).scrollBehavior,
                    controls:['editorPackageTitle','editorSavePackage','editorDeletePackage','editorMovePackage','editorPlaceElement','editorDetachElement']
                        .map(id=>({id,...document.getElementById(id).getBoundingClientRect().toJSON()}))};
                """);
        Path evidence = Path.of("target", "unified-architecture-acceptance", "saved-package-" + locale);
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("geometry.json"), json(geometry));
        Files.write(evidence.resolve("viewport.png"), page.driver().getScreenshotAs(OutputType.BYTES));
        assertAgreement("working", working);
        assertThat(page.driver().findElement(By.id("editorPackage")).getDomProperty("value")).isEqualTo("saved-package");
        assertThat(page.driver().findElement(By.id("editorPackageTitle")).getDomProperty("value"))
                .isEqualTo("Browser reviewed package");
        assertThat(page.requests().stream().filter(request -> request.path().equals("/taxonomy/api/architecture/editor")).toList()).hasSize(1);
        noScriptErrors();
    }

    private static void assertSnapshotViewport(String phase) {
        var geometry = (Map<?, ?>) page.driver().executeAsyncScript("""
                const done=arguments[arguments.length-1];scrollTo(0,0);
                requestAnimationFrame(()=>requestAnimationFrame(()=>{
                    const rect=selector=>{const element=document.querySelector(selector);if(!element)return null;
                        const r=element.getBoundingClientRect();return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height};};
                    const canvas=rect('#architectureCanvasShell'),details=rect('.workbench-detail-panel');
                    const titleRange=document.createRange();
                    titleRange.selectNodeContents(document.querySelector('.architecture-source-toolbar h2'));
                    const titleBounds=titleRange.getBoundingClientRect();
                    const sourceTitle={top:titleBounds.top,bottom:titleBounds.bottom,left:titleBounds.left,right:titleBounds.right};
                    const sourceActions=rect('#architectureSnapshotSource .architecture-source-actions');
                    const titleClearOfActions=sourceTitle.right<=sourceActions.left || sourceTitle.left>=sourceActions.right
                        || sourceTitle.bottom<=sourceActions.top || sourceTitle.top>=sourceActions.bottom;
                    const geometry={innerWidth,innerHeight,scrollY,canvas,details,
                        sourceTitle,sourceActions,titleClearOfActions,
                        header:rect('.taxonomy-app-chrome'),sourceToolbar:rect('.architecture-source-toolbar'),
                        sourceDescription:rect('#architectureSourceDescription'),actions:rect('#architectureSnapshotSource .workbench-toolbar'),
                        status:rect('#architectureStatus'),grid:rect('#architectureSnapshotSource .workbench-grid'),
                        controls:rect('#architectureWorkbench .architecture-controls'),
                        valid:titleClearOfActions && document.documentElement.scrollWidth<=innerWidth+1 && (innerWidth>900
                            ? canvas.height>=innerHeight*.5 && canvas.bottom<=innerHeight+1 && details.bottom<=innerHeight+1
                            : canvas.top<=innerHeight*.7 && [...document.querySelectorAll('#architectureSnapshotSource .architecture-control,#architectureSnapshotSource .architecture-toggle,#architectureSnapshotSource .architecture-search')]
                                .filter(e=>!e.hidden).every(e=>e.getBoundingClientRect().height>=44))};
                    window.__snapshotLayout=geometry;done(geometry);
                }));
                """);
        assertThat(geometry.get("valid")).as("%s: the embedded snapshot preserves the existing CI canvas, inspector and mobile viewport gates: %s", phase, geometry)
                .isEqualTo(true);
    }

    private static void assertSnapshotExports() {
        var disclosure = page.driver().findElement(By.id("architectureExportOptions"));
        var summary = disclosure.findElement(By.tagName("summary"));
        summary.sendKeys(Keys.ENTER);
        assertThat(disclosure.getDomProperty("open")).isEqualTo("true");
        assertThat(page.driver().executeScript("""
                const r=document.querySelector('#architectureExportOptions .workbench-export-content').getBoundingClientRect();
                return r.left>=0 && r.right<=innerWidth;
                """)).as("Embedded snapshot exports stay inside the viewport").isEqualTo(true);
        var controls = disclosure.findElements(By.cssSelector("select, button:not([disabled])"));
        assertThat(controls).extracting(control -> control.getDomAttribute("id")).containsExactly(
                "workbenchDocumentLanguage", "downloadArchitectureSvg", "downloadArchitecturePdf", "downloadArchitectureWord",
                "downloadDecisionWord", "downloadArchitectureArchiMate", "downloadArchitectureVisio");
        for (var control : controls) {
            new Actions(page.driver()).sendKeys(Keys.TAB).perform();
            assertThat(page.driver().switchTo().activeElement()).isEqualTo(control);
            assertThat(page.driver().executeScript("""
                    const r=arguments[0].getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);
                    return r.top>=0 && r.bottom<=innerHeight && (hit===arguments[0] || arguments[0].contains(hit));
                    """, control)).as("%s stays keyboard reachable in the embedded export popup", control.getDomAttribute("id")).isEqualTo(true);
        }
        new Actions(page.driver()).sendKeys(Keys.ESCAPE).perform();
        assertThat(disclosure.getDomProperty("open")).isEqualTo("false");
        assertThat(page.driver().switchTo().activeElement()).isEqualTo(summary);
    }

    @ParameterizedTest @CsvSource({"en,1920,1080", "de,1366,768", "en,1024,768", "de,390,844"})
    void embeddedSnapshotUsesRemainingViewportWithoutLosingSourceOrCamera(String locale, int width, int height) throws IOException {
        open(locale, width);
        resize(width, height);
        page.driver().executeScript("""
                window.__snapshotContextHomes=['architectureSourceDescription','architectureWorkflow','architectureStatus','architectureVisibleStatus']
                    .map(id=>{const node=document.getElementById(id);return {id,node,parent:node.parentNode,next:node.nextSibling};});
                """);
        var snapshot = snapshotSource("__shared__");
        source("snapshot", snapshot);
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#architectureCanvas [data-node-id='CP-1000']")));
        assertSnapshotViewport("initial " + width + "x" + height);
        assertThat(page.driver().executeScript("""
                const inspector=document.querySelector('#architectureSnapshotSource .workbench-detail-panel');
                return __snapshotContextHomes.every(home=>document.getElementById(home.id)===home.node && home.node.parentNode===inspector)
                    && inspector.firstElementChild.id==='detailTitle'
                    && document.getElementById('architectureSource').getAttribute('aria-describedby')==='architectureSourceDescription';
                """)).as("The existing source controls and explanation stay accessible after the current selection in the inspector").isEqualTo(true);
        var fit = page.driver().findElement(By.id("fitArchitecture"));
        assertThat(fit.isEnabled()).isTrue();
        page.driver().executeScript("arguments[0].scrollIntoView({behavior:'instant',block:'center',inline:'nearest'});", fit);
        assertThat(page.driver().executeScript("""
                const r=arguments[0].getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);
                return r.top>=0 && r.bottom<=innerHeight && (hit===arguments[0] || arguments[0].contains(hit));
                """, fit)).as("The real scenario Fit control passes the native hit test").isEqualTo(true);
        ScenarioBrowserWalkthrough.click(page.driver(), wait, By.id("fitArchitecture"));
        wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("""
                const canvas=document.getElementById('architectureCanvasShell').getBoundingClientRect();
                const nodes=[...document.querySelectorAll('#architectureCanvas .architecture-node')];
                return nodes.length===2 && nodes.every(node=>{const r=node.getBoundingClientRect();
                    return r.left>=canvas.left-1 && r.right<=canvas.right+1 && r.top>=canvas.top-1 && r.bottom<=canvas.bottom+1;});
                """)));
        assertSnapshotViewport("after native Fit");
        Path evidence = Path.of("target", "unified-architecture-height", locale + "-" + width);
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("geometry.json"), json(page.driver().executeScript("return __snapshotLayout;")));
        if (width == 1920 || width == 390) Files.write(evidence.resolve("viewport.png"), page.driver().getScreenshotAs(OutputType.BYTES));
        assertSnapshotExports();
        if (width == 1920) {
            page.driver().executeScript("""
                    TaxonomyArchitectureWorkspace.select('CP-1000');
                    TaxonomyArchitectureWorkspace.getDiagram().restoreViewport({x:23,y:41,k:.8});
                    """);
            Object camera = page.driver().executeScript("return TaxonomyArchitectureWorkspace.getDiagram().captureViewport();");
            Object data = page.driver().executeScript("return JSON.stringify(TaxonomyArchitectureWorkspace.getData());");
            for (int[] size : List.of(new int[]{390, 844}, new int[]{1366, 768}, new int[]{1920, 1080})) {
                resize(size[0], size[1]);
                assertSnapshotViewport("resized " + size[0] + "x" + size[1]);
                wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("""
                        const canvas=document.getElementById('architectureCanvasShell'),box=document.getElementById('architectureCanvas').viewBox.baseVal;
                        return box.width===canvas.clientWidth && box.height===canvas.clientHeight;
                        """)));
                assertThat(page.driver().executeScript("return TaxonomyArchitectureWorkspace.getDiagram().captureViewport();"))
                        .as("Resizing the workspace must preserve the manually retained camera").isEqualTo(camera);
            }
            source("analysis", scope("repository-A", "analysis-A", "draft/A"));
            assertThat(page.driver().executeScript("return __snapshotContextHomes.every(home=>home.node.parentNode===home.parent && home.node.nextSibling===home.next);")).isEqualTo(true);
            source("snapshot", snapshot);
            assertSnapshotViewport("returned source");
            assertThat(page.requests().stream().filter(request -> request.path().contains("/architecture-workbench/")).toList()).hasSize(1);
            assertThat(page.driver().executeScript("return TaxonomyArchitectureWorkspace.getDiagram().captureViewport();")).isEqualTo(camera);
            assertThat(page.driver().executeScript("return JSON.stringify(TaxonomyArchitectureWorkspace.getData());")).isEqualTo(data);
            assertThat(page.driver().findElement(By.cssSelector("#architectureCanvas [data-node-id='CP-1000']")).getDomAttribute("aria-pressed")).isEqualTo("true");

            var cdp = (HasCdp) new Augmenter().augment(page.driver());
            try {
                page.driver().executeScript("window.dispatchEvent(new Event('beforeprint'));");
                cdp.executeCdpCommand("Emulation.setEmulatedMedia", Map.of("media", "print"));
                assertThat(page.driver().executeScript("""
                        const grid=document.querySelector('#architectureSnapshotSource .workbench-grid').getBoundingClientRect();
                        const details=document.querySelector('#architectureSnapshotSource .workbench-detail-panel').getBoundingClientRect();
                        return grid.bottom+1>=details.bottom;
                        """)).as("Screen sizing must not constrain the full snapshot details in print media").isEqualTo(true);
            } finally {
                cdp.executeCdpCommand("Emulation.setEmulatedMedia", Map.of("media", "screen",
                        "features", List.of(Map.of("name", "prefers-reduced-motion", "value", "reduce"))));
                page.driver().executeScript("window.dispatchEvent(new Event('afterprint'));");
            }
            assertSnapshotViewport("returned from print");
            assertThat(page.driver().executeScript("return TaxonomyArchitectureWorkspace.getDiagram().captureViewport();")).isEqualTo(camera);
            var adopt = page.driver().findElement(By.id("workflow-adopt"));
            assertThat(adopt.isDisplayed()).as("The existing snapshot adoption entry remains visible in the inspector").isTrue();
            adopt.click();
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("snapshotAdoption")));
            assertThat(page.driver().getCurrentUrl()).contains("architectureSource=working", "sourceSnapshotId=snapshot-B");
            assertThat(page.driver().findElement(By.id("adoptionPreview")).isDisplayed()).isTrue();
            assertThat(page.driver().executeScript("return __snapshotContextHomes.every(home=>home.node.parentNode===home.parent && home.node.nextSibling===home.next);")).isEqualTo(true);
        }
        noScriptErrors();
    }

    @ParameterizedTest @ValueSource(ints = {1366, 1024})
    void embeddedSnapshotFitsShortContentViewportAndKeepsFitAndExports(int width) throws IOException {
        open("en", width);
        // Browser-window chrome leaves less content height than the requested outer window.
        resize(width, 625);
        source("snapshot", snapshotSource("__shared__"));
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#architectureCanvas [data-node-id='CP-1000']")));
        var geometry = (Map<?, ?>) page.driver().executeAsyncScript("""
                const done=arguments[arguments.length-1];scrollTo(0,0);
                requestAnimationFrame(()=>requestAnimationFrame(()=>{
                    const rect=selector=>{const r=document.querySelector(selector).getBoundingClientRect();
                        return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height};};
                    const canvas=rect('#architectureCanvasShell'),details=rect('.workbench-detail-panel');
                    const grid=document.querySelector('#architectureSnapshotSource .workbench-grid');
                    const geometry={innerWidth,innerHeight,outerWidth,outerHeight,scrollY,canvas,details,
                        grid:rect('#architectureSnapshotSource .workbench-grid'),workbench:rect('#architectureWorkbench'),
                        controls:rect('#architectureWorkbench .architecture-controls'),
                        assignedHeight:grid.style.getPropertyValue('--architecture-snapshot-height'),
                        rows:getComputedStyle(document.getElementById('architectureWorkbench')).gridTemplateRows,
                        // The original ArchitectureWorkbenchUiIT viewport oracle is unchanged.
                        valid:canvas.height>0 && canvas.bottom<=innerHeight+1
                            && details.bottom<=innerHeight+1
                            && document.documentElement.scrollWidth<=innerWidth+1};
                    window.__snapshotLayout=geometry;done(geometry);
                }));
                """);
        Path evidence = Path.of("target", "unified-architecture-height", "en-" + width + "-short");
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("geometry.json"), json(geometry));
        Files.write(evidence.resolve("viewport.png"), page.driver().getScreenshotAs(OutputType.BYTES));
        assertThat(geometry.get("valid")).as("The original viewport gate must also hold in a short content area: %s", geometry)
                .isEqualTo(true);
        ScenarioBrowserWalkthrough.click(page.driver(), wait, By.id("fitArchitecture"));
        wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("""
                const canvas=document.getElementById('architectureCanvasShell').getBoundingClientRect();
                const nodes=[...document.querySelectorAll('#architectureCanvas .architecture-node')];
                return nodes.length===2 && nodes.every(node=>{const r=node.getBoundingClientRect();
                    return r.left>=canvas.left-1 && r.right<=canvas.right+1 && r.top>=canvas.top-1 && r.bottom<=canvas.bottom+1;});
                """)));
        assertSnapshotExports();
        noScriptErrors();
    }

    @Test void embeddedSnapshotEntersAndExitsNativeFullscreenWithoutChangingItsSourceOrSelection() throws IOException {
        open("en", 1600);
        // Use the real Scenario browser-window path, rather than an emulated CSS viewport.
        ((HasCdp) new Augmenter().augment(page.driver())).executeCdpCommand("Emulation.clearDeviceMetricsOverride", Map.of());
        page.driver().manage().window().setSize(new Dimension(1600, 1100));
        source("snapshot", snapshotSource("__shared__"));
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#architectureCanvas [data-node-id='CP-1000']")));
        ScenarioBrowserWalkthrough.click(page.driver(), wait, By.id("fitArchitecture"));
        ScenarioBrowserWalkthrough.click(page.driver(), wait, By.cssSelector("#architectureCanvas [data-node-id='CP-1000']"));
        ScenarioBrowserWalkthrough.click(page.driver(), wait, By.id("zoomArchitectureIn"));
        ScenarioBrowserWalkthrough.click(page.driver(), wait, By.id("zoomArchitectureOut"));
        ScenarioBrowserWalkthrough.click(page.driver(), wait, By.id("fitArchitecture"));
        page.driver().executeScript("""
                window.__fullscreenSource=JSON.stringify(TaxonomyArchitectureWorkspace.getSource());
                window.__fullscreenData=TaxonomyArchitectureWorkspace.getData();
                window.__fullscreenDocument=__sameDocument;
                """);
        ScenarioBrowserWalkthrough.observeFullscreen(page.driver());
        Path evidence = Path.of("target", "unified-architecture-fullscreen");
        Files.createDirectories(evidence);
        try {
            assertThat(page.driver().executeScript("return document.fullscreenElement;")).isNull();
            assertThat(page.driver().executeScript("return document.fullscreenEnabled;")).isEqualTo(true);
            assertThat(page.driver().findElement(By.id("fullscreenArchitecture")).isEnabled()).isTrue();
            ScenarioBrowserWalkthrough.click(page.driver(), wait, By.id("fullscreenArchitecture"));
            wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("""
                    return document.fullscreenElement===document.getElementById('architectureWorkbench');
                    """)));
            page.driver().executeScript("__recordFullscreen('entered');");
            assertThat(page.driver().executeScript("""
                    return JSON.stringify(TaxonomyArchitectureWorkspace.getSource())===__fullscreenSource
                        && TaxonomyArchitectureWorkspace.getData()===__fullscreenData && __sameDocument===__fullscreenDocument;
                    """)).as("Fullscreen keeps the exact source, loaded data and document").isEqualTo(true);
            assertThat(page.driver().findElement(By.cssSelector("#architectureCanvas [data-node-id='CP-1000']"))
                    .getDomAttribute("aria-pressed")).isEqualTo("true");
            Files.write(evidence.resolve("entered.png"), page.driver().getScreenshotAs(OutputType.BYTES));
            ScenarioBrowserWalkthrough.click(page.driver(), wait, By.id("fullscreenArchitecture"));
            wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("return document.fullscreenElement===null;")));
            page.driver().executeScript("__recordFullscreen('exited');");
            assertThat(page.driver().executeScript("""
                    return JSON.stringify(TaxonomyArchitectureWorkspace.getSource())===__fullscreenSource
                        && TaxonomyArchitectureWorkspace.getData()===__fullscreenData && __sameDocument===__fullscreenDocument;
                    """)).as("Exiting fullscreen keeps the exact source, loaded data and document").isEqualTo(true);
            assertThat(page.driver().findElement(By.cssSelector("#architectureCanvas [data-node-id='CP-1000']"))
                    .getDomAttribute("aria-pressed")).isEqualTo("true");
            assertThat(page.driver().executeScript("""
                    return __fullscreenEvidence.events.filter(event=>event.type==='fullscreenchange').map(event=>event.fullscreen);
                    """)).as("Native fullscreen changes enter once and leave once").isEqualTo(java.util.Arrays.asList("architectureWorkbench", null));
            assertThat(page.driver().executeScript("""
                    return __fullscreenEvidence.events.filter(event=>event.type==='click:capture').map(event=>event.isTrusted);
                    """)).as("Both fullscreen toggles are trusted browser clicks").isEqualTo(List.of(true, true));
            assertThat(page.driver().executeScript("""
                    return __fullscreenEvidence.events.filter(event=>event.type==='fullscreenerror' || event.type==='unhandledrejection');
                    """)).isEqualTo(List.of());
            assertThat(page.requests().stream().filter(request -> request.path().contains("/architecture-workbench/")).toList()).hasSize(1);
            noScriptErrors();
        } finally {
            page.driver().executeScript("__recordFullscreen('final');");
            Files.writeString(evidence.resolve("events.json"), json(page.driver().executeScript("return __fullscreenEvidence;")));
            Files.writeString(evidence.resolve("browser.json"), json(page.driver().getCapabilities().asMap()));
            Files.write(evidence.resolve("final.png"), page.driver().getScreenshotAs(OutputType.BYTES));
        }
    }

    @ParameterizedTest @CsvSource({"en,1440", "de,390"})
    void renderedApplicationHasOneNavigationAndLensOpensAnalysisInPlace(String locale, int width) throws IOException {
        open(locale, width);
        assertThat(page.driver().findElements(By.cssSelector("#applicationWorkspaces [data-app-route]")))
                .extracting(element -> element.getDomAttribute("data-app-route"))
                .containsExactly("projects", "analysis", "architecture");
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
        // The source picker resumes the retained revision; an explicit unpinned source reads the latest head.
        new Select(page.driver().findElement(By.id("architectureSource"))).selectByValue("working");
        wait.until(driver -> "working".equals(page.driver().executeScript("return TaxonomyArchitectureWorkspace.getSource().kind;")));
        assertAgreement("working", first);
        assertThat(page.driver().findElement(By.id("editorTitle")).getDomProperty("value")).isEqualTo("Retain my unaccepted draft");
        assertThat(page.driver().executeScript("return __guardCalls;"))
                .as("Resuming the retained working source does not ask to discard its draft").isEqualTo(1L);
        assertThat(page.requests().stream().filter(request -> request.method().equals("GET")
                && request.path().equals("/taxonomy/api/architecture/editor")).toList())
                .as("Refusing another source and resuming the retained one must not reload the editor").hasSize(1);
        assertThat(page.driver().executeScript("""
                return [TaxonomyArchitectureWorkspace.getSource().revision,new URL(location.href).searchParams.get('revision')];
                """))
                .as("The retained revision remains pinned in both the workspace and URL").isEqualTo(List.of(7L, "7"));
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

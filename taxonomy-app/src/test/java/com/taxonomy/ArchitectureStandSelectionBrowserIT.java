package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.taxonomy.PortfolioClientTestPage.json;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual templates, JavaScript, native navigation and dialogs; only HTTP data is authored. */
@Tag("browser")
@Tag("ui-acceptance")
@Tag("ci-app-shard-browser")
class ArchitectureStandSelectionBrowserIT {
    private static final String OLD_COMMIT = "a".repeat(40);
    private static final String HEAD = "b".repeat(40);
    private static final String EDITOR = "/api/architecture/editor";
    private static final String LIFECYCLE = "/api/architecture/lifecycle";
    private static final String RECONCILIATION = EDITOR + "/versions/reconciliation";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger REVISION = new AtomicInteger(2);
    private static final AtomicBoolean NEEDS_RECONCILIATION = new AtomicBoolean();
    private static final AtomicBoolean PENDING = new AtomicBoolean();
    private static PortfolioClientTestPage page;
    private static WebDriverWait wait;

    @BeforeAll static void start(@TempDir Path downloads) throws Exception {
        page = new PortfolioClientTestPage(downloads);
        wait = new WebDriverWait(page.driver(), Duration.ofSeconds(20));
    }
    @AfterAll static void stop() { if (page != null) page.close(); }
    @BeforeEach void fixtures() {
        page.clearRoutes(); page.clearRequests(); REVISION.set(2); NEEDS_RECONCILIATION.set(false); PENDING.set(false);
        page.driver().manage().window().setSize(new Dimension(1366, 900));
        page.route("GET", EDITOR, request -> json(200, editor(request.query())));
        page.route("GET", LIFECYCLE, request -> json(200, overview(request.query())));
        page.route("GET", "/api/projects", request -> json(200, List.of(project())));
        page.route("GET", "/api/projects/42/requirements/7", request -> json(200, requirement()));
        page.route("POST", LIFECYCLE + "/realizations/preview", request -> json(200,
                Map.of("context", context(REVISION.get()), "requirementId", "fixture-requirement", "fingerprint", "review-fingerprint",
                        "realization", Map.of("state", "PARTIAL"), "changes", List.of())));
        page.route("POST", LIFECYCLE + "/realizations", request -> { REVISION.set(3); return json(200, publication(false)); });
        page.route("POST", RECONCILIATION + "/preview", request -> json(200, reconciliationPreview(request)));
        page.route("POST", RECONCILIATION + "/accept", request -> { REVISION.set(3); NEEDS_RECONCILIATION.set(false); return json(200, publication(false)); });
        page.route("GET", LIFECYCLE + "/history", request -> json(200,
                Map.of("head", HEAD, "entries", List.of(Map.of("commitId", OLD_COMMIT,"message","Reviewed source\n\nFull rationale",
                        "author","fixture-user","timestamp","2026-10-07T09:00:00Z")),"offset",0,"scanned",1,"coverage","COMPLETE","source","LIVE_GIT")));
    }

    @Test void historicalSemanticRevisionUsesTheSameReadOnlyStandEverywhere() {
        open("revision=1"); awaitRevision(1);
        assertThat(page.driver().findElement(By.id("editorDsl")).getDomProperty("value")).contains("historical revision 1");
        assertThat(page.driver().findElement(By.id("editorTitle")).isEnabled()).isFalse();
        assertThat(page.driver().executeScript("return TaxonomyArchitectureLifecycle.state().projectionState;")).isEqualTo("HISTORICAL");
        assertThat(page.driver().executeScript("return TaxonomyAppChrome.getContext().editable;")).isEqualTo(false);
        assertThat(page.requests().stream().filter(r -> r.path().equals(LIFECYCLE)).toList())
                .allMatch(r -> "1".equals(query(r.query()).get("revision")));
    }

    @ParameterizedTest @ValueSource(strings = {"commit", "revision"})
    void currentStandRefreshesEditorAndLifecycleTogether(String kind) {
        open(kind + "=" + (kind.equals("commit") ? OLD_COMMIT : "1"));
        wait.until(driver -> Boolean.FALSE.equals(page.driver().executeScript("return window.TaxonomyAppChrome?.getContext().editable;"))
                && Boolean.TRUE.equals(page.driver().executeScript("return !!window.TaxonomyArchitectureLifecycle?.state();")));
        click(By.cssSelector("[data-lifecycle-action='current']")); awaitRevision(2);
        assertThat(page.driver().findElement(By.id("editorDsl")).getDomProperty("value")).contains("current revision 2");
        assertThat(page.driver().findElement(By.id("editorTitle")).isEnabled()).isTrue();
        assertThat(page.driver().getCurrentUrl()).contains("revision=2").doesNotContain("commit=");
    }

    @Test void backAndForwardKeepTheEditorLifecycleAndChromeOnOneStand() {
        currentWithHistoricalBackEntry();
        page.driver().navigate().back(); awaitRevision(1);
        assertThat(page.driver().executeScript("return TaxonomyAppChrome.getContext().editable;")).isEqualTo(false);
        page.driver().navigate().forward(); awaitRevision(2);
        assertThat(page.driver().executeScript("return TaxonomyAppChrome.getContext().editable;")).isEqualTo(true);
    }

    @Test void failedEditorLoadDiscardsTheOldEditableOverview() {
        open("revision=2"); awaitRevision(2);
        page.route("GET", EDITOR, request -> json(404, Map.of("detail","Unknown selected revision")));
        navigateSelection("revision=99");
        wait.until(driver -> driver.findElement(By.id("editorError")).isDisplayed());
        assertThat(page.driver().executeScript("return TaxonomyArchitectureLifecycle.state();")).isNull();
        assertThat(page.driver().executeScript("return TaxonomyAppChrome.getContext().editable;")).isEqualTo(false);
        assertThat(page.driver().findElements(By.cssSelector("#architectureLifecycle tbody tr"))).isEmpty();
    }

    @Test void changingArchitectureDiscardsThePreviousHistoryBeforeTheNextReadCompletes() throws Exception {
        open("revision=2"); awaitRevision(2); expand();
        click(By.cssSelector("[data-lifecycle-action='history']"));
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector("[data-lifecycle-history-list]"), "Reviewed source"));
        CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
        page.route("GET", EDITOR, request -> { requested.countDown(); await(release); return json(200, editor(request.query())); });
        try {
            navigateSelection("revision=2&repositoryId=other-architecture");
            assertThat(requested.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(page.driver().findElement(By.cssSelector("[data-lifecycle-history-list]")).getDomProperty("textContent")).isEmpty();
            assertThat(page.driver().executeScript("return TaxonomyArchitectureLifecycle.state();")).isNull();
        } finally { release.countDown(); }
        awaitRevision(2);
    }

    @Test void logSearchKeepsTheExactRequirementPairAndGitHeadAcrossPages() {
        page.route("GET", LIFECYCLE, request -> {
            var value = new LinkedHashMap<>(overview(request.query()));
            @SuppressWarnings("unchecked") var requirements = (List<Map<String,Object>>) value.get("requirements");
            var contributed = new LinkedHashMap<>(requirements.getFirst());
            contributed.put("contributors",List.of(Map.of("projectKey","CONTRIBUTOR","requirementVersion",2,"contentHash","fixture-hash")));
            value.put("requirements",List.of(contributed)); return json(200,value);
        });
        page.route("GET", LIFECYCLE + "/history", request -> {
            var query = query(request.query());
            var result = new LinkedHashMap<String,Object>(); result.put("head",HEAD);
            result.put("entries",List.of(Map.of("commitId",OLD_COMMIT,"message",query.getOrDefault("text", "All") + " matching subject\n\nComplete reviewed rationale")));
            result.put("coverage","COMPLETE"); result.put("source","LIVE_GIT"); result.put("scanned",1);
            if ("needle".equals(query.get("text")) && "0".equals(query.get("offset"))) { result.put("nextOffset",25); result.put("coverage","PARTIAL"); }
            return json(200,result);
        });
        open("revision=2"); awaitRevision(2);
        page.driver().executeScript("TaxonomyAppChrome.updateContext({project:{id:99,key:'CONTRIBUTOR',title:'Contributing project'}});");
        expand(); click(By.cssSelector("[data-requirement-key='REQ-7'] [data-lifecycle-action='history']"));
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector("[data-lifecycle-history-list]"),"All matching subject"));
        page.driver().findElement(By.id("lifecycleHistorySearch")).sendKeys("needle");
        click(By.cssSelector("#lifecycleHistoryForm button[type='submit']"));
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector("[data-lifecycle-history-list]"),"needle matching subject"));
        assertThat(page.driver().findElement(By.cssSelector("[data-lifecycle-history-list] li > a")).getText()).isEqualTo("needle matching subject");
        click(By.cssSelector("[data-lifecycle-history-list] > button"));
        wait.until(driver -> page.requests().stream().anyMatch(r -> r.path().equals(LIFECYCLE + "/history") && "25".equals(query(r.query()).get("offset"))));
        var requests = page.requests().stream().filter(r -> r.path().equals(LIFECYCLE + "/history")).map(r -> query(r.query())).toList();
        assertThat(requests).allSatisfy(query -> { assertThat(query).containsEntry("projectKey","PROJECT-42").containsEntry("requirementKey","REQ-7"); });
        assertThat(requests.getLast()).containsEntry("text","needle").containsEntry("expectedHead",HEAD).containsEntry("offset","25");
    }

    @Test void delayedReviewLookupCannotOpenADialogUnderAnotherStand() throws Exception {
        currentWithHistoricalBackEntry(); expand();
        CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
        page.route("GET", "/api/projects", request -> { requested.countDown(); await(release); return json(200, List.of(project())); });
        try {
            click(By.cssSelector("[data-lifecycle-action='review']"));
            assertThat(requested.await(10, TimeUnit.SECONDS)).isTrue();
            page.driver().navigate().back(); awaitRevision(1); release.countDown();
            wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector("[data-lifecycle-status]"), "selected architecture state has changed"));
            assertThat(page.driver().findElements(By.id("lifecycleReviewDialog"))).isEmpty();
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void preparedOrDelayedReviewCannotBeAcceptedAfterTheVisibleStandChanges(boolean delayedPreview) throws Exception {
        currentWithHistoricalBackEntry(); openReview();
        CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
        if (delayedPreview) page.route("POST", LIFECYCLE + "/realizations/preview", request -> {
            requested.countDown(); await(release); return json(200, Map.of("fingerprint","late-preview", "changes",List.of()));
        });
        try {
            click(By.id("lifecycleReviewPreview"));
            if (delayedPreview) assertThat(requested.await(10, TimeUnit.SECONDS)).isTrue();
            else wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("lifecycleReviewAccept")));
            page.driver().navigate().back(); awaitRevision(1); release.countDown();
            assertStaleDialog("lifecycleReview");
            assertThat(page.driver().findElement(By.id("lifecycleReviewReason")).getDomProperty("value")).isEqualTo("Keep this reviewed rationale");
            assertThat(countPosts(LIFECYCLE + "/realizations")).isZero();
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ownPublicationAdvancesTheEditorAndPreservesItsUnsubmittedDraft(boolean pending) {
        currentWithHistoricalBackEntry();
        var draft = page.driver().findElement(By.id("editorTitle")); draft.sendKeys("Unsubmitted element title");
        AtomicInteger attempts = new AtomicInteger();
        page.route("POST", LIFECYCLE + "/realizations", request -> {
            REVISION.set(3); PENDING.set(pending && attempts.getAndIncrement() == 0); return json(200, publication(PENDING.get()));
        });
        openReview(); prepareReview(); click(By.id("lifecycleReviewAccept")); awaitRevision(3);
        if (pending) {
            wait.until(ExpectedConditions.elementToBeClickable(By.id("lifecycleReviewAccept")));
            assertThat(page.driver().findElement(By.id("lifecycleReviewReason")).isEnabled()).isFalse();
            click(By.id("lifecycleReviewAccept"));
        }
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("lifecycleReviewDialog")));
        assertThat(draft.getDomProperty("value")).isEqualTo("Unsubmitted element title");
        assertThat(page.driver().getCurrentUrl()).contains("revision=3");
        assertThat(page.driver().findElement(By.id("editorDsl")).getDomProperty("value")).contains("current revision 3");
        if (pending) {
            var bodies = page.requests().stream().filter(r -> r.method().equals("POST") && r.path().equals(LIFECYCLE + "/realizations")).map(PortfolioClientTestPage.Request::body).toList();
            assertThat(bodies).hasSize(2); assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void standalonePublicationUpdatesOnlyTheStillCurrentUrlAndRetainsItsContext(boolean chooseHistorical) throws Exception {
        page.route("GET","/api/projects/42",request -> json(200,project()));
        page.route("GET","/api/git/state",request -> json(200,Map.of("currentBranch","draft","branches",List.of("draft"),"headCommit",HEAD)));
        page.route("GET","/api/projects/git/export",request -> json(200,Map.of("repositoryId","fixture-architecture","workspaceId","fixture-space","activeBranch","draft","headCommit",HEAD,"dsl","")));
        page.route("GET","/api/account/me",request -> json(200,Map.of("architectureMutationAllowed",true)));
        page.openAt("portfolio-versioning","","/projects/42/versioning","en","""
                localStorage.clear(); sessionStorage.clear();
                history.replaceState(null,'','/projects/42/versioning?lang=en&projectId=42&repositoryId=fixture-architecture&workspaceId=fixture-space&workspaceScopeKey=fixture-space&branch=draft&revision=1&sourceSnapshotId=frozen-source#retained');
                """);
        wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("return window.TaxonomyArchitectureLifecycle?.state()?.context.revision===1;")));
        navigateSelection("revision=2");
        wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("return TaxonomyArchitectureLifecycle.state()?.context.revision===2;")));
        openReview(); prepareReview();
        CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
        page.route("POST",LIFECYCLE + "/realizations",request -> {
            requested.countDown(); if(chooseHistorical) await(release); REVISION.set(3); return json(200,publication(false));
        });
        try {
            click(By.id("lifecycleReviewAccept")); assertThat(requested.await(10,TimeUnit.SECONDS)).isTrue();
            if(chooseHistorical) {
                page.driver().navigate().back();
                wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("return TaxonomyArchitectureLifecycle.state()?.context.revision===1;")));
                release.countDown(); assertLateSuccess("lifecycleReview");
            } else {
                wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("lifecycleReviewDialog")));
                wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("return TaxonomyArchitectureLifecycle.state()?.context.revision===3;")));
                assertThat(page.driver().getCurrentUrl()).doesNotContain("revision=", "commit=")
                        .contains("repositoryId=fixture-architecture","workspaceId=fixture-space","branch=draft","sourceSnapshotId=frozen-source").endsWith("#retained");
                assertThat(page.driver().findElement(By.cssSelector("[data-app-route='architecture']")).getDomAttribute("href"))
                        .doesNotContain("revision=", "commit=").contains("sourceSnapshotId=frozen-source","workspaceScopeKey=fixture-space");
            }
        } finally { release.countDown(); }
    }

    @Test void completedReviewDoesNotNavigateBackFromASelectedHistoricalRevision() throws Exception {
        currentWithHistoricalBackEntry(); openReview(); prepareReview();
        CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
        page.route("POST", LIFECYCLE + "/realizations", request -> {
            requested.countDown(); await(release); REVISION.set(3); return json(200, publication(false));
        });
        try {
            click(By.id("lifecycleReviewAccept")); assertThat(requested.await(10, TimeUnit.SECONDS)).isTrue();
            page.driver().navigate().back(); awaitRevision(1); release.countDown();
            assertLateSuccess("lifecycleReview");
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void preparedOrDelayedReconciliationCannotBeAcceptedAfterTheVisibleStandChanges(boolean delayedPreview) throws Exception {
        NEEDS_RECONCILIATION.set(true); currentWithHistoricalBackEntry(); beginReconciliation();
        CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
        if (delayedPreview) page.route("POST", RECONCILIATION + "/preview", request -> {
            requested.countDown(); await(release); return json(200, reconciliationPreview(request));
        });
        try {
            click(By.id("lifecycleReconciliationPreview"));
            if (delayedPreview) assertThat(requested.await(10, TimeUnit.SECONDS)).isTrue();
            else wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("lifecycleReconciliationAccept")));
            page.driver().navigate().back(); awaitRevision(1); release.countDown();
            assertStaleDialog("lifecycleReconciliation");
            assertThat(page.driver().findElement(By.id("lifecycleReconciliationReason")).getDomProperty("value")).isEqualTo("Keep my reconciliation reason");
            assertThat(new Select(page.driver().findElement(By.cssSelector("[data-conflict-id]"))).getFirstSelectedOption().getDomProperty("value")).isEqualTo("LOCAL");
            assertThat(countPosts(RECONCILIATION + "/accept")).isZero();
        } finally { release.countDown(); }
    }

    @Test void uncertainReconciliationRetryKeepsItsExactIntentInTheOriginalStand() {
        NEEDS_RECONCILIATION.set(true); currentWithHistoricalBackEntry(); beginReconciliation(); prepareReconciliation();
        AtomicInteger calls = new AtomicInteger();
        page.route("POST", RECONCILIATION + "/accept", request -> {
            if (calls.getAndIncrement() == 0) return json(503, Map.of("detail","Uncertain reconciliation response"));
            REVISION.set(3); NEEDS_RECONCILIATION.set(false); return json(200, publication(false));
        });
        click(By.id("lifecycleReconciliationAccept"));
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("lifecycleReconciliationDialog"), "Uncertain reconciliation response"));
        assertThat(page.driver().findElement(By.id("lifecycleReconciliationReason")).getDomProperty("value")).isEqualTo("Keep my reconciliation reason");
        click(By.id("lifecycleReconciliationAccept")); awaitRevision(3);
        var bodies = page.requests().stream().filter(r -> r.method().equals("POST") && r.path().equals(RECONCILIATION + "/accept")).map(PortfolioClientTestPage.Request::body).toList();
        assertThat(bodies).hasSize(2); assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
        assertThat(JSON.readTree(bodies.getFirst()).path("resolutions").path("element:fixture").asText()).isEqualTo("LOCAL");
        assertThat(JSON.readTree(bodies.getFirst()).path("metadata").path("commandId").asText()).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {"current", "historical", "other"})
    void lostRequirementResponseCanRetryOnlyInTheCurrentOriginalScope(String selection) {
        var fixture = new PortfolioRequirementFixture(page);
        page.route("GET",LIFECYCLE,request -> {
            var q=query(request.query());
            var c=new LinkedHashMap<>(PortfolioRequirementFixture.lifecycleContext());
            c.put("revision",Integer.parseInt(q.getOrDefault("revision",String.valueOf(REVISION.get()))));
            c.put("repositoryId",q.getOrDefault("repositoryId","control-architecture"));
            boolean historical=((Number)c.get("revision")).intValue()<REVISION.get();
            return json(200,Map.of("context",c,"architectureTitle","Control architecture","source","WORKSPACE_REVISION",
                    "projectionState",historical?"HISTORICAL":"READY","publicationState",historical?"HISTORICAL":"CHECKPOINTED",
                    "mayEdit",!historical,"projects",List.of(PortfolioRequirementFixture.project()),"requirements",List.of(),"dependencies",List.of()));
        });
        AtomicInteger attempts=new AtomicInteger();
        page.route("POST",PortfolioRequirementFixture.SAVE_REQUIREMENT,request -> {
            if(attempts.getAndIncrement()==0) {
                REVISION.set(3); fixture.updateBaseline(PortfolioRequirementFixture.DRAFT.getFirst(),"Pump control");
                return json(503,Map.of("detail","The saved response was lost"));
            }
            var c=new LinkedHashMap<>(PortfolioRequirementFixture.lifecycleContext()); c.put("revision",3);
            return json(200,Map.of("projectId",1,"requirementId",2,"requirement",fixture.requirement.get(),"publication",Map.of("context",c,"state","CHECKPOINTED")));
        });
        fixture.open("en"); fixture.openVersionModal(); fixture.editVersion(); fixture.submitVersion();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("newVersionError"),"The saved response was lost"));
        fixture.assertDraft(); fixture.closeVersionModal();
        if(selection.equals("current")) {
            expand(); click(By.cssSelector("[data-lifecycle-action='refresh']"));
        } else {
            if(selection.equals("other")) page.driver().executeScript("TaxonomyArchitectureWorkflow.setScope({repositoryId:'other-architecture',workspaceScopeKey:'qa-copy',branch:'draft'});");
            navigateSelection(selection.equals("historical")?"revision=1":"repositoryId=other-architecture");
        }
        int visible=selection.equals("historical")?1:3;
        wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("return TaxonomyArchitectureLifecycle.state()?.context.revision===arguments[0];",visible)));
        fixture.openVersionModal(); fixture.assertDraft();
        fixture.click(By.cssSelector("#newVersionForm button[type='submit']"));
        if(selection.equals("current")) {
            wait.until(driver -> countPosts(PortfolioRequirementFixture.SAVE_REQUIREMENT)==2
                    || driver.findElement(By.id("newVersionError")).getText().contains("selected architecture state has changed"));
            assertThat(countPosts(PortfolioRequirementFixture.SAVE_REQUIREMENT))
                    .as("Refreshing the current original scope must allow the exact uncertain command to reach the backend").isEqualTo(2);
            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("newVersionModal")));
            assertExactRetry(PortfolioRequirementFixture.SAVE_REQUIREMENT);
        } else {
            wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("newVersionError"),"selected architecture state has changed"));
            assertThat(countPosts(PortfolioRequirementFixture.SAVE_REQUIREMENT)).isEqualTo(1);
            fixture.assertDraft();
        }
    }

    @ParameterizedTest
    @CsvSource({"review,current,unknown","review,historical,unknown","review,other,unknown","reconciliation,current,unknown","reconciliation,historical,unknown","reconciliation,other,unknown","review,current,pending"})
    void lostDialogResponseKeepsOnlyItsExactRetryAfterAStandRead(String kind,String selection,String outcome) {
        boolean reconciliation=kind.equals("reconciliation"); NEEDS_RECONCILIATION.set(reconciliation);
        currentWithHistoricalBackEntry();
        String prefix=reconciliation?"lifecycleReconciliation":"lifecycleReview";
        String endpoint=reconciliation?RECONCILIATION+"/accept":LIFECYCLE+"/realizations";
        if(reconciliation) { beginReconciliation(); prepareReconciliation(); } else { openReview(); prepareReview(); }
        AtomicInteger attempts=new AtomicInteger();
        page.route("POST",endpoint,request -> {
            NEEDS_RECONCILIATION.set(false);
            if(outcome.equals("pending")) {
                PENDING.set(attempts.getAndIncrement()==0);
                if(PENDING.get()) REVISION.set(3);
                return json(200,Map.of("context",context(REVISION.get()),"state",PENDING.get()?"CHECKPOINT_PENDING":"CHECKPOINTED"));
            }
            REVISION.set(3);
            return attempts.getAndIncrement()==0?json(503,Map.of("detail","The accepted response was lost")):json(200,publication(false));
        });
        click(By.id(prefix+"Accept"));
        if(outcome.equals("pending")) { awaitRevision(3); REVISION.set(4); PENDING.set(false); }
        else wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id(prefix+"Dialog"),"The accepted response was lost"));
        navigateSelection(selection.equals("historical")?"revision=1":selection.equals("other")?"repositoryId=other-architecture":"");
        awaitRevision(selection.equals("historical")?1:outcome.equals("pending")?4:3);
        if(selection.equals("current")) {
            var retry=page.driver().findElement(By.id(prefix+"Accept"));
            assertThat(retry.isDisplayed() && retry.isEnabled()).as("A current read must retain the original uncertain operation retry").isTrue();
            assertThat(page.driver().findElement(By.id(prefix+"Preview")).isEnabled()).as("An unknown accepted intent cannot become a fresh preview").isFalse();
            assertThat(page.driver().findElement(By.id(prefix+"Reason")).isEnabled()).isFalse();
            click(By.id(prefix+"Accept"));
            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id(prefix+"Dialog")));
            assertExactRetry(endpoint);
            if(outcome.equals("pending")) awaitRevision(4);
        } else {
            assertStaleDialog(prefix);
            assertThat(countPosts(endpoint)).isEqualTo(1);
        }
    }

    @Test void newArchitectureDescriptionKeepsThePersistedThousandCharacterBoundary() {
        page.route("GET",LIFECYCLE + "/architectures",request -> json(200,List.of()));
        open("revision=2"); awaitRevision(2); expand();
        click(By.cssSelector("[data-lifecycle-action='new-architecture']"));
        var description=wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("lifecycleArchitectureDescription")));
        assertThat(description.getDomProperty("maxLength")).as("Architecture descriptions must fit the persisted field").isEqualTo("1000");
        description.sendKeys("x".repeat(1001));
        assertThat(description.getDomProperty("value")).hasSize(1000);
        assertThat(countPosts(LIFECYCLE + "/architectures")).isZero();
    }

    @Test void failedArchitectureProvisioningCanResumeAfterReloadWithItsPersistedIdentity() throws Exception {
        var failed=creationArchitecture("FAILED");
        CountDownLatch discovered=new CountDownLatch(1);
        page.route("GET",LIFECYCLE + "/architectures",request -> { discovered.countDown(); return json(200,List.of(failed)); });
        page.route("POST",LIFECYCLE + "/architectures",request -> json(200,creationArchitecture("ACTIVE")));
        page.route("POST",EDITOR + "/workspaces",request -> json(201,Map.of("workspaceId","recovered-space",
                "sourceRepositoryId","recovered-architecture","sourceBranch","main","currentBranch","main","displayName","Recovered architecture","provisioningStatus","READY")));
        openForCreationRecovery(); awaitRevision(2); page.driver().navigate().refresh(); awaitRevision(2); expand();
        click(By.cssSelector("[data-lifecycle-action='new-architecture']"));
        assertThat(discovered.await(10,TimeUnit.SECONDS)).as("Opening creation must discover owned incomplete provisioning after reload").isTrue();
        click(By.cssSelector("[data-lifecycle-recover-architecture='recovered-architecture']"));
        wait.until(driver -> driver.getCurrentUrl().contains("repositoryId=recovered-architecture"));
        var creations=page.requests().stream().filter(request -> request.method().equals("POST") && request.path().equals(LIFECYCLE + "/architectures")).toList();
        assertThat(creations).hasSize(1);
        assertThat(JSON.readTree(creations.getFirst().body())).isEqualTo(JSON.valueToTree(Map.of(
                "displayName","Recovered architecture","slug","architecture-stable-retry","description","The original description, retained after failure.")));
        assertThat(page.driver().getCurrentUrl()).contains("workspaceId=recovered-space","workspaceScopeKey=recovered-space","branch=main");
        assertThat(countPosts(EDITOR + "/workspaces")).isEqualTo(1);
    }

    @Test void runningArchitectureProvisioningRefreshesItsStateWithoutDuplicatingTheRequest() throws Exception {
        AtomicBoolean completed=new AtomicBoolean();
        CountDownLatch discovered=new CountDownLatch(1);
        page.route("GET",LIFECYCLE + "/architectures",request -> {
            discovered.countDown(); return json(200,List.of(creationArchitecture(completed.get()?"ACTIVE":"PROVISIONING")));
        });
        open("revision=2"); awaitRevision(2); expand(); click(By.cssSelector("[data-lifecycle-action='new-architecture']"));
        assertThat(discovered.await(10,TimeUnit.SECONDS)).isTrue();
        var item=wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[data-lifecycle-provisioning-architecture='recovered-architecture']")));
        assertThat(item.getText()).contains("Recovered architecture");
        assertThat(item.findElements(By.cssSelector("[data-lifecycle-recover-architecture]"))).isEmpty();
        completed.set(true); click(By.cssSelector("[data-lifecycle-refresh-architectures]"));
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("[data-lifecycle-provisioning-architecture='recovered-architecture']")));
        assertThat(countPosts(LIFECYCLE + "/architectures")).isZero();
        assertThat(countPosts(EDITOR + "/workspaces")).isZero();
        assertThat(page.driver().getCurrentUrl()).contains("repositoryId=fixture-architecture");
    }

    private static void assertExactRetry(String endpoint) {
        var sent=page.requests().stream().filter(request -> request.method().equals("POST") && request.path().equals(endpoint)).toList();
        assertThat(sent).hasSize(2); assertThat(sent.get(1).body()).isEqualTo(sent.getFirst().body());
        assertThat(JSON.readTree(sent.getFirst().body()).path("context").path("revision").asInt()).isEqualTo(2);
        assertThat(JSON.readTree(sent.getFirst().body()).path("metadata").path("commandId").asText()).isNotBlank();
        assertThat(sent).allSatisfy(request -> assertThat(request.header("If-Match")).isEqualTo("\"workspace-revision-2\""));
    }

    @Test void completedReconciliationDoesNotNavigateBackFromASelectedHistoricalRevision() throws Exception {
        NEEDS_RECONCILIATION.set(true); currentWithHistoricalBackEntry(); beginReconciliation(); prepareReconciliation();
        CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
        page.route("POST", RECONCILIATION + "/accept", request -> {
            requested.countDown(); await(release); REVISION.set(3); NEEDS_RECONCILIATION.set(false); return json(200, publication(false));
        });
        try {
            click(By.id("lifecycleReconciliationAccept")); assertThat(requested.await(10, TimeUnit.SECONDS)).isTrue();
            page.driver().navigate().back(); awaitRevision(1); release.countDown();
            assertLateSuccess("lifecycleReconciliation");
        } finally { release.countDown(); }
    }

    private static void assertLateSuccess(String prefix) {
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id(prefix + "Dialog"), "Saved in Git"));
        assertThat(page.driver().findElement(By.id(prefix + "Dialog")).getText()).contains("selected architecture state has changed");
        assertThat(page.driver().getCurrentUrl()).contains("revision=1");
        assertThat(page.driver().executeScript("return TaxonomyArchitectureLifecycle.state().context.revision;")).isEqualTo(1L);
        assertThat(page.driver().executeScript("return TaxonomyAppChrome.getContext().editable;")).isEqualTo(false);
    }
    private static void assertStaleDialog(String prefix) {
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id(prefix + "Dialog"), "selected architecture state has changed"));
        var accept = page.driver().findElement(By.id(prefix + "Accept"));
        assertThat(accept.isDisplayed() && accept.isEnabled()).isFalse();
        assertThat(page.driver().findElement(By.id(prefix + "Preview")).isEnabled()).isFalse();
    }
    private static void currentWithHistoricalBackEntry() { open("revision=1"); awaitRevision(1); navigateSelection("revision=2"); awaitRevision(2); }
    private static void openReview() {
        expand(); click(By.cssSelector("[data-lifecycle-action='review']"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("lifecycleReviewDialog")));
        page.driver().findElement(By.id("lifecycleReviewReason")).sendKeys("Keep this reviewed rationale");
    }
    private static void prepareReview() { click(By.id("lifecycleReviewPreview")); wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("lifecycleReviewAccept"))); }
    private static void beginReconciliation() {
        expand(); click(By.cssSelector("[data-lifecycle-action='reconcile']"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("lifecycleReconciliationDialog")));
        page.driver().findElement(By.id("lifecycleReconciliationReason")).sendKeys("Keep my reconciliation reason");
        click(By.id("lifecycleReconciliationPreview"));
        var select = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-conflict-id]")));
        new Select(select).selectByValue("LOCAL");
    }
    private static void prepareReconciliation() { click(By.id("lifecycleReconciliationPreview")); wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("lifecycleReconciliationAccept"))); }
    private static void expand() {
        var details = page.driver().findElement(By.cssSelector("#architectureLifecycle > details"));
        if (!"true".equals(details.getDomProperty("open"))) click(By.cssSelector("#architectureLifecycle > details > summary"));
    }
    private static void click(By locator) {
        var element = wait.until(ExpectedConditions.elementToBeClickable(locator));
        page.driver().executeScript("arguments[0].scrollIntoView({block:'center',behavior:'instant'});",element); element.click();
    }
    private static void awaitRevision(int revision) {
        wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("return window.TaxonomyArchitectureLifecycle?.state()?.context.revision === arguments[0] && document.getElementById('architectureEditor').getAttribute('aria-busy') === 'false';",revision)));
    }
    private static void open(String selection) {
        page.openAt("architecture-editor", "", "/architecture/editor", "en", """
                localStorage.clear(); sessionStorage.clear();
                history.replaceState(null,'','/architecture/editor?lang=en&repositoryId=fixture-architecture&workspaceId=fixture-space&workspaceScopeKey=fixture-space&branch=draft&%s');
                """.formatted(selection));
    }
    private static void openForCreationRecovery() {
        page.openAt("architecture-editor", "", "/architecture/editor", "en", """
                localStorage.clear(); sessionStorage.clear();
                if(!new URLSearchParams(location.search).has('repositoryId')) {
                    history.replaceState(null,'','/architecture/editor?lang=en&repositoryId=fixture-architecture&workspaceId=fixture-space&workspaceScopeKey=fixture-space&branch=draft&revision=2');
                }
                """);
    }
    private static void navigateSelection(String selection) {
        page.driver().executeScript("""
                const url=new URL(location.href); url.searchParams.delete('revision');url.searchParams.delete('commit');
                new URLSearchParams(arguments[0]).forEach((value,key)=>url.searchParams.set(key,value));
                history.pushState(null,'',url);dispatchEvent(new PopStateEvent('popstate'));
                """,selection);
    }
    private static long countPosts(String path) { return page.requests().stream().filter(r -> r.method().equals("POST") && r.path().equals(path)).count(); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(20,TimeUnit.SECONDS)) throw new AssertionError("Fixture response was not released"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private static Map<String,String> query(String query) {
        var values = new LinkedHashMap<String,String>();
        if (query != null) for (String part:query.split("&")) { String[] pair=part.split("=",2); values.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):""); }
        return values;
    }
    private static Map<String,Object> context(int revision) {
        return new LinkedHashMap<>(Map.of("repositoryId","fixture-architecture","workspaceScopeKey","fixture-space","branch","draft",
                "commit",HEAD,"revision",revision,"actor","fixture-user","writeMode","PRIVATE_WORKSPACE"));
    }
    private static Map<String,Object> selectedContext(Map<String,String> query) {
        int revision=query.containsKey("commit")?1:Integer.parseInt(query.getOrDefault("revision",String.valueOf(REVISION.get())));
        var value=context(revision); value.put("repositoryId",query.getOrDefault("repositoryId","fixture-architecture"));
        value.put("workspaceScopeKey",query.getOrDefault("workspaceScopeKey","fixture-space")); value.put("branch",query.getOrDefault("branch","draft"));
        if(query.containsKey("commit")) value.put("commit",query.get("commit")); return value;
    }
    private static Map<String,Object> creationArchitecture(String state) {
        return Map.of("repositoryId","recovered-architecture","displayName","Recovered architecture","slug","architecture-stable-retry",
                "defaultBranch","main","description","The original description, retained after failure.","owned",true,"lifecycleState",state);
    }
    private static String projection(Map<String,String> query) {
        if(query.containsKey("commit") || Integer.parseInt(query.getOrDefault("revision",String.valueOf(REVISION.get())))<REVISION.get()) return "HISTORICAL";
        return PENDING.get()?"CHECKPOINT_PENDING":NEEDS_RECONCILIATION.get()?"VERSION_CHANGED":"READY";
    }
    private static Map<String,Object> editor(String raw) {
        var q=query(raw); var c=selectedContext(q); var projection=projection(q);
        var document=Map.of("context",c,"dsl",projection.equals("HISTORICAL")?"historical revision 1":"current revision " + c.get("revision"),
                "projectionState",projection,"history",List.of(),"versions",List.of(),"source",q.containsKey("commit")?"GIT_CHECKPOINT":"WORKSPACE_REVISION");
        return Map.of("mayEdit",projection.equals("READY"),"document",document,
                "schema",Map.of("elementTypes",List.of("Capability"),"relationTypes",List.of(),"relationStatuses",List.of("proposed"),"planningProfiles",List.of(),"layoutStrategy","stable"),
                "model",Map.of("elements",List.of(),"relations",List.of(),"requirements",List.of(),"evidence",List.of(),"packages",List.of(),"views",List.of()),
                "scene",Map.of("nodes",List.of(),"edges",List.of(),"width",1000,"height",420),"searchIndex",List.of(),"planningInformation",Map.of());
    }
    private static Map<String,Object> overview(String raw) {
        var q=query(raw); var projection=projection(q);
        return Map.of("context",selectedContext(q),"architectureTitle","Fixture architecture","source",q.containsKey("commit")?"GIT_CHECKPOINT":"WORKSPACE_REVISION",
                "projectionState",projection,"publicationState",projection.equals("READY")?"CHECKPOINTED":projection,"mayEdit",projection.equals("READY"),
                "projects",List.of(project()),"requirements",List.of(Map.ofEntries(Map.entry("id","fixture-requirement"),Map.entry("projectId",42),Map.entry("projectKey","PROJECT-42"),
                        Map.entry("requirementId",7),Map.entry("requirementKey","REQ-7"),Map.entry("title","Access requirement"),Map.entry("version",2),Map.entry("versionId",12),
                        Map.entry("contentHash","fixture-hash"),Map.entry("text","Authored requirement"),Map.entry("realization",Map.of("state","OPEN")),Map.entry("contributors",List.of()),Map.entry("links",List.of()))),
                "dependencies",List.of());
    }
    private static Map<String,Object> project() { return Map.of("id",42,"projectKey","PROJECT-42","title","Fixture project","status","ACTIVE"); }
    private static Map<String,Object> requirement() { return Map.of("id",7,"projectId",42,"requirementKey","REQ-7","title","Access requirement","currentVersion",Map.of("id",12,"versionNumber",2,"contentHash","fixture-hash","text","Authored requirement")); }
    private static Map<String,Object> publication(boolean pending) { return Map.of("context",context(3),"state",pending?"CHECKPOINT_PENDING":"CHECKPOINTED"); }
    private static Map<String,Object> reconciliationPreview(PortfolioClientTestPage.Request request) {
        var command=JSON.readTree(request.body()); boolean chosen=command.path("resolutions").path("element:fixture").asText().equals("LOCAL");
        var conflict=new LinkedHashMap<String,Object>(); conflict.put("id","element:fixture");conflict.put("reason","Both sources changed the element title");
        conflict.put("base","Original title");conflict.put("local","Local title");conflict.put("incoming","Incoming title");conflict.put("resolution",chosen?"LOCAL":null);
        var value=new LinkedHashMap<String,Object>(); value.put("context",context(2));value.put("headCommit",HEAD);value.put("fingerprint",chosen?"reviewed-local":"unresolved");
        value.put("baseDsl","Original title");value.put("localDsl","Local title");value.put("incomingDsl","Incoming title");value.put("mergedDsl",chosen?"Local title":null);
        value.put("changes",List.of());value.put("conflicts",List.of(conflict));value.put("canAccept",chosen);return value;
    }
}

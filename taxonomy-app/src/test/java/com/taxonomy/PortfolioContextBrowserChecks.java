package com.taxonomy;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.SourceReference;
import org.assertj.core.api.SoftAssertions;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.taxonomy.PortfolioContextHttpFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Browser-only checks called by the existing JUnit/Failsafe portfolio owner. */
final class PortfolioContextBrowserChecks {
    private static final List<String> PREFERENCES = List.of("taxonomy_language", "taxonomy.portfolio.projectId",
            "taxonomy.portfolio.analysisJobs.v2");
    private final RemoteWebDriver driver;
    private final WebDriverWait wait;
    private final PortfolioContextHttpFixture fixtures;
    private final String origin;
    private final String context;
    private final Path evidence;
    private final HasCdp cdp;

    private PortfolioContextBrowserChecks(RemoteWebDriver driver, PortfolioContextHttpFixture fixtures,
                                          String origin, String context) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        this.fixtures = fixtures;
        this.origin = origin;
        this.context = context;
        this.evidence = Path.of("target", "portfolio-context-evidence", context.isEmpty() ? "root" : "taxonomy");
        // Raw CDP commands use the installed Chrome driver endpoint; no versioned
        // DevTools dependency or browser-function replacement is required.
        this.cdp = (HasCdp) new Augmenter().augment(driver);
    }

    static void run(RemoteWebDriver driver, PortfolioContextHttpFixture fixtures,
                    String origin, String context) throws IOException {
        new PortfolioContextBrowserChecks(driver, fixtures, origin, context).run();
    }

    private void run() throws IOException {
        String originalUrl = driver.getCurrentUrl();
        String originalLocale = driver.findElement(By.tagName("html")).getDomAttribute("lang");
        List<Cookie> originalLocaleCookies = driver.manage().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("lang")).toList();
        Object preferences = driver.executeScript("""
                return Object.fromEntries(arguments[0].map(key => [key, localStorage.getItem(key)]));
                """, PREFERENCES);
        Files.createDirectories(evidence);
        Throwable failure = null;
        try {
            driver.executeScript("""
                    localStorage.setItem('taxonomy_language', 'de');
                    localStorage.setItem('taxonomy.portfolio.projectId', arguments[0]);
                    localStorage.setItem('taxonomy.portfolio.analysisJobs.v2', '[]');
                    """, String.valueOf(PROJECT_A));
            driver.get("about:blank"); // Stop application timers before activating the write boundary.
            fixtures.activate(context);
            viewport(1366, 768);
            open("/projects?lang=de");
            visible("projectListEmpty"); visible("noProjectSelected"); hidden("portfolioBusy"); hidden("projectTools");
            fixtures.projectsAvailable = true;
            driver.navigate().refresh();
            pageReady("selectedProjectKey", "QA-CONTEXT-A", "portfolioBusy");
            verifyPortfolioSpacePriority();
            verifyProjectNavigation(1366, 768);
            verifyProjectNavigation(1920, 1080);
            verifyProjectNavigation(390, 844);
            viewport(1366, 768);
            showProject(PROJECT_A);
            verifyVersionConflict();
            verifyMatrixMeaning();
            verifyVersioningRefresh();
            assertThat(fixtures.reads).contains("/api/git/state", "/api/projects/git/export");
            assertThat(fixtures.unexpected).as("Only scoped fixture traffic is permitted").isEmpty();
            assertThat(fixtures.submittedVersions).hasSize(1);
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            try { screenshot("failure"); } catch (IOException | RuntimeException diagnostic) { error.addSuppressed(diagnostic); }
            throw error;
        } finally {
            Throwable cleanupFailure = null;
            try {
                if (fixtures.readGate != null) fixtures.readGate.release.countDown();
                if (fixtures.analysisJobReadGate != null) fixtures.analysisJobReadGate.release.countDown();
                open("/__qa_portfolio_context_cleanup__");
                restorePreferences(preferences);
                // Real ?lang= navigation is persisted by CookieLocaleResolver.
                // Restore its cookie as well as localStorage before reloading.
                driver.manage().deleteCookieNamed("lang");
                originalLocaleCookies.forEach(driver.manage()::addCookie);
            } catch (RuntimeException error) { cleanupFailure = error; }
            fixtures.deactivate();
            try {
                cdp.executeCdpCommand("Emulation.clearDeviceMetricsOverride", Map.of());
                driver.get(originalUrl);
                wait.until(browser -> Boolean.TRUE.equals(driver.executeScript(
                        "return !!window.TaxonomyI18n && document.documentElement.lang === arguments[0]", originalLocale)));
                if (!driver.findElements(By.id("portfolioBusy")).isEmpty()) hidden("portfolioBusy");
                restorePreferences(preferences);
            } catch (RuntimeException error) {
                if (cleanupFailure == null) cleanupFailure = error; else cleanupFailure.addSuppressed(error);
            }
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else throw new AssertionError("Portfolio browser fixture cleanup failed", cleanupFailure);
            }
        }
    }

    private void verifyPortfolioSpacePriority() throws IOException {
        SoftAssertions checks = new SoftAssertions();
        for (int[] size : List.of(new int[]{1366, 768}, new int[]{1920, 1080}, new int[]{390, 844})) {
            viewport(size[0], size[1]);
            showProject(PROJECT_B);
            driver.executeScript("window.scrollTo({top:0,left:0,behavior:'instant'})");
            Map<?, ?> geometry = (Map<?, ?>) driver.executeScript("""
                    const shown=element => !!element && !!element.getClientRects().length
                        && getComputedStyle(element).visibility!=='hidden';
                    const bounds=element => {
                        const rect=element.getBoundingClientRect();
                        return {x:rect.x,y:rect.y,top:rect.top,left:rect.left,right:rect.right,
                            bottom:rect.bottom,width:rect.width,height:rect.height,visible:shown(element)};
                    };
                    const metrics=document.getElementById('portfolioMetrics');
                    const cards=Array.from(metrics.querySelectorAll('.portfolio-metric'),card => {
                        const parts=Array.from(card.querySelectorAll('.metric-value,.metric-label'));
                        return {...bounds(card),label:card.querySelector('.metric-label').innerText,
                            value:card.querySelector('.metric-value').innerText,
                            unclipped:parts.every(part => shown(part) && part.clientWidth>0 && part.clientHeight>0
                                && part.scrollWidth<=part.clientWidth+1 && part.scrollHeight<=part.clientHeight+1)};
                    });
                    const firstRow=document.querySelector('#requirementsTable tbody tr');
                    return {viewport:{width:innerWidth,height:innerHeight},scroll:{x:scrollX,y:scrollY},cards,
                        metrics:bounds(metrics),jobs:bounds(document.getElementById('portfolioJobCenter')),
                        heading:bounds(document.querySelector('#requirementsPane h3')),
                        create:bounds(document.querySelector('#requirementsPane [data-bs-target="#requirementModal"]')),
                        firstRow:bounds(firstRow),firstLink:bounds(firstRow.querySelector('a')),
                        emptyText:document.getElementById('portfolioJobList').innerText,
                        filterVisible:shown(document.getElementById('portfolioJobFilter')),
                        summaryVisible:shown(document.getElementById('portfolioJobSummary')),
                        helpVisible:shown(document.getElementById('portfolioJobCenterHelp'))};
                    """);
            Files.writeString(evidence.resolve("space-priority-" + size[0] + ".json"),
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(geometry));
            screenshot("space-priority-" + size[0]);
            noOverflow();
            checks.assertThat(number(geometry, "scroll", "x")).isZero();
            checks.assertThat(number(geometry, "scroll", "y")).isZero();
            List<?> cards = (List<?>) geometry.get("cards");
            checks.assertThat(cards).hasSize(6).allSatisfy(value -> {
                Map<?, ?> card = (Map<?, ?>) value;
                assertThat(card.get("visible")).isEqualTo(true);
                assertThat(card.get("unclipped")).as("Complete metric text: %s", card).isEqualTo(true);
                assertThat(((Number) card.get("width")).doubleValue()).isPositive();
                assertThat(((Number) card.get("height")).doubleValue()).isPositive();
            });
            checks.assertThat(geometry.get("emptyText")).isEqualTo("Keine Analysejobs vorhanden.");
            for (String control : List.of("filterVisible", "summaryVisible", "helpVisible")) {
                checks.assertThat(geometry.get(control)).as("An empty history has no unused %s", control).isEqualTo(false);
            }
            checks.assertThat(((Map<?, ?>) geometry.get("jobs")).get("visible")).isEqualTo(true);
            checks.assertThat(number(geometry, "jobs", "width")).isPositive();
            checks.assertThat(number(geometry, "jobs", "height"))
                    .as("An empty job history remains a compact orientation cue")
                    .isPositive()
                    .isLessThanOrEqualTo(size[0] >= 1000 ? 80 : 104);
            if (size[0] >= 1000) {
                List<Long> cardTops = cards.stream().map(value ->
                        Math.round(((Number) ((Map<?, ?>) value).get("top")).doubleValue())).toList();
                checks.assertThat(cardTops).as("All six desktop metrics occupy one row").containsOnly(cardTops.getFirst());
                checks.assertThat(number(geometry, "metrics", "height")).isLessThanOrEqualTo(112);
                for (String part : List.of("heading", "create", "firstRow", "firstLink")) {
                    Map<?, ?> rect = (Map<?, ?>) geometry.get(part);
                    checks.assertThat(rect.get("visible")).as("Visible desktop %s", part).isEqualTo(true);
                    checks.assertThat(number(geometry, part, "width")).isPositive();
                    checks.assertThat(number(geometry, part, "height")).isPositive();
                    checks.assertThat(number(geometry, part, "top")).isGreaterThanOrEqualTo(0);
                    checks.assertThat(number(geometry, part, "left")).isGreaterThanOrEqualTo(0);
                    checks.assertThat(number(geometry, part, "right")).isLessThanOrEqualTo(size[0]);
                    checks.assertThat(number(geometry, part, "bottom"))
                            .as("%s is visible without scrolling at %s: %s", part, size[0], geometry)
                            .isLessThanOrEqualTo(size[1]);
                }
            } else {
                checks.assertThat(number(geometry, "metrics", "height"))
                        .as("Mobile metrics remain compact while labels wrap").isLessThanOrEqualTo(300);
            }
        }
        verifyMetricTextReflow(checks);
        verifyAnalysisJobStates(checks);
        checks.assertAll();
    }

    private void verifyMetricTextReflow(SoftAssertions checks) throws IOException {
        viewport(1366, 768);
        showProject(PROJECT_B);
        try {
            driver.executeScript("document.documentElement.style.fontSize='200%'");
            Map<?, ?> geometry = (Map<?, ?>) driver.executeScript("""
                    const metrics=document.getElementById('portfolioMetrics');
                    metrics.scrollIntoView({block:'center',behavior:'instant'});
                    const parts=Array.from(metrics.querySelectorAll('.metric-value,.metric-label'),part => ({
                        text:part.innerText,clientWidth:part.clientWidth,scrollWidth:part.scrollWidth,
                        clientHeight:part.clientHeight,scrollHeight:part.scrollHeight,
                        visible:!!part.getClientRects().length && getComputedStyle(part).visibility!=='hidden'
                    }));
                    const words=[];
                    for(const label of metrics.querySelectorAll('.metric-label')) {
                        const walker=document.createTreeWalker(label,NodeFilter.SHOW_TEXT);
                        for(let node=walker.nextNode();node;node=walker.nextNode()) {
                            for(const match of node.textContent.matchAll(/\\S+/g)) {
                                const range=document.createRange();
                                range.setStart(node,match.index);
                                range.setEnd(node,match.index+match[0].length);
                                const lines=new Set(Array.from(range.getClientRects())
                                    .filter(rect => rect.width>0 && rect.height>0)
                                    .map(rect => Math.round(rect.top)));
                                words.push({text:match[0],lines:lines.size});
                            }
                        }
                    }
                    const sidebar=document.querySelector('.portfolio-sidebar');
                    const header=sidebar.querySelector('.card-header').getBoundingClientRect();
                    const action=sidebar.querySelector('[data-bs-target="#projectModal"]').getBoundingClientRect();
                    const sidebarBody=sidebar.querySelector('.card-body');
                    return {rootFont:getComputedStyle(document.documentElement).fontSize,
                        viewport:{width:innerWidth,height:innerHeight},parts,words,
                        sidebar:{clientWidth:sidebarBody.clientWidth,scrollWidth:sidebarBody.scrollWidth},
                        newProject:{width:action.width,height:action.height,left:action.left,right:action.right,
                            headerLeft:header.left,headerRight:header.right}};
                    """);
            Files.writeString(evidence.resolve("space-priority-text-200.json"),
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(geometry));
            screenshot("space-priority-text-200");
            List<?> parts = (List<?>) geometry.get("parts");
            checks.assertThat(parts).hasSize(12);
            checks.assertThat(parts).allSatisfy(value -> {
                Map<?, ?> part = (Map<?, ?>) value;
                assertThat(part.get("visible")).isEqualTo(true);
                assertThat(((Number) part.get("clientWidth")).doubleValue()).isPositive();
                assertThat(((Number) part.get("scrollWidth")).doubleValue())
                        .as("Enlarged metric text stays inside its own card: %s", part)
                        .isLessThanOrEqualTo(((Number) part.get("clientWidth")).doubleValue() + 1);
                assertThat(((Number) part.get("scrollHeight")).doubleValue())
                        .isLessThanOrEqualTo(((Number) part.get("clientHeight")).doubleValue() + 1);
            });
            checks.assertThat((List<?>) geometry.get("words")).isNotEmpty().allSatisfy(value -> {
                Map<?, ?> word = (Map<?, ?>) value;
                assertThat(((Number) word.get("lines")).intValue())
                        .as("Enlarged metric labels remain readable without breaking individual words: %s", word)
                        .isEqualTo(1);
            });
            checks.assertThat(number(geometry, "sidebar", "scrollWidth"))
                    .as("Enlarged project names and statuses remain readable without sideways scrolling")
                    .isLessThanOrEqualTo(number(geometry, "sidebar", "clientWidth") + 1);
            checks.assertThat(number(geometry, "newProject", "width")).isPositive();
            checks.assertThat(number(geometry, "newProject", "height")).isPositive();
            checks.assertThat(number(geometry, "newProject", "left"))
                    .isGreaterThanOrEqualTo(number(geometry, "newProject", "headerLeft"));
            checks.assertThat(number(geometry, "newProject", "right"))
                    .as("The new-project action stays inside its header at enlarged text size")
                    .isLessThanOrEqualTo(number(geometry, "newProject", "headerRight"));
            noOverflow();
        } finally {
            driver.executeScript("document.documentElement.style.fontSize=''");
        }
    }

    private static double number(Map<?, ?> geometry, String part, String key) {
        return ((Number) ((Map<?, ?>) geometry.get(part)).get(key)).doubleValue();
    }

    private void verifyAnalysisJobStates(SoftAssertions checks) throws IOException {
        viewport(1366, 768);
        showProject(PROJECT_A);
        driver.get("about:blank");
        fixtures.jobListReads.set(0);
        fixtures.showAnalysisJobs();
        open("/projects?lang=de");
        pageReady("selectedProjectKey", "QA-CONTEXT-A", "portfolioBusy");
        wait.until(browser -> driver.findElements(By.cssSelector("#portfolioJobList .portfolio-job")).size() == 3);
        visible("portfolioJobFilter"); visible("portfolioJobSummary"); visible("portfolioJobCenterHelp");
        checks.assertThat(driver.findElement(By.id("portfolioJobSummary")).getText())
                .contains("Wartend: 1", "Laufend: 1", "Fehlgeschlagen: 1");

        select("portfolioJobFilter", "SUCCESS");
        wait.until(browser -> driver.findElements(By.cssSelector("#portfolioJobList .portfolio-job")).isEmpty());
        checks.assertThat(driver.findElement(By.id("portfolioJobList")).getText())
                .as("A filter with no matches must not claim that no jobs exist")
                .isEqualTo("Keine Analysejobs mit diesem Status.");
        visible("portfolioJobFilter"); visible("portfolioJobSummary");
        screenshot("jobs-filtered-empty");

        select("portfolioJobFilter", "PENDING");
        Map<?, ?> pending = jobState("qa-pending");
        checks.assertThat((String) pending.get("text")).contains("Wartend");
        checks.assertThat(pending.get("progress")).isEqualTo("0");
        select("portfolioJobFilter", "FAILED");
        Map<?, ?> failed = jobState("qa-failed");
        checks.assertThat((String) failed.get("text")).contains("Fehlgeschlagen");
        checks.assertThat(failed.get("retryVisible")).isEqualTo(true);
        verifyJobInteractionContinuity(checks);
        checks.assertThat((String) jobState("qa-failed").get("details"))
                .contains("QA-REQ-0", "QA-REQ-65", "QA: Analyse für diesen Eintrag fehlgeschlagen.");
        viewport(390, 844);
        noOverflow();
        scrollJob("qa-failed"); screenshot("jobs-failed-mobile");
        viewport(1366, 768);

        select("portfolioJobFilter", "RUNNING");
        checks.assertThat(jobState("qa-running").get("progress")).isEqualTo("50");
        visible("portfolioJobFilter"); visible("portfolioJobCenterHelp"); hidden("portfolioBusy");
        // Observe all requests in the existing bounded discovery sequence
        // before exercising a later failure and recovery through detail polls.
        wait.until(browser -> fixtures.jobListReads.get() >= 3);
        fixtures.jobPollingUnavailable = true;
        wait.until(browser -> ((String) jobState("qa-running").get("text")).contains("HTTP 503"));
        checks.assertThat(jobState("qa-running").get("progressVisible")).isEqualTo(true);
        scrollJob("qa-running"); screenshot("jobs-poll-error");
        int previousPolls = fixtures.successfulJobPolls.get();
        fixtures.completeRunningAnalysis();
        fixtures.jobPollingUnavailable = false;
        wait.until(browser -> fixtures.successfulJobPolls.get() > previousPolls);
        wait.until(browser -> driver.findElements(By.cssSelector("#portfolioJobList .portfolio-job")).isEmpty()
                && driver.findElement(By.id("portfolioJobSummary")).getText().contains("Erfolgreich: 1"));
        hidden("portfolioBusy");
        checks.assertThat(driver.findElement(By.id("portfolioJobFilter")).getDomProperty("value")).isEqualTo("RUNNING");
        checks.assertThat(driver.findElement(By.id("portfolioJobList")).getText())
                .as("A running job becoming terminal leaves an honest filtered-empty state")
                .isEqualTo("Keine Analysejobs mit diesem Status.");
        select("portfolioJobFilter", "SUCCESS");
        checks.assertThat((String) jobState("qa-running").get("text"))
                .as("A successful poll clears an obsolete connection warning").doesNotContain("HTTP 503");
        screenshot("jobs-poll-recovered");
        select("portfolioJobFilter", "");
        wait.until(browser -> driver.findElements(By.cssSelector("#portfolioJobList .portfolio-job")).size() == 3);

        open("/__qa_portfolio_context_cleanup__");
        fixtures.analysisJobs = List.of();
        driver.executeScript("localStorage.setItem('taxonomy.portfolio.analysisJobs.v2','[]')");
        open("/projects?lang=de");
        pageReady("selectedProjectKey", "QA-CONTEXT-A", "portfolioBusy");
    }

    private void verifyJobInteractionContinuity(SoftAssertions checks) throws IOException {
        Map<String, Object> states = new LinkedHashMap<>();
        // Finish startup discovery, then hold both active jobs at the HTTP
        // boundary while native keyboard interactions establish the view.
        wait.until(browser -> fixtures.jobListReads.get() >= 3);
        var otherJobGate = fixtures.delayAnalysisJobReads();
        try {
            wait.until(browser -> otherJobGate.seenJobs.containsAll(Set.of("qa-pending", "qa-running")));
            select("portfolioJobFilter", "FAILED");
            // Close the native select popup before a separate Tab action;
            // a Tab bundled with Enter can be consumed by that popup.
            visible("portfolioJobFilter").sendKeys(Keys.ESCAPE);
            key(Keys.TAB);
            Map<?, ?> beforeToggle = jobInteractionState("qa-failed");
            states.put("beforeToggle", beforeToggle);
            assertThat(beforeToggle.get("focusedAction"))
                    .as("Native Tab from the job filter reaches the details toggle").isEqualTo("toggle");
            key(Keys.ENTER);
            Map<?, ?> afterToggle = jobInteractionState("qa-failed");
            states.put("afterToggle", afterToggle);
            checks.assertThat(afterToggle.get("expanded")).isEqualTo(true);
            checks.assertThat(afterToggle.get("focusedAction"))
                    .as("Enter on job details keeps keyboard focus on that job's details toggle")
                    .isEqualTo("toggle");
            key(Keys.TAB);
            Map<?, ?> afterTab = jobInteractionState("qa-failed");
            states.put("afterNextTab", afterTab);
            checks.assertThat(afterTab.get("focusedAction"))
                    .as("The next Tab reaches the failed job's retry action after opening its details")
                    .isEqualTo("retry");

            select("portfolioJobFilter", "");
            visible("portfolioJobFilter").sendKeys(Keys.ESCAPE);
            focusJobToggleByKeyboard("qa-running");
            key(Keys.ENTER);
            viewport(390, 844);
            Map<?, ?> beforeOtherPoll = scrollJobDetailsByKeyboard("qa-failed");
            states.put("beforeOtherJobPoll", beforeOtherPoll);
            int previousPolls = fixtures.successfulJobPolls.get();
            fixtures.advanceRunningAnalysisAttempt();
            otherJobGate.release.countDown();
            wait.until(browser -> fixtures.successfulJobPolls.get() > previousPolls
                    && "2".equals(jobInteractionState("qa-running").get("lastAttempt")));
            Map<?, ?> afterOtherPoll = jobInteractionState("qa-failed");
            states.put("afterOtherJobPoll", afterOtherPoll);
            states.put("otherJobPollResponse", jobInteractionState("qa-running"));
            states.put("otherJobHttpPolls", Map.of("before", previousPolls,
                    "after", fixtures.successfulJobPolls.get()));
            assertJobInteractionPreserved(checks, "A different job's HTTP poll", beforeOtherPoll, afterOtherPoll);
            screenshot("jobs-interaction-after-other-poll");

            var ownJobGate = fixtures.delayAnalysisJobReads();
            try {
                wait.until(browser -> ownJobGate.seenJobs.containsAll(Set.of("qa-pending", "qa-running")));
                Map<?, ?> beforeOwnPoll = scrollJobDetailsByKeyboard("qa-running");
                states.put("beforeOwnJobPoll", beforeOwnPoll);
                int previousOwnPolls = fixtures.successfulJobPolls.get();
                fixtures.advanceRunningAnalysisAttempt();
                ownJobGate.release.countDown();
                wait.until(browser -> fixtures.successfulJobPolls.get() > previousOwnPolls
                        && "3".equals(jobInteractionState("qa-running").get("lastAttempt")));
                Map<?, ?> afterOwnPoll = jobInteractionState("qa-running");
                states.put("afterOwnJobPoll", afterOwnPoll);
                states.put("ownJobHttpPolls", Map.of("before", previousOwnPolls,
                        "after", fixtures.successfulJobPolls.get()));
                assertJobInteractionPreserved(checks, "The expanded job's own HTTP poll", beforeOwnPoll, afterOwnPoll);
                checks.assertThat(afterOwnPoll.get("lastAttempt"))
                        .as("Keeping focus and scroll position must still render the fresh job response")
                        .isEqualTo("3");
                screenshot("jobs-interaction-after-own-poll");
            } finally {
                ownJobGate.release.countDown();
            }
        } finally {
            otherJobGate.release.countDown();
            Files.writeString(evidence.resolve("jobs-interaction-state.json"),
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(states));
        }
    }

    private Map<?, ?> scrollJobDetailsByKeyboard(String id) {
        // Establish button focus separately before following the native tab
        // order into the horizontally scrollable detail region.
        focusJobToggleByKeyboard(id);
        key(Keys.TAB);
        if (Boolean.TRUE.equals(jobState(id).get("retryVisible"))) key(Keys.TAB);
        Map<?, ?> before = jobInteractionState(id);
        assertThat(before.get("focusedAction"))
                .as("The mobile details table is reachable by native Tab: %s", before)
                .isEqualTo("details-scroll");
        assertThat(((Number) before.get("maxScrollLeft")).doubleValue())
                .as("The mobile fixture genuinely requires internal horizontal scrolling")
                .isPositive();
        wait.until(browser -> {
            key(Keys.ARROW_RIGHT);
            Map<?, ?> current = jobInteractionState(id);
            return ((Number) current.get("scrollLeft")).doubleValue()
                    >= ((Number) current.get("maxScrollLeft")).doubleValue() - 1;
        });
        Map<?, ?> scrolled = jobInteractionState(id);
        assertThat(((Number) scrolled.get("lastCellRight")).doubleValue())
                .as("Native Arrow Right exposes the last result column")
                .isLessThanOrEqualTo(((Number) scrolled.get("tableRight")).doubleValue() + 1);
        noOverflow();
        return scrolled;
    }

    private void focusJobToggleByKeyboard(String id) {
        driver.findElement(By.cssSelector(jobSelector(id) + " .job-toggle")).sendKeys(Keys.ESCAPE);
        assertThat(jobInteractionState(id).get("focusedAction"))
                .as("Native input focuses the %s details button before the next key action", id)
                .isEqualTo("toggle");
    }

    private void assertJobInteractionPreserved(SoftAssertions checks, String event,
                                               Map<?, ?> before, Map<?, ?> after) {
        checks.assertThat(after.get("expanded")).as("%s keeps details expanded", event).isEqualTo(true);
        checks.assertThat(after.get("focusedAction"))
                .as("%s preserves focus in the same job's details table: %s", event, after)
                .isEqualTo("details-scroll");
        checks.assertThat(((Number) after.get("scrollLeft")).doubleValue())
                .as("%s preserves the horizontal detail reading position", event)
                .isBetween(((Number) before.get("scrollLeft")).doubleValue() - 1,
                        ((Number) before.get("scrollLeft")).doubleValue() + 1);
        checks.assertThat(after.get("scrollTop"))
                .as("%s preserves the vertical detail reading position", event)
                .isEqualTo(before.get("scrollTop"));
    }

    private Map<?, ?> jobInteractionState(String id) {
        return wait.until(browser -> (Map<?, ?>) driver.executeScript("""
                const job=document.querySelector(arguments[0]);
                if(!job || !job.getClientRects().length) return null;
                const toggle=job.querySelector('.job-toggle'), table=job.querySelector('.table-responsive');
                const active=document.activeElement, lastRow=table?.querySelector('tbody tr:last-child');
                const focusedAction=!job.contains(active) ? 'outside-job'
                    : active.matches('.job-toggle') ? 'toggle'
                    : active.matches('.job-retry') ? 'retry'
                    : active===table ? 'details-scroll' : 'other';
                return {jobUrl:job.dataset.jobUrl,focusedAction,
                    activeElement:{tag:active.tagName,id:active.id,classes:active.className},
                    expanded:toggle.getAttribute('aria-expanded')==='true',
                    scrollLeft:table?.scrollLeft,scrollTop:table?.scrollTop,
                    maxScrollLeft:table ? table.scrollWidth-table.clientWidth : null,
                    tableRight:table?.getBoundingClientRect().right,
                    lastCellRight:lastRow?.lastElementChild.getBoundingClientRect().right,
                    lastAttempt:lastRow?.children[2].textContent,
                    viewport:{width:innerWidth,height:innerHeight,scrollX,scrollY}};
                """, jobSelector(id)));
    }

    private static String jobSelector(String id) {
        return "#portfolioJobList .portfolio-job[data-job-url$='/" + id + "']";
    }

    private void scrollJob(String id) {
        wait.until(browser -> Boolean.TRUE.equals(driver.executeScript("""
                const job=document.querySelector(arguments[0]);
                if(!job || !job.getClientRects().length) return false;
                job.scrollIntoView({behavior:'instant',block:'center',inline:'nearest'});
                return true;
                """, "#portfolioJobList .portfolio-job[data-job-url$='/" + id + "']")));
    }

    private Map<?, ?> jobState(String id) {
        // Polling replaces job articles. Read each rendered state atomically
        // instead of holding an element reference across an unrelated poll.
        return wait.until(browser -> (Map<?, ?>) driver.executeScript("""
                const job=document.querySelector(arguments[0]);
                if(!job || !job.getClientRects().length) return null;
                const progress=job.querySelector('[role="progressbar"]'), retry=job.querySelector('.job-retry');
                return {text:job.innerText,progress:progress?.getAttribute('aria-valuenow'),
                    progressVisible:!!progress?.getClientRects().length,retryVisible:!!retry?.getClientRects().length,
                    details:job.querySelector('.job-details')?.innerText};
                """, "#portfolioJobList .portfolio-job[data-job-url$='/" + id + "']"));
    }

    private void verifyProjectNavigation(int width, int height) throws IOException {
        viewport(width, height);
        showProject(PROJECT_A);
        noOverflow();
        WebElement toggle = visible("projectToolsToggle");
        assertThat(toggle.getText()).isEqualTo("Projektwerkzeuge");
        scroll(toggle); focus(toggle); key(Keys.ARROW_DOWN);
        focused("projectImportLink");
        assertThat(toggle.getDomAttribute("aria-expanded")).isEqualTo("true");
        for (String name : List.of("Import", "Matrices", "Reports", "Versioning")) visible("project" + name + "Link");
        insideViewport(toggle);
        insideViewport(driver.findElement(By.cssSelector("#projectTools .dropdown-menu")));
        screenshot("tools-" + width);
        key(Keys.ARROW_DOWN); focused("projectMatricesLink");
        key(Keys.ESCAPE);
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("#projectTools .dropdown-menu")));
        focused("projectToolsToggle");
        assertThat(toggle.getDomAttribute("aria-expanded")).isEqualTo("false");
        showProject(PROJECT_B);
        var requirement = fixtures.primaryRequirement(PROJECT_B);
        WebElement detail = detailLink(PROJECT_B);
        scroll(detail);
        Map<?, ?> geometry = insideViewport(detail);
        assertThat(geometry.get("centerHit"))
                .as("The requirement link's rectangular target includes its center: %s", geometry).isEqualTo(true);
        for (String action : List.of("analyze", "snapshots", "confirm")) {
            assertThat(driver.findElement(By.cssSelector(".requirement-" + action + "[data-requirement-id='"
                    + requirement.id() + "']")).isDisplayed()).isTrue();
        }
        noOverflow();
        verifySelectedProjectContrast(width);
    }

    private void verifySelectedProjectContrast(int width) throws IOException {
        // Start with a real pointer interaction outside the list, then hover the
        // selected item. Its selected colors must survive both input modes.
        click("selectedProjectKey");
        WebElement selected = driver.findElement(By.cssSelector("#projectList .project-select.active"));
        new Actions(driver).moveToElement(selected).perform();
        wait.until(browser -> Boolean.TRUE.equals(driver.executeScript("""
                return arguments[0].matches(':hover') && !arguments[0].matches(':focus-visible')
                    && arguments[0].getAnimations({subtree:true}).every(animation => animation.playState !== 'running');
                """, selected)));
        assertSelectedProjectContrast(selected, "pointer hover");
        screenshot("selected-project-hover-" + width);

        new Actions(driver).moveToElement(driver.findElement(By.tagName("nav"))).perform();
        for (int tabs = 0; tabs < 60 && !selected.equals(driver.switchTo().activeElement()); tabs++) key(Keys.TAB);
        assertThat(driver.switchTo().activeElement())
                .as("Native Tab navigation reaches the selected project").isEqualTo(selected);
        wait.until(browser -> Boolean.TRUE.equals(driver.executeScript("""
                return arguments[0].matches(':focus-visible') && !arguments[0].matches(':hover')
                    && arguments[0].getAnimations({subtree:true}).every(animation => animation.playState !== 'running');
                """, selected)));
        assertSelectedProjectContrast(selected, "keyboard focus");
        assertThat(selected.getDomAttribute("aria-current")).isEqualTo("page");
        screenshot("selected-project-focus-" + width);
    }

    private void assertSelectedProjectContrast(WebElement selected, String state) {
        Map<?, ?> colors = (Map<?, ?>) driver.executeScript("""
                const button=arguments[0], canvas=document.createElement('canvas');
                canvas.width=canvas.height=1;
                const painter=canvas.getContext('2d'), cache=new Map();
                const rgba=value => {
                    if(!cache.has(value)) {
                        painter.clearRect(0,0,1,1); painter.fillStyle=value; painter.fillRect(0,0,1,1);
                        const pixel=Array.from(painter.getImageData(0,0,1,1).data); pixel[3]/=255;
                        cache.set(value,pixel);
                    }
                    return cache.get(value).slice();
                };
                const over=(foreground,background) => {
                    const alpha=foreground[3]+background[3]*(1-foreground[3]);
                    return alpha ? [...foreground.slice(0,3).map((value,index) =>
                        (value*foreground[3]+background[index]*background[3]*(1-foreground[3]))/alpha),alpha]
                        : [0,0,0,0];
                };
                // Composite the actual text, background and opacity at each DOM
                // ancestor; translucent metadata must not inherit an opaque score.
                const pixel=(element,withText) => {
                    let result=withText ? rgba(getComputedStyle(element).color) : [0,0,0,0];
                    for(let node=element;node;node=node.parentElement) {
                        const style=getComputedStyle(node);
                        result=over(result,rgba(style.backgroundColor)); result[3]*=Number(style.opacity);
                    }
                    return over(result,[255,255,255,1]);
                };
                const luminance=color => color.slice(0,3).map(value => value/255)
                    .map(value => value<=.04045 ? value/12.92 : ((value+.055)/1.055)**2.4)
                    .reduce((sum,value,index) => sum+value*[.2126,.7152,.0722][index],0);
                const parts=[['key',button.querySelector('strong')],
                    ['title',button.querySelector(':scope > .small:not(.opacity-75)')],
                    ['metadata',button.querySelector('.opacity-75')]].map(([part,element]) => {
                        const style=getComputedStyle(element), foreground=pixel(element,true), background=pixel(element,false);
                        const first=luminance(foreground), second=luminance(background);
                        return {part,text:element.textContent,color:style.color,opacity:style.opacity,font:style.font,
                            foreground,background,contrast:(Math.max(first,second)+.05)/(Math.min(first,second)+.05)};
                    });
                return {hover:button.matches(':hover'),focusVisible:button.matches(':focus-visible'),
                    focused:document.activeElement===button,background:getComputedStyle(button).backgroundColor,parts};
                """, selected);
        List<?> parts = (List<?>) colors.get("parts");
        assertThat(parts).hasSize(3);
        for (Object value : parts) {
            Map<?, ?> part = (Map<?, ?>) value;
            assertThat(((Number) part.get("contrast")).doubleValue())
                    .as("Selected-project %s remains readable during %s: %s", part.get("part"), state, colors)
                    .isGreaterThanOrEqualTo(4.5);
        }
    }

    private void showProject(long project) {
        click(By.cssSelector(".project-select[data-project-id='" + project + "']"));
        pageReady("selectedProjectKey", project == PROJECT_A ? "QA-CONTEXT-A" : "QA-CONTEXT-B", "portfolioBusy");
        Map<String, String> tools = Map.of("Import", "import", "Matrices", "matrices", "Reports", "reports", "Versioning", "versioning");
        tools.forEach((name, suffix) -> assertThat(driver.findElement(By.id("project" + name + "Link"))
                .getDomAttribute("href")).isEqualTo(relative("/projects/" + project + "/" + suffix + "?lang=de")));
        var requirement = fixtures.primaryRequirement(project);
        assertThat(detailLink(project).getDomAttribute("href"))
                .isEqualTo(relative("/projects/" + project + "/requirements/" + requirement.id() + "?lang=de"));
        assertThat(detailLink(project).getAccessibleName()).contains(requirement.requirementKey());
    }

    private WebElement detailLink(long project) {
        return driver.findElement(By.cssSelector("#requirementsTable tbody a[href*='/requirements/"
                + fixtures.primaryRequirement(project).id() + "?']"));
    }

    private void verifyVersionConflict() throws IOException {
        focus(detailLink(PROJECT_A)); key(Keys.ENTER);
        url("/projects/" + PROJECT_A + "/requirements/" + REQUIREMENT_A + "?lang=de");
        pageReady("requirementHeading", fixtures.primaryRequirement(PROJECT_A).title(), "detailBusy");
        wait.until(ExpectedConditions.elementToBeClickable(By.id("newVersionButton")));
        click("newVersionButton");
        assertModal("newVersionModal");
        Map<String, String> draft = new LinkedHashMap<>();
        draft.put("versionText", "  Pumpe geordnet stoppen.\nDie Bestätigung der Leitstelle abwarten.  ");
        draft.put("changeReason", "  Fachliche Präzisierung nach Prüfung  ");
        draft.put("sourceSection", "5.3.1"); draft.put("sourcePage", "27");
        draft.put("sourceOriginal", "Original aus Abschnitt 5.3.1.\nDiese Quellenangabe bleibt im Entwurf.");
        fill("versionText", draft.get("versionText")); fill("changeReason", draft.get("changeReason"));
        click(By.cssSelector("#newVersionModal details summary"));
        for (String id : List.of("sourceSection", "sourcePage", "sourceOriginal")) fill(id, draft.get(id));
        click(By.cssSelector("#newVersionForm button[type='submit']"));
        wait.until(browser -> fixtures.submittedVersions.size() == 1);
        hidden("detailBusy");
        wait.until(ExpectedConditions.textToBe(By.id("newVersionError"), CONFLICT));
        WebElement error = visible("newVersionError");
        focused("newVersionError"); assertModal("newVersionModal"); hidden("detailError");
        assertValues(draft); insideViewport(error);
        assertThat(driver.executeScript("""
                const e=arguments[0], r=e.getBoundingClientRect();
                const hit=document.elementFromPoint(r.x+r.width/2, r.y+r.height/2);
                return document.activeElement===e && e.closest('#newVersionModal')!==null
                    && (hit===e || e.contains(hit));
                """, error)).as("Focused conflict stays unobscured inside the open real Bootstrap modal").isEqualTo(true);
        SourceReference original = fixtures.primaryRequirement(PROJECT_A).currentVersion().source();
        var expected = new CreateRequirementVersionRequest(draft.get("versionText"), draft.get("changeReason"),
                new SourceReference(original.sourceArtifactId(), original.sourceVersionId(), original.sourceFragmentIds(),
                        draft.get("sourceSection"), 27, draft.get("sourceOriginal")));
        assertThat(fixtures.submittedVersions).containsExactly(expected);
        screenshot("version-conflict");
        click(By.cssSelector("#newVersionModal .btn-close")); hidden("newVersionModal");
    }

    private void verifyMatrixMeaning() throws IOException {
        click("matrixLink");
        url("/projects/" + PROJECT_A + "/matrices?lang=de");
        pageReady("matrixProject", "QA-CONTEXT-A", "matrixBusy");
        click("solutionMatrixTab");
        cellText("QA-REQ-0", "0%"); cellText("QA-REQ-EMPTY", "·");
        assertThat(cell("QA-REQ-EMPTY").getAccessibleName()).isEqualTo("QA-SOL-A, QA-REQ-EMPTY: Leer");
        click(cellLocator("QA-REQ-0")); assertModal("cellDetail");
        assertThat(visible("cellDetailBody").getText()).contains("0%", ZERO_EVIDENCE)
                .doesNotContain("Es ist keine Beziehung gespeichert.");
        assertThat(driver.findElement(By.cssSelector("#cellDetailBody a")).getDomAttribute("href"))
                .isEqualTo(relative("/projects/" + PROJECT_A + "/requirements/" + REQUIREMENT_A + "?lang=de"));
        screenshot("matrix-zero"); closeCell();
        click(cellLocator("QA-REQ-EMPTY")); assertModal("cellDetail");
        assertThat(visible("cellDetailBody").getText())
                .contains("Es ist keine Beziehung gespeichert. Dies ist kein expliziter Null-Score.")
                .doesNotContain(ZERO_EVIDENCE);
        closeCell();
        select("relationState", "related");
        cellText("QA-REQ-0", "0%"); cellText("QA-REQ-65", "65%"); noCell("QA-REQ-EMPTY");
        select("relationState", "empty"); noCell("QA-REQ-0"); cellText("QA-REQ-EMPTY", "·");
        click("resetFilters"); fill("minimumValue", "1"); noCell("QA-REQ-0"); cellText("QA-REQ-65", "65%");

        // Deliberate degraded-response test, separate from the valid sparse map above.
        fixtures.unknownCoverage = true;
        driver.navigate().refresh(); pageReady("matrixProject", "QA-CONTEXT-A", "matrixBusy");
        click("solutionMatrixTab");
        cellText("QA-REQ-0", "0%"); cellText("QA-REQ-EMPTY", "·"); cellText("QA-REQ-UNKNOWN", "?");
        assertThat(cell("QA-REQ-UNKNOWN").getAccessibleName()).isEqualTo("QA-SOL-A, QA-REQ-UNKNOWN: Unbekannt");
        scroll(visible("solutionMatrix")); screenshot("matrix-degraded-cells");
        click(cellLocator("QA-REQ-UNKNOWN")); assertModal("cellDetail");
        assertThat(visible("cellDetailBody").getText()).contains("Die Abdeckung dieser gespeicherten Beziehung ist unbekannt.")
                .doesNotContain("Es ist keine Beziehung gespeichert.", "0%");
        screenshot("matrix-unknown"); closeCell();
        select("relationState", "related"); cellText("QA-REQ-UNKNOWN", "?"); cellText("QA-REQ-0", "0%"); noCell("QA-REQ-EMPTY");
        fill("minimumValue", "1"); noCell("QA-REQ-UNKNOWN"); noCell("QA-REQ-0");
        fixtures.unknownCoverage = false;
    }

    private void verifyVersioningRefresh() throws IOException {
        click("portfolioBack"); pageReady("selectedProjectKey", "QA-CONTEXT-A", "portfolioBusy");
        click("projectToolsToggle"); click("projectVersioningLink");
        url("/projects/" + PROJECT_A + "/versioning?lang=de");
        pageReady("versioningProject", "QA-CONTEXT-A", "versioningBusy");
        for (String id : List.of("commitBranch", "materializeBranch", "mergeSource", "mergeTarget")) {
            List<WebElement> options = new Select(visible(id)).getOptions();
            assertThat(options.stream().map(WebElement::getText).toList()).containsExactlyElementsOf(BRANCHES);
            assertThat(options.stream().map(option -> option.getDomAttribute("value")).toList()).containsExactlyElementsOf(BRANCHES);
        }
        Map<String, String> messages = Map.of("commitMessage", "  Geprüfter Commit-Entwurf\nBegründung behalten.  ",
                "mergeMessage", "  Geprüfte Zusammenführung  ");
        Map<String, String> selections = Map.of("commitBranch", BRANCHES.get(1), "materializeBranch", BRANCHES.get(2),
                "mergeSource", BRANCHES.getFirst(), "mergeTarget", BRANCHES.get(2));
        messages.forEach(this::fill); selections.forEach(this::select);
        var gate = fixtures.delayNextRepositoryRead();
        fixtures.head = "b".repeat(40);
        click("refreshPreview");
        wait.until(browser -> gate.seen.get()); visible("versioningBusy");
        assertValues(messages); assertValues(selections);
        gate.release.countDown(); hidden("versioningBusy");
        wait.until(ExpectedConditions.textToBe(By.id("headCommit"), "b".repeat(12)));
        assertValues(messages); assertValues(selections); hidden("versioningError");
        scroll(visible("commitMessage")); screenshot("versioning-refresh");
    }

    private void restorePreferences(Object preferences) {
        driver.executeScript("""
                for (const [key,value] of Object.entries(arguments[0])) {
                    if(value===null) localStorage.removeItem(key); else localStorage.setItem(key,value);
                }
                """, preferences);
    }

    private void viewport(int width, int height) {
        cdp.executeCdpCommand("Emulation.setDeviceMetricsOverride", Map.of("width", width, "height", height,
                "deviceScaleFactor", 1, "mobile", false));
        wait.until(browser -> Boolean.TRUE.equals(driver.executeScript(
                "return innerWidth===arguments[0] && innerHeight===arguments[1]", width, height)));
    }

    private void noOverflow() {
        assertThat(driver.executeScript("return Math.max(document.documentElement.scrollWidth,document.body.scrollWidth)<=innerWidth+2"))
                .as("No page-wide horizontal scrolling at the actual browser viewport").isEqualTo(true);
    }

    private Map<?, ?> insideViewport(WebElement element) {
        assertThat(element.isDisplayed()).isTrue();
        Map<?, ?> geometry = (Map<?, ?>) driver.executeScript("""
                const element=arguments[0], rect=element.getBoundingClientRect(), style=getComputedStyle(element);
                const bounds=value => ({x:value.x,y:value.y,width:value.width,height:value.height,
                    right:value.right,bottom:value.bottom});
                const range=document.createRange(); range.selectNodeContents(element);
                const hit=document.elementFromPoint(rect.x+rect.width/2,rect.y+rect.height/2);
                const checks={minimumWidth:rect.width>=24,minimumHeight:rect.height>=24,left:rect.x>=-1,
                    top:rect.y>=-1,right:rect.right<=innerWidth+1,bottom:rect.bottom<=innerHeight+1};
                return {rect:bounds(rect),clientRects:Array.from(element.getClientRects(),bounds),
                    textRects:Array.from(range.getClientRects(),bounds),checks,valid:Object.values(checks).every(Boolean),
                    viewport:{innerWidth,innerHeight,scrollX,scrollY,devicePixelRatio,
                        clientWidth:document.documentElement.clientWidth,clientHeight:document.documentElement.clientHeight},
                    font:style.font,fontFamily:style.fontFamily,lineHeight:style.lineHeight,display:style.display,
                    centerHit:hit===element || element.contains(hit),
                    hit:hit ? {tag:hit.tagName,id:hit.id,className:hit.className} : null};
                """, element);
        assertThat(geometry.get("valid"))
                .as("Control fully visible inside the actual viewport: %s; geometry: %s", element, geometry).isEqualTo(true);
        return geometry;
    }

    private void screenshot(String name) throws IOException { Files.write(evidence.resolve(name + ".png"), driver.getScreenshotAs(OutputType.BYTES)); }
    private void open(String path) { driver.get(origin + relative(path)); }
    private String relative(String path) { return context + path; }
    private void url(String path) { wait.until(ExpectedConditions.urlToBe(origin + relative(path))); }
    private WebElement visible(String id) { return wait.until(ExpectedConditions.visibilityOfElementLocated(By.id(id))); }
    private void hidden(String id) { wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id(id))); }
    private void pageReady(String id, String text, String busy) {
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id(id), text)); hidden(busy);
        assertThat(driver.findElement(By.tagName("html")).getDomAttribute("lang")).isEqualTo("de");
    }
    private void focus(WebElement element) { scroll(element); driver.executeScript("arguments[0].focus()", element); }
    private void focused(String id) { wait.until(browser -> driver.findElement(By.id(id)).equals(driver.switchTo().activeElement())); }
    private void key(Keys key) { new Actions(driver).sendKeys(key).perform(); }
    private void scroll(WebElement element) { driver.executeScript("arguments[0].scrollIntoView({behavior:'instant',block:'center',inline:'nearest'})", element); }
    private void click(String id) { click(By.id(id)); }
    private void click(By locator) {
        wait.ignoring(org.openqa.selenium.ElementClickInterceptedException.class)
                .ignoring(org.openqa.selenium.StaleElementReferenceException.class).until(browser -> {
                    WebElement element = browser.findElement(locator);
                    if (!element.isDisplayed() || !element.isEnabled()) return false;
                    scroll(element); element.click(); return true;
                });
    }
    private void fill(String id, String value) { WebElement element = visible(id); element.clear(); element.sendKeys(value); }
    private void select(String id, String value) {
        WebElement control = visible(id);
        List<WebElement> options = new Select(control).getOptions();
        int index = -1;
        for (int i = 0; i < options.size(); i++) {
            if (value.equals(options.get(i).getDomAttribute("value"))) { index = i; break; }
        }
        assertThat(index).as("Select %s offers %s", id, value).isGreaterThanOrEqualTo(0);
        // Use actual browser keyboard input. ChromeDriver's option.click path
        // can emit change without the native input event used by this filter.
        control.sendKeys(Keys.HOME);
        for (int i = 0; i < index; i++) control.sendKeys(Keys.ARROW_DOWN);
        control.sendKeys(Keys.ENTER, Keys.TAB);
        assertThat(control.getDomProperty("value")).as("Native selection in %s", id).isEqualTo(value);
    }
    private void assertValues(Map<String, String> values) {
        values.forEach((id, value) -> assertThat(driver.findElement(By.id(id)).getDomProperty("value")).as(id).isEqualTo(value));
    }
    private void assertModal(String id) {
        wait.until(browser -> {
            WebElement element = browser.findElement(By.id(id));
            if (!element.isDisplayed() || !"true".equals(element.getDomAttribute("aria-modal"))) return false;
            return Boolean.TRUE.equals(driver.executeScript("""
                    const e=arguments[0];
                    if(!e.classList.contains('show') || e.classList.contains('showing') || e.classList.contains('hiding')) return false;
                    if(e.classList.contains('offcanvas')) return true;
                    const dialog=e.querySelector('.modal-dialog');
                    return getComputedStyle(e).opacity==='1' && (!dialog || getComputedStyle(dialog).transform==='none');
                    """, element));
        });
    }
    private By cellLocator(String key) { return By.cssSelector("#solutionMatrix table .matrix-drilldown[data-column='" + key + "']"); }
    private WebElement cell(String key) { return wait.until(ExpectedConditions.visibilityOfElementLocated(cellLocator(key))); }
    private void cellText(String key, String text) { wait.until(ExpectedConditions.textToBe(cellLocator(key), text)); }
    private void noCell(String key) { wait.until(browser -> driver.findElements(cellLocator(key)).isEmpty()); }
    private void closeCell() { assertModal("cellDetail"); key(Keys.ESCAPE); hidden("cellDetail"); }
}

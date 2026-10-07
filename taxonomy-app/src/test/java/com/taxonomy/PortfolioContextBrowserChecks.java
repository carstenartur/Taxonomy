package com.taxonomy;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.SourceReference;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            verifyProjectNavigation(1366, 768);
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
        scroll(detail); insideViewport(detail);
        for (String action : List.of("analyze", "snapshots", "confirm")) {
            assertThat(driver.findElement(By.cssSelector(".requirement-" + action + "[data-requirement-id='"
                    + requirement.id() + "']")).isDisplayed()).isTrue();
        }
        noOverflow();
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

    private void insideViewport(WebElement element) {
        assertThat(element.isDisplayed()).isTrue();
        assertThat(driver.executeScript("""
                const r=arguments[0].getBoundingClientRect();
                return r.width>=24 && r.height>=24 && r.x>=-1 && r.y>=-1
                    && r.right<=innerWidth+1 && r.bottom<=innerHeight+1;
                """, element)).as("Control fully visible inside the actual viewport: %s", element).isEqualTo(true);
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

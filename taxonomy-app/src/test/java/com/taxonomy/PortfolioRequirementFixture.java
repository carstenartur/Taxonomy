package com.taxonomy;

import org.openqa.selenium.By;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.taxonomy.PortfolioClientTestPage.json;
import static org.assertj.core.api.Assertions.assertThat;

/** Requirement HTTP data shared by the JUnit comparison and form-recovery contracts. */
final class PortfolioRequirementFixture {
    static final String ROOT = "/api/projects/1";
    static final String REQUIREMENT = ROOT + "/requirements/2";
    static final List<String> VERSION_FIELDS =
            List.of("versionText", "changeReason", "sourceSection", "sourcePage", "sourceOriginal");
    static final List<String> DRAFT =
            List.of("Draft text\nKeep this edit", "Reviewed interlock sequence", "Draft section", "9", "Draft source");

    final PortfolioClientTestPage page;
    final AtomicReference<Map<String, Object>> requirement = new AtomicReference<>();
    final AtomicReference<List<Map<String, Object>>> versions = new AtomicReference<>();
    final WebDriverWait wait;

    PortfolioRequirementFixture(PortfolioClientTestPage page) {
        this.page = page;
        wait = new WebDriverWait(page.driver(), Duration.ofSeconds(20));
        setVersions("Original", "Stored text");
        page.clearRoutes();
        page.clearRequests();
        page.route("GET", ROOT, request -> json(200, project()));
        page.route("GET", REQUIREMENT, request -> json(200, requirement.get()));
        page.route("GET", REQUIREMENT + "/versions", request -> json(200, versions.get()));
        page.route("GET", REQUIREMENT + "/snapshots", request -> json(200, List.of()));
        page.route("GET", REQUIREMENT + "/reformulations", request -> json(200, List.of()));
        page.route("GET", ROOT + "/portfolio", request -> json(200,
                Map.of("requirements", List.of(requirement.get()), "solutions", List.of())));
        page.route("GET", "/api/account/me", request -> json(200,
                Map.of("username", "qa-client", "architectureMutationAllowed", true)));
        page.route("POST", REQUIREMENT + "/analyses", request ->
                json(202, Map.of("id", "qa-refresh")).withHeader("Location", ROOT + "/analysis-jobs/qa-refresh"));
        page.route("GET", ROOT + "/analysis-jobs/qa-refresh", request ->
                json(200, Map.of("id", "qa-refresh", "status", "SUCCESS", "items", List.of())));
    }

    static Map<String, Object> project() {
        return Map.of("id", 1, "projectKey", "PROJECT", "title", "Control system", "status", "ACTIVE");
    }

    static Map<String, Object> version(int id, int number, String text) {
        return Map.of("id", id, "versionNumber", number, "text", text,
                "contentHash", "a".repeat(64), "changeReason", number == 1 ? "First" : "Second",
                "createdBy", "qa-client", "createdAt", "2026-10-07T09:00:00Z",
                "source", Map.of("sourceArtifactId", 91, "sourceVersionId", 92,
                        "sourceFragmentIds", List.of(93), "sectionReference", "Stored section",
                        "pageNumber", 4, "originalText", "Stored source"));
    }

    void setVersions(String older, String newer) {
        var current = version(12, 2, newer);
        versions.set(List.of(current, version(11, 1, older)));
        var value = new LinkedHashMap<String, Object>();
        value.put("id", 2);
        value.put("projectId", 1);
        value.put("requirementKey", "REQ-2");
        value.put("title", "Pump control");
        value.put("status", "DRAFT");
        value.put("reviewStatus", "PROPOSED");
        value.put("requirementType", "FUNCTIONAL");
        value.put("criticality", "HIGH");
        value.put("ownerUsername", "qa-client");
        value.put("currentVersionId", 12);
        value.put("currentVersion", current);
        requirement.set(value);
    }

    void updateBaseline(String text, String title) {
        var history = new ArrayList<>(versions.get());
        int id = history.stream().mapToInt(version -> ((Number) version.get("id")).intValue()).max().orElse(0) + 1;
        int number = history.stream().mapToInt(version -> ((Number) version.get("versionNumber")).intValue()).max().orElse(0) + 1;
        var current = version(id, number, text);
        history.addFirst(current);
        var value = new LinkedHashMap<>(requirement.get());
        value.put("title", title);
        value.put("currentVersion", current);
        value.put("currentVersionId", id);
        versions.set(List.copyOf(history));
        requirement.set(value);
    }

    void open(String locale) {
        page.open("requirement-detail", "", "/projects/1/requirements/2", locale,
                "localStorage.clear();sessionStorage.clear();");
        wait.until(ExpectedConditions.textToBe(By.id("requirementHeading"), "Pump control"));
        idle();
        wait.until(driver -> !driver.findElements(By.id("reformulationSnapshot")).isEmpty());
        assertThat(page.driver().findElement(By.id("detailError")).isDisplayed()).isFalse();
    }

    void idle() {
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("detailBusy")));
    }

    void click(String id) {
        click(By.id(id));
    }

    void click(By selector) {
        new WebDriverWait(page.driver(), Duration.ofSeconds(20))
                .ignoring(ElementClickInterceptedException.class)
                .ignoring(StaleElementReferenceException.class)
                .withMessage("Native click on " + selector)
                .until(driver -> {
                    var element = driver.findElement(selector);
                    if (!element.isDisplayed() || !element.isEnabled()) return false;
                    javascript().executeScript(
                            "arguments[0].scrollIntoView({behavior:'instant',block:'center',inline:'nearest'});", element);
                    if (!Boolean.TRUE.equals(javascript().executeScript("""
                            const e=arguments[0], r=e.getBoundingClientRect();
                            const hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);
                            return hit && (hit===e || e.contains(hit));
                            """, element))) return false;
                    element.click();
                    return true;
                });
        var control = page.driver().findElement(selector);
        if ("tab".equals(control.getDomAttribute("data-bs-toggle"))) {
            By pane = By.cssSelector(control.getDomAttribute("data-bs-target"));
            wait.until(driver -> {
                var target = driver.findElement(pane);
                return target.isDisplayed() && List.of(target.getDomAttribute("class").split("\\s+"))
                        .containsAll(List.of("show", "active"));
            });
        }
    }

    void openVersionModal() {
        click("newVersionButton");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("newVersionModal")));
        wait.until(driver -> "true".equals(
                driver.findElement(By.id("newVersionModal")).getDomAttribute("aria-modal")));
    }

    void editVersion() {
        var summary = page.driver().findElement(By.cssSelector("#newVersionForm details > summary"));
        if (!"true".equals(page.driver().findElement(By.cssSelector("#newVersionForm details"))
                .getDomProperty("open"))) summary.sendKeys(Keys.ENTER);
        for (int index = 0; index < VERSION_FIELDS.size(); index++) {
            var field = page.driver().findElement(By.id(VERSION_FIELDS.get(index)));
            field.clear();
            field.sendKeys(DRAFT.get(index));
        }
        var text = page.driver().findElement(By.id("versionText"));
        text.sendKeys(Keys.chord(Keys.CONTROL, Keys.HOME));
        text.sendKeys(Keys.ARROW_RIGHT, Keys.ARROW_RIGHT, Keys.ARROW_RIGHT);
        for (int index = 0; index < 5; index++) text.sendKeys(Keys.chord(Keys.SHIFT, Keys.ARROW_RIGHT));
    }

    List<String> versionValues() {
        return VERSION_FIELDS.stream()
                .map(id -> page.driver().findElement(By.id(id)).getDomProperty("value")).toList();
    }

    void assertDraft() { assertThat(versionValues()).containsExactlyElementsOf(DRAFT); }

    void submitVersion() {
        click(By.cssSelector("#newVersionForm button[type='submit']"));
    }

    void closeVersionModal() {
        click(By.cssSelector("#newVersionModal .btn-close"));
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("newVersionModal")));
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector(".modal-backdrop")));
    }

    JavascriptExecutor javascript() { return page.driver(); }
    WebElement element(String id) { return page.driver().findElement(By.id(id)); }
}

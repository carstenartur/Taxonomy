package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.taxonomy.PortfolioClientTestPage.json;
import static org.assertj.core.api.Assertions.assertThat;

/** Matrix review contracts against production templates, real HTTP and native browser controls. */
@Tag("browser")
class PortfolioMatrixReviewIT {
    private static final String API = "/api/projects/1";
    private static final List<String> COLUMNS = List.of("REQ-ZERO", "REQ-POSITIVE", "REQ-MISSING", "REQ-UNKNOWN");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static PortfolioClientTestPage page;
    private static WebDriverWait wait;

    @BeforeAll static void openBrowser(@TempDir Path downloads) throws Exception {
        page = new PortfolioClientTestPage(downloads);
        page.driver().manage().window().setSize(new Dimension(1440, 1000));
        wait = new WebDriverWait(page.driver(), Duration.ofSeconds(20));
    }

    @AfterAll static void closeBrowser() { if (page != null) page.close(); }

    private Map<String, Object> portfolio(Object unknown) {
        var values = new LinkedHashMap<String, Object>();
        values.put("REQ-ZERO", 0);
        values.put("REQ-POSITIVE", 65);
        values.put("REQ-UNKNOWN", unknown);
        var data = new LinkedHashMap<String, Object>();
        data.put("requirements", List.of(
                Map.of("id", 2, "requirementKey", "REQ-ZERO"),
                Map.of("id", 3, "requirementKey", "REQ-POSITIVE"),
                Map.of("id", 4, "requirementKey", "REQ-MISSING"),
                Map.of("id", 5, "requirementKey", "REQ-UNKNOWN")));
        var solution = new LinkedHashMap<String, Object>();
        solution.put("solution", Map.of("solutionKey", "SOL-1"));
        solution.put("requirements", List.of(Map.of("requirementId", 2, "coveragePercent", 0,
                "reviewStatus", "CONFIRMED", "evidence", "Explicitly reviewed as no coverage")));
        solution.put("productCandidates", List.of(Map.of(
                "product", Map.of("productKey", "PROD-1", "sourceReference", "Reviewed datasheet"),
                "coveragePercent", 0, "reviewStatus", "CONFIRMED", "selectionStatus", "SHORTLISTED")));
        data.put("solutions", List.of(solution));
        data.put("taxonomyNodes", List.of(Map.of("nodeCode", "TAX-1", "title", "Access control")));
        data.put("requirementSolutionMatrix", matrix(List.of("SOL-1"), COLUMNS, Map.of("SOL-1", values)));
        data.put("requirementTaxonomyMatrix", matrix(List.of("REQ-ZERO"), List.of("TAX-1"),
                Map.of("REQ-ZERO", Map.of("TAX-1", 0))));
        data.put("solutionProductMatrix", matrix(List.of("SOL-1"), List.of("PROD-1"),
                Map.of("SOL-1", Map.of("PROD-1", 0))));
        return data;
    }

    private Map<String, Object> matrix(List<String> rows, List<String> columns, Map<String, ?> values) {
        return Map.of("rows", rows, "columns", columns, "values", values);
    }

    private void open(String locale, Map<String, Object> data, String bootstrap) {
        page.clearRoutes();
        page.clearRequests();
        page.route("GET", API, request -> json(200,
                Map.of("id", 1, "projectKey", "PROJECT", "title", "Control system")));
        page.route("GET", API + "/portfolio", request -> json(200, data));
        page.open("portfolio-matrices", "", "/projects/1/matrices", locale,
                "localStorage.clear();sessionStorage.clear();" + bootstrap);
        wait.until(ExpectedConditions.textToBe(By.id("matrixProject"), "PROJECT — Control system"));
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("matrixBusy")));
        assertThat(page.driver().findElement(By.id("matrixError")).isDisplayed()).isFalse();
    }

    private void openSolutions() {
        open("en", portfolio(null), "");
        selectTab("solution");
    }

    private void click(WebElement element) {
        page.driver().executeScript("arguments[0].scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});", element);
        wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("""
                const e=arguments[0], r=e.getBoundingClientRect();
                const x=r.left+r.width/2, y=r.top+r.height/2;
                const hit=document.elementFromPoint(x,y);
                return x>=0 && y>=0 && x<innerWidth && y<innerHeight && hit && (hit===e || e.contains(hit));
                """, element)));
        element.click();
    }

    private void click(String id) { click(page.driver().findElement(By.id(id))); }

    private void selectTab(String type) {
        click(type + "MatrixTab");
        wait.until(driver -> {
            var pane = page.driver().findElement(By.id(type + "MatrixPane"));
            return pane.isDisplayed() && List.of(pane.getDomAttribute("class").split("\\s+"))
                    .containsAll(List.of("show", "active"));
        });
    }

    private List<WebElement> buttons(String target, String surface) {
        return page.driver().findElements(By.cssSelector("#" + target + " " + surface + " .matrix-drilldown"));
    }

    private WebElement cell(String target, String surface, String column) {
        return page.driver().findElement(By.cssSelector(
                "#" + target + " " + surface + " .matrix-drilldown[data-column='" + column + "']"));
    }

    private String detail(String target, String column) {
        click(cell(target, "table", column));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("cellDetail")));
        wait.until(driver -> {
            var detail = page.driver().findElement(By.id("cellDetail"));
            var classes = List.of(detail.getDomAttribute("class").split("\\s+"));
            return "true".equals(detail.getDomAttribute("aria-modal"))
                    && classes.contains("show") && !classes.contains("showing");
        });
        return page.driver().findElement(By.id("cellDetailBody")).getText();
    }

    private void closeDetail() {
        new Actions(page.driver()).sendKeys(Keys.ESCAPE).perform();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("cellDetail")));
        wait.until(ExpectedConditions.numberOfElementsToBe(By.cssSelector(".offcanvas-backdrop"), 0));
    }

    private void columnsAre(String... columns) {
        try {
            wait.until(driver -> buttons("solutionMatrix", "table").stream()
                    .map(button -> button.getDomAttribute("data-column")).toList().equals(List.of(columns)));
        } catch (TimeoutException failure) {
            captureFilterDiagnostics(failure);
            throw failure;
        }
        assertThat(buttons("solutionMatrix", "table"))
                .extracting(button -> button.getDomAttribute("data-column")).containsExactly(columns);
    }

    private void captureFilterDiagnostics(Throwable failure) {
        try {
            Path directory = Path.of("target", "portfolio-matrix-diagnostics");
            Files.createDirectories(directory);
            Object snapshot = page.driver().executeScript("""
                    return {
                        url: location.href,
                        relation: document.getElementById('relationState').value,
                        minimum: document.getElementById('minimumValue').value,
                        search: document.getElementById('matrixSearch').value,
                        activeFilters: document.getElementById('activeFilters').innerText,
                        panes: Array.from(document.querySelectorAll('.tab-pane')).map(pane => ({
                            id: pane.id, classes: pane.className,
                            display: getComputedStyle(pane).display,
                            visibility: getComputedStyle(pane).visibility
                        })),
                        headers: Array.from(document.querySelectorAll('#solutionMatrix table th')).map(e => e.innerText),
                        buttons: Array.from(document.querySelectorAll('#solutionMatrix table .matrix-drilldown')).map(e => ({
                            column: e.dataset.column, row: e.dataset.row, value: e.dataset.value,
                            text: e.innerText, accessibleLabel: e.getAttribute('aria-label')
                        })),
                        events: window.__portfolioFilterEvents || []
                    };
                    """);
            Files.writeString(directory.resolve("filter-timeout.json"), JSON.writeValueAsString(snapshot));
            Files.write(directory.resolve("filter-timeout.png"), page.driver().getScreenshotAs(OutputType.BYTES));
        } catch (Exception diagnosticFailure) {
            failure.addSuppressed(diagnosticFailure);
        }
    }

    private void minimum(String value) {
        var input = page.driver().findElement(By.id("minimumValue"));
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"), value, Keys.TAB);
    }

    private void relationship(String value) {
        var select = page.driver().findElement(By.id("relationState"));
        var values = select.findElements(By.tagName("option")).stream()
                .map(option -> option.getDomAttribute("value")).toList();
        assertThat(values).contains(value);
        Object previousEvents = page.driver().executeScript("return window.__portfolioFilterEvents.length;");
        // ChromeDriver's option click can synthesize change without input. Real keyboard
        // selection follows the same trusted input path as an ordinary browser user.
        select.sendKeys(Keys.HOME);
        for (int index = 0; index < values.indexOf(value); index++) select.sendKeys(Keys.ARROW_DOWN);
        select.sendKeys(Keys.ENTER, Keys.TAB);
        assertThat(select.getDomProperty("value")).isEqualTo(value);
        assertThat(page.driver().executeScript("""
                return window.__portfolioFilterEvents.slice(arguments[0]).some(event =>
                    event.type === 'input' && event.trusted === true && event.relation === arguments[1]);
                """, previousEvents, value)).as("native selection emits a trusted input event").isEqualTo(true);
    }

    @ParameterizedTest @ValueSource(strings = {"en", "de"})
    void explicitZeroAbsentAndUnknownRemainDistinctInTableAndDetails(String locale) {
        open(locale, portfolio(null), "");
        selectTab("solution");
        var zero = cell("solutionMatrix", "table", "REQ-ZERO");
        var missing = cell("solutionMatrix", "table", "REQ-MISSING");
        var unknown = cell("solutionMatrix", "table", "REQ-UNKNOWN");
        assertThat(zero.getText()).isEqualTo("0%");
        assertThat(zero.getAccessibleName()).contains("0%");
        assertThat(missing.getText()).isNotEqualTo(zero.getText());
        assertThat(unknown.getText()).isNotEqualTo(zero.getText());
        assertThat(unknown.getAccessibleName()).isNotEqualTo(missing.getAccessibleName())
                .containsIgnoringCase(locale.equals("de") ? "unbekannt" : "unknown");
        assertThat(detail("solutionMatrix", "REQ-ZERO")).contains("0%", "Explicitly reviewed as no coverage")
                .doesNotContain("No stored relationship exists", "keine Beziehung gespeichert");
        closeDetail();
        assertThat(detail("solutionMatrix", "REQ-MISSING"))
                .contains(locale.equals("de") ? "keine Beziehung gespeichert" : "No stored relationship exists");
        closeDetail();
        assertThat(detail("solutionMatrix", "REQ-UNKNOWN"))
                .containsIgnoringCase(locale.equals("de") ? "unbekannt" : "unknown")
                .doesNotContain("No stored relationship exists", "keine Beziehung gespeichert", "0%");
        closeDetail();
    }

    @Test void aZeroTaxonomyMappingKeepsItsSnapshotProvenance() {
        open("en", portfolio(null), "");
        assertThat(detail("taxonomyMatrix", "TAX-1")).contains("0%", "From current snapshot", "Access control")
                .doesNotContain("No stored relationship exists");
        closeDetail();
    }

    @Test void aZeroProductCandidateKeepsItsSourceAndReviewStatus() {
        open("en", portfolio(null), "");
        selectTab("product");
        assertThat(detail("productMatrix", "PROD-1")).contains("0%", "Reviewed datasheet", "Confirmed", "Shortlisted")
                .doesNotContain("No stored relationship exists");
        closeDetail();
    }

    @Test void relatedIncludesStoredZeroAndUnknownButEmptyOnlyIncludesAbsentCells() {
        open("en", portfolio(null), """
                window.__portfolioFilterEvents = [];
                ['input', 'change', 'mousedown', 'mouseup', 'click', 'keydown', 'keyup'].forEach(type => {
                    document.addEventListener(type, event => {
                        const target = event.target;
                        if (target.id !== 'relationState' && target.id !== 'minimumValue'
                            && target.parentElement?.id !== 'relationState') return;
                        window.__portfolioFilterEvents.push({
                            type, trusted: event.isTrusted, target: target.tagName,
                            key: event.key || null,
                            relation: document.getElementById('relationState').value,
                            minimum: document.getElementById('minimumValue').value
                        });
                    }, {capture: true, passive: true});
                });
                """);
        selectTab("solution");
        relationship("related");
        columnsAre("REQ-ZERO", "REQ-POSITIVE", "REQ-UNKNOWN");
        relationship("empty");
        columnsAre("REQ-MISSING");
        relationship("all");
        minimum("1");
        columnsAre("REQ-POSITIVE");
    }

    @Test void invalidCoverageNeverBecomesAnExplicitNumericZero() {
        record Invalid(String label, Object value, String decodedExpression) { }
        var invalid = List.of(new Invalid("null", null, null), new Invalid("undefined", null, "undefined"),
                new Invalid("empty string", "", null), new Invalid("numeric string", "0", null),
                new Invalid("unknown string", "unknown", null), new Invalid("false", false, null),
                new Invalid("NaN", null, "NaN"), new Invalid("Infinity", null, "Infinity"),
                new Invalid("negative", -1, null), new Invalid("over 100", 101, null));
        for (var value : invalid) {
            String bootstrap = "";
            if (value.decodedExpression() != null) {
                // These three values cannot occur in JSON. Inject only that malformed value
                // after the genuine HTTP response is decoded, at the public Response boundary.
                // Production modules, DOM, event handlers and private state stay unchanged.
                bootstrap = """
                        const nativeJson = Response.prototype.json;
                        Response.prototype.json = async function () {
                            const data = await nativeJson.call(this);
                            if (new URL(this.url, location.href).pathname === '/api/projects/1/portfolio') {
                                data.requirementSolutionMatrix.values['SOL-1']['REQ-UNKNOWN'] = %s;
                            }
                            return data;
                        };
                        """.formatted(value.decodedExpression());
            }
            open("en", portfolio(value.value()), bootstrap);
            selectTab("solution");
            var unknown = cell("solutionMatrix", "table", "REQ-UNKNOWN");
            assertThat(unknown.isDisplayed()).as(value.label() + " remains reviewable").isTrue();
            assertThat(unknown.getAccessibleName()).as(value.label()).containsIgnoringCase("unknown").doesNotContain("0%");
        }
    }

    @Test void alternativeListKeepsZeroMissingAndUnknownSemantics() {
        openSolutions();
        click(page.driver().findElement(By.cssSelector("#solutionMatrix details summary")));
        assertThat(cell("solutionMatrix", "details", "REQ-ZERO").getText()).contains("0%");
        assertThat(cell("solutionMatrix", "details", "REQ-UNKNOWN").getText()).containsIgnoringCase("unknown");
        assertThat(cell("solutionMatrix", "details", "REQ-MISSING").getText()).contains("Empty").doesNotContain("0%");
    }

    @Test void filteredOutIntersectionsCannotMasqueradeAsAbsentRelationships() {
        var data = portfolio(null);
        data.put("requirementSolutionMatrix", matrix(List.of("SOL-1", "SOL-2"),
                List.of("REQ-ZERO", "REQ-POSITIVE"), Map.of(
                        "SOL-1", Map.of("REQ-ZERO", 0, "REQ-POSITIVE", 70),
                        "SOL-2", Map.of("REQ-ZERO", 80, "REQ-POSITIVE", 10))));
        open("en", data, "");
        selectTab("solution");
        minimum("50");
        wait.until(driver -> buttons("solutionMatrix", "table").size() == 2);
        assertThat(buttons("solutionMatrix", "table")).hasSize(2).allSatisfy(button ->
                assertThat(Integer.parseInt(button.getDomAttribute("data-value"))).isGreaterThanOrEqualTo(50));
        assertThat(buttons("solutionMatrix", "details")).hasSize(2);
        assertThat(page.driver().findElements(By.cssSelector("#solutionMatrix table td[aria-label$='Excluded by filters']")))
                .hasSize(2).allSatisfy(cell -> assertThat(cell.findElements(By.tagName("button"))).isEmpty());
    }

    private String download(String format) throws Exception {
        String filename = "PROJECT-solution-matrix." + format;
        click(format.equals("json") ? "exportJson" : "exportCsv");
        wait.until(driver -> page.browser().downloadedFiles().contains(filename));
        return Files.readString(page.browser().download(filename));
    }

    @Test void jsonExportKeepsExplicitZeroUnknownNullAndAbsentKeys() throws Exception {
        openSolutions();
        var exported = JSON.readTree(download("json"));
        var values = exported.path("values").path("SOL-1");
        assertThat(exported.path("matrixType").asString()).isEqualTo("solution");
        assertThat(values.path("REQ-ZERO").isNumber()).isTrue();
        assertThat(values.path("REQ-ZERO").asInt()).isZero();
        assertThat(values.has("REQ-UNKNOWN")).isTrue();
        assertThat(values.path("REQ-UNKNOWN").isNull()).isTrue();
        assertThat(values.has("REQ-MISSING")).isFalse();
    }

    @Test void csvExportLeavesAbsentCellsBlankAndLabelsUnknownCoverage() throws Exception {
        openSolutions();
        assertThat(download("csv"))
                .isEqualTo("row,REQ-ZERO,REQ-POSITIVE,REQ-MISSING,REQ-UNKNOWN\nSOL-1,0,65,,Unknown");
    }
}

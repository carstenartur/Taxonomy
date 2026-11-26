package com.taxonomy;

import com.taxonomy.catalog.definition.BundledCatalogue;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

import static com.taxonomy.PortfolioClientTestPage.json;
import static com.taxonomy.PortfolioRequirementFixture.REQUIREMENT;
import static com.taxonomy.PortfolioRequirementFixture.ROOT;
import static org.assertj.core.api.Assertions.assertThat;

/** Real layout and hit-testing; VM contracts separately cover retained commands and evidence. */
@Tag("browser")
@Tag("ci-app-shard-browser")
class PortfolioRemediationLayoutIT {
    private static PortfolioClientTestPage page;
    private static HasCdp cdp;
    private static List<TaxonomyNode> catalogueNodes;

    @BeforeAll static void openBrowser(@TempDir Path downloads) throws Exception {
        page = new PortfolioClientTestPage(downloads);
        page.driver().manage().window().setSize(new Dimension(1440, 1000));
        cdp = (HasCdp) new Augmenter().augment(page.driver());
        try (var input = new ClassPathResource(BundledCatalogue.BILINGUAL_PATH).getInputStream();
             var workbook = new XSSFWorkbook(input)) {
            catalogueNodes = TaxonomyService.readWorkbookNodes(workbook).nodes().values().stream()
                    .sorted(Comparator.comparing(TaxonomyNode::getCode)).limit(320).toList();
        }
        assertThat(catalogueNodes).hasSize(320);
    }

    @AfterEach void restoreViewport() {
        if (cdp != null) cdp.executeCdpCommand("Emulation.clearDeviceMetricsOverride", Map.of());
    }

    @AfterAll static void closeBrowser() { if (page != null) page.close(); }

    private void viewport(int width) {
        cdp.executeCdpCommand("Emulation.setDeviceMetricsOverride", Map.of(
                "width", width, "height", 844, "deviceScaleFactor", 1, "mobile", width < 600));
    }

    private void screenshot(String name) throws Exception {
        Path output = Path.of("target/scenario-acceptance/portfolio-remediation");
        Files.createDirectories(output);
        Files.write(output.resolve(name + ".png"), page.driver().getScreenshotAs(OutputType.BYTES));
    }

    @ParameterizedTest
    @CsvSource({"de,390", "en,390", "de,1440", "en,1440"})
    void adoptionCloseRemainsVisibleAndClickableAfterScrollingLongEvidence(String locale, int width) throws Exception {
        viewport(width);
        var fixture = new PortfolioRequirementFixture(page);
        String offer = REQUIREMENT + "/reformulations/layout-offer";
        String longText = (locale.equals("de")
                ? "Aufgezeichnete Anforderung mit Herkunft und offenem Entscheidungsbedarf.\n"
                : "Recorded requirement with provenance and an open decision.\n").repeat(60);
        var preview = Map.of("hash", "a".repeat(64), "content", Map.of(
                "id", "layout-preview", "proposalId", "layout-offer", "sourceVersionId", 11,
                "originalText", longText, "currentRequirement", Map.of("currentVersionId", 12,
                        "currentVersion", Map.of("text", longText)),
                "revision", Map.of("number", 1, "questions", List.of()),
                "finalText", longText, "warnings", List.of("NOT_SYNTHESIZED"), "blockingReasons", List.of()));
        page.route("POST", offer + "/adoption-previews", request -> json(201, preview));
        page.route("GET", offer + "/adoptions", request -> json(200, List.of()));
        fixture.open(locale);
        page.driver().executeScript("""
                window.TaxonomyReformulationAdoption.open({projectId:1,requirementId:2,
                    proposalId:'layout-offer',revision:1,language:arguments[0],isCurrent:()=>true});
                """, locale);
        fixture.wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-adoption-action='confirm']")));
        var close = page.driver().findElement(By.cssSelector("[data-adoption-action='close']"));
        assertThat(close.getAccessibleName()).isEqualTo(locale.equals("de") ? "Schließen" : "Close");
        assertThat(page.driver().findElement(By.cssSelector(".reformulation-adoption-body")).getText())
                .contains(locale.equals("de") ? "Die Quellabdeckung wurde noch nicht ausgewertet."
                        : "Source coverage has not been evaluated.");
        assertAdoptionGeometry();
        screenshot("adoption-" + locale + "-" + width + "-top");
        Number before = (Number) page.driver().executeScript("return arguments[0].getBoundingClientRect().top;", close);
        page.driver().executeScript("""
                const body=document.querySelector('.reformulation-adoption-body');body.scrollTop=body.scrollHeight;
                """);
        assertThat((Number) page.driver().executeScript("return document.querySelector('.reformulation-adoption-body').scrollTop;"))
                .satisfies(value -> assertThat(value.doubleValue()).isGreaterThan(0));
        assertAdoptionGeometry();
        Number after = (Number) page.driver().executeScript("return arguments[0].getBoundingClientRect().top;", close);
        assertThat(after.doubleValue()).isEqualTo(before.doubleValue());
        screenshot("adoption-" + locale + "-" + width + "-scrolled");
        // A native click, without scrollIntoView, proves the persistent header remains reachable.
        close.click();
        fixture.wait.until(ExpectedConditions.numberOfElementsToBe(By.cssSelector(".reformulation-adoption-dialog"), 0));
        assertThat(page.requests().stream().filter(request -> request.method().equals("POST"))).singleElement()
                .satisfies(request -> assertThat(request.path()).isEqualTo(offer + "/adoption-previews"));
    }

    private void assertAdoptionGeometry() {
        assertThat(page.driver().executeScript("""
                const dialog=document.querySelector('.reformulation-adoption-dialog');
                const body=dialog.querySelector('.reformulation-adoption-body');
                const close=dialog.querySelector('[data-adoption-action="close"]');
                const d=dialog.getBoundingClientRect(),c=close.getBoundingClientRect();
                const hit=document.elementFromPoint(c.left+c.width/2,c.top+c.height/2);
                return d.left>=0 && d.right<=innerWidth && d.top>=0 && d.bottom<=innerHeight
                    && c.left>=d.left && c.right<=d.right && c.top>=d.top && c.bottom<=d.bottom
                    && c.width>0 && c.height>0 && body.scrollHeight>body.clientHeight
                    && (hit===close || close.contains(hit));
                """)).as("Dialog bounds, scrollable body and unobscured Close control").isEqualTo(true);
    }

    @ParameterizedTest
    @CsvSource({"de,390", "en,390", "de,1440", "en,1440"})
    void inventoryKeepsLargeNodeSelectionBoundedAndOptionalSetupCollapsed(String locale, int width) throws Exception {
        viewport(width);
        var fixture = new PortfolioRequirementFixture(page);
        page.route("GET", ROOT + "/snapshots/layout-snapshot/inventory-comparisons", request -> json(200, List.of()));
        fixture.open(locale);
        fixture.click("solutions-tab");
        assertThat(page.driver().executeScript("""
                const tabs=document.getElementById('detailTabs'),selected=document.getElementById('solutions-tab');
                const r=selected.getBoundingClientRect(),t=tabs.getBoundingClientRect();
                return getComputedStyle(selected).whiteSpace==='nowrap' && r.left>=t.left && r.right<=t.right
                    && selected.getAttribute('aria-selected')==='true';
                """)).as("The last tab remains readable and can be reached by a native click").isEqualTo(true);
        var mappings = catalogueNodes.stream().map(node -> Map.of("nodeCode", node.getCode(),
                "nodeTitle", locale.equals("de") && node.getNameDe() != null ? node.getNameDe() : node.getNameEn())).toList();
        page.driver().executeScript("""
                const target=document.createElement('div');target.id='inventory-layout';
                document.getElementById('requirementMain').append(target);
                // The saved-snapshot response is authored; its identifiers and titles come from the real bundled catalogue.
                window.TaxonomyInventoryComparison.mount(target,{projectId:1,solutions:[],snapshot:{
                    summary:{id:'layout-snapshot'},elementMappings:arguments[0]}});
                target.scrollIntoView({block:'start'});
                """, mappings);
        var nodes = page.driver().findElement(By.cssSelector("[data-inventory-nodes]"));
        var optional = page.driver().findElement(By.cssSelector("[data-inventory-options]"));
        var reopen = page.driver().findElement(By.cssSelector("[data-inventory-reopen]"));
        assertThat(nodes.findElements(By.cssSelector("input[type='checkbox']"))).hasSize(320)
                .allSatisfy(input -> assertThat(input.isSelected()).isFalse());
        assertThat(optional.getDomProperty("open")).isEqualTo("false");
        assertThat(reopen.isEnabled()).isFalse();
        assertThat(reopen.getDomAttribute("title")).isEqualTo(locale.equals("de")
                ? "Zuerst einen gespeicherten Abgleich auswählen." : "Select a retained comparison first.");
        assertThat(page.driver().executeScript("""
                const panel=document.querySelector('[data-inventory-comparison]'),nodes=arguments[0];
                const r=panel.getBoundingClientRect(),rem=parseFloat(getComputedStyle(document.documentElement).fontSize);
                return nodes.clientHeight<=20*rem && nodes.scrollHeight>nodes.clientHeight
                    && ['auto','scroll'].includes(getComputedStyle(nodes).overflowY)
                    && r.left>=0 && r.right<=innerWidth && document.documentElement.scrollWidth<=innerWidth;
                """, nodes)).as("Node scrolling stays within the viewport and the 20rem selection area").isEqualTo(true);
        screenshot("inventory-" + locale + "-" + width);
        fixture.click(By.cssSelector("[data-inventory-options] > summary"));
        assertThat(optional.getDomProperty("open")).isEqualTo("true");
        assertThat(optional.findElements(By.cssSelector("input"))).allSatisfy(input -> assertThat(input.isDisplayed()).isTrue());
        assertThat(page.requests()).noneMatch(request -> request.method().equals("POST") || request.method().equals("PATCH"));
    }
}

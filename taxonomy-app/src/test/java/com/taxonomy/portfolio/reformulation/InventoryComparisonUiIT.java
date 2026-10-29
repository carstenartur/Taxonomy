package com.taxonomy.portfolio.reformulation;

import com.taxonomy.catalog.api.TaxonomyNodeLookup;
import com.taxonomy.dto.*;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.testsupport.BrowserSession;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.*;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real saved catalogue root, persisted evidence, authenticated browser, CSRF and retained reopen. */
@Tag("browser") @Tag("ci-app-shard-browser")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"taxonomy.security.require-password-change=false","server.servlet.context-path=/taxonomy"})
@AutoConfigureMockMvc @WithMockUser(username="architect",roles="ARCHITECT")
class InventoryComparisonUiIT extends ReformulationWorkflowFixture {
    private static final String BASE="/taxonomy";
    private static final String PASSWORD="Inventory-"+UUID.randomUUID();
    @DynamicPropertySource static void credentials(DynamicPropertyRegistry registry) {registry.add("taxonomy.admin-password",()->PASSWORD);}
    @LocalServerPort int port;
    @Autowired ProductCatalogService products;
    @Autowired SolutionPortfolioService solutionService;
    @Autowired com.taxonomy.portfolio.inventory.InventoryComparisonService comparisons;
    @Autowired TaxonomyNodeLookup nodes;
    @Autowired PortfolioFingerprintService fingerprints;
    @Override String snapshot(RequirementView req) {
        var official=nodes.findByCode("BP").orElseThrow();
        var node=new TaxonomyNodeDto();node.setCode(official.getCode());node.setNameEn(official.getNameEn());node.setDescriptionEn(official.getDescriptionEn());
        var result=new AnalysisResult(Map.of(official.getCode(),60),List.of(node));result.setStatus("SUCCESS");
        var job=analyses.createOrReuseJob(project.id(),List.of(req.id()),null,25,UUID.randomUUID().toString(),"architect",context);
        String id=UUID.randomUUID().toString();
        analyses.persistSnapshot(job.items().getFirst().id(),job.id(),project.id(),PortfolioScope.key("architect",context),id,"session-"+id,result,null,null,null,null,null,"fixture-prompt",fingerprints.taxonomyFingerprint(),"architect",context,1);return id;
    }
    @Test void curatedUnknownAndHardContradictionAreReviewedAndReopenedWithoutSourceRefresh() throws Exception {
        products.createProduct(new CreateProductRequest("CATALOGUE-"+UUID.randomUUID(),"Fixture manufacturer",null,"<img src=x onerror=alert(1)>",null,ProductStatus.ACTIVE,LocalDate.parse("2029-01-01"),null,OperatingModel.UNSPECIFIED,null,null,null,null,null,null,"Documented support end; no organizational availability claim",Instant.parse("2026-01-01T00:00:00Z")),"architect",context);
        Path evidence=Path.of("target/inventory-browser");Files.createDirectories(evidence);
        try(var session=BrowserSession.openWithNetworkLogging(port,evidence)) {
            var driver=session.driver();var wait=new WebDriverWait(driver,Duration.ofSeconds(30));driver.manage().window().setSize(new Dimension(1440,1000));
            driver.get(session.origin()+BASE+"/login");driver.findElement(By.name("username")).sendKeys("admin");driver.findElement(By.name("password")).sendKeys(PASSWORD);driver.findElement(By.cssSelector("form")).submit();wait.until(d->!d.getCurrentUrl().contains("/login"));
            driver.get(session.origin()+BASE+"/projects/"+project.id()+"/requirements/"+requirement.id()+"?snapshot="+snapshot+"&lang=en");
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#inventoryComparison [data-inventory-comparison]")));
            var panel=driver.findElement(By.id("inventoryComparison"));
            observeRequests(driver);
            panel.findElement(By.cssSelector("[data-inventory-organization]")).sendKeys("north");
            ((JavascriptExecutor)driver).executeScript("arguments[0].value='2030-01-01'",panel.findElement(By.cssSelector("[data-inventory-start]")));
            new Select(panel.findElement(By.xpath(".//label[contains(.,'Time question')]/select"))).selectByValue("START_WINDOW");
            panel.findElement(By.xpath(".//label[contains(.,'Target environment')]/input")).sendKeys("production");
            ((JavascriptExecutor)driver).executeScript("arguments[0].value='2031-01-01'",panel.findElement(By.xpath(".//label[contains(.,'End (exclusive)')]/input")));
            click(driver,"//div[@id='inventoryComparison']//button[normalize-space()='Start comparison']");
            wait.until(d->d.findElement(By.cssSelector("[data-inventory-comparison] [role=status]")).getText().contains("Select at least one"));
            assertThat(requests(driver)).noneMatch(r->!r.get("method").equals("GET"));
            click(driver,"//div[@id='inventoryComparison']//input[@type='checkbox' and @value='BP']");
            click(driver,"//div[@id='inventoryComparison']//button[normalize-space()='Start comparison']");
            wait.until(d->d.findElement(By.cssSelector("[data-inventory-result]")).getText().contains("CONTRADICTED"));
            assertThat(driver.findElement(By.cssSelector("[data-inventory-result]")).getText()).contains("UNKNOWN","AVAILABILITY","SUPPORT","UNEXAMINED");
            assertThat(driver.findElement(By.cssSelector("[data-inventory-select]")).isEnabled()).isFalse();
            assertThat(driver.findElement(By.cssSelector("[data-inventory-result] strong")).getText()).isEqualTo("<img src=x onerror=alert(1)>");
            assertThat(driver.findElements(By.cssSelector("[data-inventory-result] img"))).isEmpty();
            var creation=requests(driver).stream().filter(r->r.get("method").equals("POST")).findFirst().orElseThrow();
            assertThat(creation.get("url")).startsWith(BASE+"/api/projects/");
            var request=json.readTree(creation.get("body"));
            assertThat(request.path("selectedNodes").toString()).isEqualTo("[\"BP\"]");
            assertThat(request.path("question").path("mode").asText()).isEqualTo("START_WINDOW");
            assertThat(request.path("question").path("start").asText()).isEqualTo("2030-01-01");
            assertThat(request.path("question").path("endExclusive").asText()).isEqualTo("2031-01-01");
            assertThat(request.path("question").path("environment").asText()).isEqualTo("production");
            click(driver,"//div[@id='inventoryComparison']//summary[normalize-space()='Save reasoned review']");
            driver.findElement(By.cssSelector("[data-inventory-result] textarea")).sendKeys("Support ended before intended use; ask the product owner for an alternative.");
            click(driver,"//div[@id='inventoryComparison']//button[normalize-space()='Save reasoned review']");
            wait.until(d->d.findElement(By.cssSelector("[data-inventory-comparison] [role=status]")).getText().contains("Saved"));
            String id=jdbc.queryForObject("select id from inventory_comparison where scope_key=?",String.class,PortfolioScope.key("architect",context));
            assertThat(jdbc.queryForObject("select count(*) from inventory_review where comparison_id=?",Integer.class,id)).isEqualTo(1);
            driver.navigate().refresh();wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-inventory-comparison]")));
            wait.until(d->!d.findElements(By.cssSelector("#inventoryComparison option[value='"+id+"']")).isEmpty());
            observeRequests(driver);
            var retained=driver.findElement(By.xpath("//label[contains(.,'Retained comparisons')]/select"));
            ((JavascriptExecutor)driver).executeScript("arguments[0].scrollIntoView({behavior:'instant',block:'center'})",retained);
            wait.until(ExpectedConditions.visibilityOf(retained));new Select(retained).selectByValue(id);
            click(driver,"//div[@id='inventoryComparison']//button[normalize-space()='Open retained comparison']");
            wait.until(d->d.findElement(By.cssSelector("[data-inventory-result]")).getText().contains("CONTRADICTED"));
            click(driver,"//div[@id='inventoryComparison']//summary[normalize-space()='Retained reviews']");
            wait.until(d->d.findElement(By.cssSelector("[data-inventory-result]")).getText().contains("Support ended before intended use"));
            assertThat(jdbc.queryForObject("select count(*) from inventory_comparison where scope_key=?",Integer.class,PortfolioScope.key("architect",context))).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from inventory_observation where scope_key=?",Integer.class,PortfolioScope.key("architect",context))).isZero();
            assertThat(requests(driver)).isNotEmpty().allMatch(r->r.get("method").equals("GET") && r.get("url").startsWith(BASE+"/api/"));
            assertThat(requests(driver)).anyMatch(r->r.get("url").equals(BASE+"/api/inventory-comparisons/"+id));
            Files.write(evidence.resolve("review-reopen.png"),driver.getScreenshotAs(OutputType.BYTES));
            // The same native controls submit a distinct continuous question, with explicit nodes and dates.
            click(driver,"//div[@id='inventoryComparison']//input[@type='checkbox' and @value='BP']");
            driver.findElement(By.cssSelector("[data-inventory-organization]")).sendKeys("north");
            new Select(driver.findElement(By.xpath("//div[@id='inventoryComparison']//label[contains(.,'Time question')]/select"))).selectByValue("CONTINUOUS_PERIOD");
            ((JavascriptExecutor)driver).executeScript("arguments[0].value='2030-01-01';arguments[1].value='2031-01-01'",driver.findElement(By.cssSelector("[data-inventory-start]")),driver.findElement(By.xpath("//div[@id='inventoryComparison']//label[contains(.,'End (exclusive)')]/input")));
            click(driver,"//div[@id='inventoryComparison']//button[normalize-space()='Start comparison']");
            wait.until(d->requests(d).stream().anyMatch(r->r.get("method").equals("POST")));
            var continuous=json.readTree(requests(driver).stream().filter(r->r.get("method").equals("POST")).findFirst().orElseThrow().get("body"));
            assertThat(continuous.path("question").path("mode").asText()).isEqualTo("CONTINUOUS_PERIOD");
            assertThat(continuous.path("selectedNodes").toString()).isEqualTo("[\"BP\"]");
            wait.until(d->jdbc.queryForObject("select count(*) from inventory_comparison where scope_key=?",Integer.class,PortfolioScope.key("architect",context))==2);
        }
        mvc.perform(post("/api/inventory-comparisons/"+UUID.randomUUID()+"/reviews").with(user("reader").roles("USER")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/projects/"+project.id()+"/snapshots/"+snapshot+"/inventory-comparisons").with(csrf().useInvalidToken()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void reviewedSelectionPreservesExactSnapshotAndComparisonEvidenceAndReplaysOnce() throws Exception {
        var product=products.createProduct(new CreateProductRequest("REUSE-"+UUID.randomUUID(),"Manufacturer",null,"Existing product",null,ProductStatus.ACTIVE,null,null,OperatingModel.UNSPECIFIED,null,null,null,null,null,null,"Approved product evidence",Instant.parse("2026-01-01T00:00:00Z")),"architect",context);
        products.upsertTaxonomyCoverage(product.id(),new UpsertTaxonomyCoverageRequest("BP",100,"Confirmed capability mapping",ReviewStatus.CONFIRMED),"architect",context);
        var claim=new com.taxonomy.extension.api.inventory.InventoryComparisonContracts.Assertion("availability-"+UUID.randomUUID(),"product:"+product.id(),com.taxonomy.extension.api.inventory.InventoryComparisonContracts.Criterion.AVAILABILITY,null,true,new com.taxonomy.extension.api.inventory.InventoryComparisonContracts.Boundary("2029",com.taxonomy.extension.api.inventory.InventoryComparisonContracts.Precision.YEAR),new com.taxonomy.extension.api.inventory.InventoryComparisonContracts.Boundary("2032",com.taxonomy.extension.api.inventory.InventoryComparisonContracts.Precision.YEAR),"north",null,com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind.DECLARED,"Approved organizational plan",Instant.parse("2026-01-01T00:00:00Z"));
        comparisons.appendAssertion(product.id(),claim,"architect",context);
        var solution=solutionService.createSolution(new CreateSolutionRequest("S-"+UUID.randomUUID(),"Existing solution",null,null,null,null,null,"north",null,null,null,null,Map.of()),"architect",context);
        var decision=solutionService.addProjectSolution(project.id(),new AddProjectSolutionRequest(solution.id(),null,null,50,"Explicit existing solution"),"architect",context);
        var result=comparisons.compare(project.id(),snapshot,new com.taxonomy.portfolio.inventory.InventoryComparisonService.CreateComparisonRequest(List.of("BP"),new com.taxonomy.extension.api.inventory.InventoryComparisonContracts.Question(com.taxonomy.extension.api.inventory.InventoryComparisonContracts.TimeMode.EXACT_DATE,LocalDate.parse("2030-01-01"),null,"north",null),List.of()),"architect",context);
        var review=new com.taxonomy.extension.api.inventory.InventoryComparisonContracts.Review(UUID.randomUUID(),result.fingerprint(),"product:"+product.id(),"Explicit selection after reviewing remaining unknown criteria",decision.id(),true,80);
        String path="/api/inventory-comparisons/"+result.id()+"/reviews";
        mvc.perform(post(path).with(csrf()).contentType("application/json").content(json.writeValueAsString(review))).andExpect(status().isOk());
        mvc.perform(post(path).with(csrf()).contentType("application/json").content(json.writeValueAsString(review))).andExpect(status().isOk());
        var selected=products.listCandidates(project.id(),decision.id(),"architect",context).getFirst();
        assertThat(selected.selectionStatus()).isEqualTo(ProductSelectionStatus.SELECTED);assertThat(selected.coveragePercent()).isEqualTo(80);assertThat(selected.openEvidence()).contains(result.id().toString(),snapshot,result.fingerprint());
        var linked=solutionService.listProjectSolutions(project.id(),"architect",context).getFirst().requirements().getFirst();
        assertThat(linked.snapshotId()).isEqualTo(snapshot);assertThat(linked.evidence()).contains(result.id().toString());
        assertThat(jdbc.queryForObject("select count(*) from inventory_review where comparison_id=?",Integer.class,result.id().toString())).isEqualTo(1);
        var changed=new com.taxonomy.extension.api.inventory.InventoryComparisonContracts.Review(review.id(),result.fingerprint(),review.candidateId(),"Changed rationale",decision.id(),true,80);
        mvc.perform(post(path).with(csrf()).contentType("application/json").content(json.writeValueAsString(changed))).andExpect(status().isConflict());
        products.updateProduct(product.id(),json.readValue("{\"manufacturer\":\"Revised manufacturer\"}",UpdateProductRequest.class),"architect",context);
        var staleProduct=new com.taxonomy.extension.api.inventory.InventoryComparisonContracts.Review(UUID.randomUUID(),result.fingerprint(),review.candidateId(),"Rechecking an old product preview",null,false);
        mvc.perform(post(path).with(csrf()).contentType("application/json").content(json.writeValueAsString(staleProduct))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Catalogue product")));
        projects.addRequirementVersion(project.id(),requirement.id(),new CreateRequirementVersionRequest("Changed after comparison","Version revalidation",null),"architect",context);
        var staleDemand=new com.taxonomy.extension.api.inventory.InventoryComparisonContracts.Review(UUID.randomUUID(),result.fingerprint(),review.candidateId(),"Rechecking an old demand preview",null,false);
        mvc.perform(post(path).with(csrf()).contentType("application/json").content(json.writeValueAsString(staleDemand))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Current requirement version")));
        assertThat(jdbc.queryForObject("select count(*) from inventory_review where comparison_id=?",Integer.class,result.id().toString())).isEqualTo(1);
        select(new com.taxonomy.workspace.service.WorkspaceContext("architect",context.workspaceId(),context.currentBranch(),"foreign-repository"));
        try {mvc.perform(get("/api/inventory-comparisons/"+result.id())).andExpect(status().isNotFound());}finally {select(context);}
    }
    private final List<Map<String,String>> observedRequests=new ArrayList<>();
    private void observeRequests(WebDriver driver) {
        driver.manage().logs().get(org.openqa.selenium.logging.LogType.PERFORMANCE);observedRequests.clear();
    }
    private List<Map<String,String>> requests(WebDriver driver) {
        for(var entry:driver.manage().logs().get(org.openqa.selenium.logging.LogType.PERFORMANCE)) {
            var message=json.readTree(entry.getMessage()).path("message");
            if(!message.path("method").asText().equals("Network.requestWillBeSent"))continue;
            var request=message.path("params").path("request");
            String url=request.path("url").asText();
            if(!url.startsWith("http://") && !url.startsWith("https://"))continue;
            String path=java.net.URI.create(url).getPath();
            if(path!=null && path.contains("/api/"))observedRequests.add(Map.of("url",path,"method",request.path("method").asText(),"body",request.path("postData").asText("")));
        }
        return List.copyOf(observedRequests);
    }
    private void click(WebDriver driver,String xpath) {var element=driver.findElement(By.xpath(xpath));((JavascriptExecutor)driver).executeScript("arguments[0].scrollIntoView({behavior:'instant',block:'center'})",element);element.click();}
}

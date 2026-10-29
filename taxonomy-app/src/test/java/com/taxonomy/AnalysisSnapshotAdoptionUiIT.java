package com.taxonomy;

import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.composition.architecture.AnalysisSnapshotAdoptionService;
import com.taxonomy.dto.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.testsupport.BrowserSession;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.*;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Real historical central fixture, real security/HTTP/browser and real journal; no central write bypass. */
@Tag("browser")
@Tag("ui-acceptance")
@Tag("ci-app-shard-browser")
class AnalysisSnapshotAdoptionUiIT {
    private static final String PASSWORD = "Snapshot-Adoption-Browser-2026!";
    private static final String OLD_TEXT = "Retained command exchange evidence before the later requirement edit.";
    @TempDir Path directory;
    private RemoteWebDriver driver;
    private WebDriverWait wait;

    @Test void historicalCentralSnapshotCopiesAdoptsReplaysCheckpointsInvertsAndSurvivesApplicationRestart() throws Exception {
        Path evidence = Files.createDirectories(Path.of("target/snapshot-adoption-native"));
        String url = "jdbc:hsqldb:file:" + directory.resolve("application").toAbsolutePath() + ";shutdown=true";
        ConfigurableApplicationContext app = start(url);
        AnalysisSnapshotAdoptionService.Request originalRequest;
        RepositoryContext target;
        String viewId;
        try {
            var repositories = app.getBean(SystemRepositoryService.class);
            String repository = repositories.getPrimaryRepository().getRepositoryId();
            String defaultBranch = repositories.getPrimaryRepository().getDefaultBranch();
            String branch = "retained-snapshot-branch";
            app.getBean(com.taxonomy.workspace.storage.DslGitRepositoryFactory.class)
                    .resolveRepository(RepositoryContext.centralRead(repository,defaultBranch,"admin")).createBranch(branch,defaultBranch);
            var source = new WorkspaceContext("admin",null,branch,repository);
            var projects = app.getBean(ProjectPortfolioService.class);
            var snapshots = app.getBean(PortfolioAnalysisPersistenceService.class);
            // Central records are retained historical data. Seed through authoritative services;
            // normal public central POST remains forbidden by the production interceptor.
            var project = projects.createProject(new CreateProjectRequest("HISTORICAL-ADOPTION","Historical source fixture","Authored retained evidence",ProjectStatus.ACTIVE,null,null,null,null),"admin",source);
            var requirement = projects.createRequirement(project.id(),new CreateRequirementRequest("REQ-HISTORICAL","Historical requirement",OLD_TEXT,
                    RequirementStatus.APPROVED,90,Criticality.MISSION_CRITICAL,RequirementType.SECURITY,ReviewStatus.CONFIRMED,null,"Historical fixture",null),"admin",source);
            var taxonomy = app.getBean(TaxonomyService.class);
            var catalogue = taxonomy.getFingerprintTree();
            var nodes = catalogue.stream().flatMap(AnalysisSnapshotAdoptionUiIT::flatten)
                    .filter(node -> "CP".equals(node.getTaxonomyRoot()) && !node.isTaxonomyContainer()).limit(2).toList();
            assertThat(nodes).hasSize(2);
            var graph = new RequirementArchitectureView(); graph.setViewTitle("Historical central architecture");
            graph.setIncludedElements(nodes.stream().map(node -> {
                var element = new RequirementElementView(); element.setNodeCode(node.getCode());
                element.setTaxonomySheet(node.getTaxonomyRoot()); element.setTitle(node.getNameEn());
                element.setPresenceReason("Authored historical browser fixture from the real catalogue"); return element;
            }).toList());
            var edge = new RequirementRelationshipView(); edge.setSourceCode(nodes.get(0).getCode()); edge.setTargetCode(nodes.get(1).getCode()); edge.setRelationType("RELATED_TO");
            graph.setIncludedRelationships(List.of(edge));
            var analysis = new AnalysisResult(); analysis.setStatus("SUCCESS"); analysis.setArchitectureView(graph);
            var job = snapshots.createOrReuseJob(project.id(),List.of(requirement.id()),"GEMINI",20,"historical-browser-fixture","admin",source);
            String scope = PortfolioScope.key("admin",source);
            var item = snapshots.pendingItems(job.id(),project.id(),scope).getFirst();
            var snapshot = snapshots.persistSnapshot(item.getId(),job.id(),project.id(),scope,UUID.randomUUID().toString(),UUID.randomUUID().toString(),analysis,null,null,null,
                    "AUTHORED_FIXTURE","no-model-call","authored-fixture",TaxonomyDataFingerprint.sha256(catalogue),"admin",source,1);
            projects.addRequirementVersion(project.id(),requirement.id(),new CreateRequirementVersionRequest("Later edited requirement text","Retain the old source version",null),"admin",source);
            int port = app.getEnvironment().getRequiredProperty("local.server.port",Integer.class);
            try (var session = BrowserSession.open(port,evidence.resolve("downloads"))) {
                driver = session.driver(); wait = new WebDriverWait(driver,Duration.ofSeconds(45));
                driver.manage().window().setSize(new Dimension(1280,900));
                try {
                String origin = session.origin();
                driver.get(origin + "/login"); driver.findElement(By.name("username")).sendKeys("admin");
                driver.findElement(By.name("password")).sendKeys(PASSWORD,Keys.ENTER);
                wait.until(browser -> !browser.getCurrentUrl().contains("/login"));
                String pin = "&repositoryId=" + repository + "&workspaceId=&branch=" + branch;
                driver.get(origin + "/architecture/workbench?projectId=" + project.id() + "&snapshotId=" + snapshot.id() + pin + "&lang=en");
                wait.until(browser -> browser.findElement(By.id("workflow-adopt")).isDisplayed());
                assertThat(driver.findElement(By.id("requirementText")).getText()).contains(OLD_TEXT).doesNotContain("Later edited");
                click("workflow-adopt"); ready();
                assertThat(driver.findElement(By.id("adoptionPreview")).isEnabled()).isFalse();
                click("editorWorkspace"); wait.until(browser -> browser.findElement(By.id("adoptionPreview")).isEnabled());
                String workspace = new java.net.URI(driver.getCurrentUrl()).getQuery().lines().findFirst().orElseThrow();
                workspace = Arrays.stream(workspace.split("&")).filter(value -> value.startsWith("workspaceId=")).findFirst().orElseThrow().substring("workspaceId=".length());
                String targetBranch = (String)javascript().executeScript("return new URL(location.href).searchParams.get('branch');");
                assertThat(targetBranch).isNotEqualTo(branch);
                target = RepositoryContext.workspace(repository,workspace,targetBranch,"admin");
                String beforeCommit = app.getBean(ArchitectureEditorService.class).read(target,null).context().commit();
                click("adoptionPreview"); wait.until(browser -> browser.findElement(By.id("adoptionAccept")).isEnabled());
                assertThat(app.getBean(EditorJournal.class).read(target)).isNull();
                javascript().executeScript("""
                        const original = window.ArchitectureEditorApi.adoptSnapshot;
                        window.__adoptionAttempts = [];
                        window.ArchitectureEditorApi.adoptSnapshot = async request => {
                          window.__adoptionAttempts.push(JSON.parse(JSON.stringify(request)));
                          const result = await original(request);
                          window.__adoptionAccepted = result;
                          if (window.__adoptionAttempts.length === 1) throw Object.assign(new Error('Lost accepted response fixture'), {status:0});
                          return result;
                        };
                        """);
                click("adoptionAccept"); wait.until(browser -> browser.findElement(By.id("editorError")).getText().contains("Lost accepted response"));
                assertThat(app.getBean(EditorJournal.class).read(target).operations()).hasSize(1);
                click("adoptionAccept"); wait.until(browser -> browser.getCurrentUrl().contains("revision=1") && browser.getCurrentUrl().contains("viewId=analysis-")); ready();
                assertThat(javascript().executeScript("return JSON.stringify(window.__adoptionAttempts[0]) === JSON.stringify(window.__adoptionAttempts[1]);")).isEqualTo(true);
                assertThat(javascript().executeScript("return window.__adoptionAccepted.accepted.replayed;")).isEqualTo(true);
                viewId = (String) javascript().executeScript("return window.__adoptionAccepted.viewId;");
                originalRequest = JsonMapper.builder().build().readValue((String) javascript().executeScript("return JSON.stringify(window.__adoptionAttempts[0]);"),AnalysisSnapshotAdoptionService.Request.class);
                assertThat(driver.findElement(By.id("editorDsl")).getDomProperty("value")).contains(OLD_TEXT).doesNotContain("Later edited requirement text");
                var provenanceSummary = driver.findElement(By.cssSelector("#snapshotAdoption details > summary"));
                javascript().executeScript("arguments[0].scrollIntoView({behavior:'instant',block:'center'});",provenanceSummary); provenanceSummary.click();
                assertThat(driver.findElement(By.id("adoptionProvenance")).getText()).contains(snapshot.id());
                var selected = app.getBean(com.taxonomy.editor.ArchitectureEditorProjection.class).project(app.getBean(ArchitectureEditorService.class).read(target,null),true,
                        com.taxonomy.diagram.DiagramLayout.Strategy.STABLE,null,viewId);
                assertThat(selected.scene().nodes()).hasSize(2); assertThat(selected.scene().edges()).hasSize(1);
                driver.findElement(By.id("editorRationale")).sendKeys("Retain historical snapshot checkpoint");
                click("editorCheckpoint"); wait.until(browser -> app.getBean(EditorJournal.class).read(target).state().checkpointRevision() == 1); ready();
                var historical = wait.until(browser -> browser.findElements(By.cssSelector("#editorVersions a")).stream()
                        .filter(link -> link.getDomAttribute("href").contains("commit=" + beforeCommit)).findFirst().orElse(null));
                historical.click(); ready();
                assertThat(driver.getCurrentUrl()).doesNotContain("viewId=");
                assertThat(driver.findElement(By.id("editorError")).isDisplayed()).isFalse();
                click("editorRefresh"); wait.until(browser -> browser.getCurrentUrl().contains("revision=1")); ready();
                new Select(driver.findElement(By.id("adoptionView"))).selectByValue(viewId); ready();
                driver.findElement(By.id("editorRationale")).sendKeys("Review undo and redo after historical navigation");
                invert("AdoptAnalysisSnapshot",2); invert("UNDO",3);
                new Select(driver.findElement(By.id("adoptionView"))).selectByValue(viewId); ready();
                assertThat(app.getBean(EditorJournal.class).read(target).operations()).hasSize(3);
                Files.write(evidence.resolve("adopted-view.png"),((TakesScreenshot)driver).getScreenshotAs(OutputType.BYTES));
                click("adoptionSourceLink"); wait.until(browser -> browser.findElement(By.id("workflow-adopt")).isDisplayed());
                assertThat(driver.getCurrentUrl()).contains("workspaceId=&");
                assertThat(driver.findElement(By.id("requirementText")).getText()).contains(OLD_TEXT);
                Files.writeString(evidence.resolve("result.json"),JsonMapper.builder().build().writeValueAsString(Map.of(
                        "source","retained historical central fixture","snapshotId",snapshot.id(),"requirementVersionId",snapshot.requirementVersionId(),
                        "workspaceId",target.workspaceId(),"viewId",viewId,"revision",3,"sameIdentityRetry",true,"preAdoptionCheckpointOpened",true)));
                } catch (RuntimeException | AssertionError failure) {
                    Files.write(evidence.resolve("failure.png"),((TakesScreenshot)driver).getScreenshotAs(OutputType.BYTES));
                    var messages = driver.findElements(By.cssSelector("#editorError, #architectureAlert")).stream().map(WebElement::getText).toList();
                    Files.writeString(evidence.resolve("failure.json"),JsonMapper.builder().build().writeValueAsString(Map.of("url",driver.getCurrentUrl(),"messages",messages)));
                    throw failure;
                }
            }
        } finally { app.close(); }
        // Reopen the real application and its file database; adoption replay has no in-memory cache dependency.
        try (var restarted = start(url)) {
            assertThat(restarted.getBean(AnalysisSnapshotAdoptionService.class).adopt(target,originalRequest).accepted().replayed()).isTrue();
            var document = restarted.getBean(ArchitectureEditorService.class).read(target,null);
            assertThat(document.context().revision()).isEqualTo(3);
            assertThat(new ArchitectureDslCommands().model(document.dsl()).getViews()).anyMatch(view -> viewId.equals(view.getId()));
        }
    }
    private ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(TaxonomyApplication.class).run("--server.port=0","--spring.profiles.active=hsqldb",
                "--spring.datasource.url="+url,"--spring.datasource.username=SA","--spring.datasource.password=","--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update","--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--taxonomy.admin-password="+PASSWORD,"--taxonomy.security.require-password-change=false","--embedding.enabled=false","--embedding.allow-download=false",
                "--taxonomy.init.async=false","--spring.thymeleaf.cache=false","--llm.mock=true");
    }
    private void ready() { wait.until(browser -> "false".equals(browser.findElement(By.id("architectureEditor")).getDomAttribute("aria-busy"))); }
    private JavascriptExecutor javascript() { return (JavascriptExecutor)driver; }
    private void click(String id) {
        var element = wait.until(browser -> { var value = browser.findElement(By.id(id)); return value.isDisplayed() && value.isEnabled() ? value : null; });
        javascript().executeScript("arguments[0].scrollIntoView({behavior:'instant',block:'center'});",element); element.click();
    }
    private void invert(String kind,int revision) {
        var button = driver.findElement(By.xpath("//*[@id='editorHistory']/li[contains(.,'"+kind+"')]/button"));
        javascript().executeScript("arguments[0].scrollIntoView({behavior:'instant',block:'center'});",button); button.click();
        wait.until(browser -> browser.findElement(By.id("editorPreviewDialog")).isDisplayed() && browser.findElement(By.id("editorAccept")).isEnabled());
        click("editorAccept"); wait.until(browser -> browser.getCurrentUrl().contains("revision="+revision)); ready();
        assertThat(driver.findElement(By.id("editorError")).isDisplayed()).isFalse();
    }
    private static Stream<TaxonomyNodeDto> flatten(TaxonomyNodeDto node) { return Stream.concat(Stream.of(node),node.getChildren().stream().flatMap(AnalysisSnapshotAdoptionUiIT::flatten)); }
}

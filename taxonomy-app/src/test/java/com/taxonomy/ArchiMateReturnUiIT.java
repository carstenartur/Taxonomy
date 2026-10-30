package com.taxonomy;

import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.testsupport.BrowserSession;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

/** Production-provisioned empty repository fixture; every import action uses the real browser and HTTP. */
@Tag("browser")
@Tag("ui-acceptance")
@Tag("ci-app-shard-browser")
class ArchiMateReturnUiIT {
    @TempDir Path directory;
    private static final String PASSWORD = "Archi-Return-Browser-2026!";

    @Test void actualArchiReturnIsReviewedAppliedAndReopenedInIndependentRepository() throws Exception {
        Path evidence = Files.createDirectories(Path.of("target/archi-return-native"));
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        String fixturePath = "taxonomy-export/src/test/resources/interoperability/archi-return";
        while (root != null && !Files.isDirectory(root.resolve(fixturePath))) root = root.getParent();
        assertThat(root).isNotNull();
        Path fixtures = root.resolve(fixturePath);
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--spring.profiles.active=hsqldb",
                "--spring.datasource.url=jdbc:hsqldb:file:" + directory.resolve("application") + ";shutdown=true",
                "--spring.datasource.username=SA", "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--taxonomy.admin-password=" + PASSWORD, "--taxonomy.security.require-password-change=false",
                "--embedding.enabled=false", "--embedding.allow-download=false", "--taxonomy.init.async=false",
                "--spring.thymeleaf.cache=false", "--llm.mock=true")) {
            // Test prerequisite only: no repository-creation form exists. No imported relations are preloaded.
            var primary = app.getBean(SystemRepositoryService.class).getPrimaryRepository();
            var secondary = app.getBean(ArchitectureRepositoryProvisioningService.class).createRepository(
                    "Archi return", "archi-return-browser", "Independent empty return destination", RepositoryVisibility.PRIVATE, "admin", "main");
            var workspace = app.getBean(RepositoryWorkspaceService.class).createWorkingCopy(
                    "admin", secondary.getRepositoryId(), "main", "Archi return workspace", "Native import review");
            var relations = app.getBean(TaxonomyRelationRepository.class);
            var primaryIds = relations.findByRepositoryIdAndWorkspaceId(primary.getRepositoryId(), null).stream().map(row -> row.getId()).toList();
            assertThat(primaryIds).isNotEmpty();
            assertThat(relations.findByRepositoryIdAndWorkspaceIdAndSourceNodeCodeAndTargetNodeCodeAndRelationType(
                    primary.getRepositoryId(), null, "CR", "CP", com.taxonomy.model.RelationType.FULFILLS)).hasSize(1);
            assertThat(relations.findByRepositoryIdAndWorkspaceId(secondary.getRepositoryId(), null)).isEmpty();
            assertThat(relations.findByRepositoryIdAndWorkspaceId(secondary.getRepositoryId(), workspace.getWorkspaceId())).isEmpty();
            int port = app.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            try (var session = BrowserSession.open(port, evidence.resolve("downloads"))) {
                RemoteWebDriver driver = session.driver();
                if (!(driver instanceof ChromeDriver)) driver.setFileDetector(new LocalFileDetector());
                var wait = new WebDriverWait(driver, Duration.ofSeconds(45));
                driver.manage().window().setSize(new Dimension(1280, 900));
                driver.get(session.origin() + "/login");
                driver.findElement(By.name("username")).sendKeys("admin");
                driver.findElement(By.name("password")).sendKeys(PASSWORD, Keys.ENTER);
                wait.until(browser -> !browser.getCurrentUrl().contains("/login"));
                String pin = "&repositoryId=" + secondary.getRepositoryId() + "&workspaceId=" + workspace.getWorkspaceId() + "&branch=" + workspace.getCurrentBranch();
                try {
                    for (int attempt = 0; attempt < 3; attempt++) {
                        driver.get(session.origin() + "/?lang=en" + (attempt < 2 ? pin : "&workspaceId=&repositoryId=" + secondary.getRepositoryId() + "&branch=main"));
                        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("mainNavTabs")));
                        var dismiss = driver.findElements(By.id("onboardingDismiss"));
                        if (!dismiss.isEmpty() && dismiss.getFirst().isDisplayed()) click(driver, wait, "onboardingDismiss");
                        click(driver, wait, By.cssSelector("#mainNavTabs [data-page='architecture']"));
                        click(driver, wait, By.cssSelector("#architectureSubTabs [data-subtab='arch-export']"));
                        click(driver, wait, By.cssSelector("#archiReturnPanel summary"));
                        driver.findElement(By.id("archiReturnOriginal")).sendKeys(fixtures.resolve("workbench-original.xml").toString());
                        driver.findElement(By.id("archiReturnFile")).sendKeys(fixtures.resolve("archi-5.10-returned.xml").toString());
                        click(driver, wait, "archiReturnPreview");
                        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("archiReturnStatus"), "Matched elements:"));
                        if (attempt == 2) {
                            assertThat(driver.findElement(By.id("archiReturnStatus")).getText()).contains("editable working copy", "existing duplicates: 0");
                            assertThat(driver.findElement(By.id("archiReturnAccept")).isEnabled()).isFalse();
                            assertThat(driver.findElement(By.id("archiReturnApply")).isEnabled()).isFalse();
                            break;
                        }
                        wait.until(ExpectedConditions.elementToBeClickable(By.id("archiReturnAccept")));
                        assertThat(driver.findElement(By.id("archiReturnApply")).isEnabled()).isFalse();
                        assertThat(driver.findElement(By.id("archiReturnLosses")).getText()).contains(
                                "ARCHI_PROPERTY_TYPE_TO_STRING", "ARCHI_NODE_LABEL_OMITTED", "ARCHI_TYPE_FOLDERS_OMITTED");
                        assertThat(driver.findElement(By.id("archiReturnStatus")).getText())
                                .contains(attempt == 0 ? "existing duplicates: 0" : "existing duplicates: 1");
                        click(driver, wait, "archiReturnAccept");
                        click(driver, wait, "archiReturnApply");
                        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("archiReturnStatus"),
                                attempt == 0 ? "Created relations: 1; duplicates skipped: 0" : "Created relations: 0; duplicates skipped: 1"));
                        assertThat(driver.findElement(By.id("archiReturnApply")).isEnabled()).isFalse();
                    }
                    var rows = relations.findByRepositoryIdAndWorkspaceId(secondary.getRepositoryId(), workspace.getWorkspaceId());
                    assertThat(rows).hasSize(1);
                    assertThat(relations.findByRepositoryIdAndWorkspaceIdAndSourceNodeCodeAndTargetNodeCodeAndRelationType(
                            secondary.getRepositoryId(), workspace.getWorkspaceId(), "CR", "CP", com.taxonomy.model.RelationType.FULFILLS)).hasSize(1);
                    assertThat(rows.getFirst().getOwnerUsername()).isEqualTo("admin");
                    assertThat(rows.getFirst().getProvenance()).isEqualTo("ARCHIMATE_RETURN");
                    assertThat(relations.findByRepositoryIdAndWorkspaceId(primary.getRepositoryId(), null).stream().map(row -> row.getId()).toList()).containsExactlyInAnyOrderElementsOf(primaryIds);
                    assertThat(relations.findByRepositoryIdAndWorkspaceId(primary.getRepositoryId(), workspace.getWorkspaceId())).isEmpty();
                    Files.write(evidence.resolve("reviewed-return.png"), driver.getScreenshotAs(OutputType.BYTES));
                } catch (RuntimeException | AssertionError failure) {
                    captureFailure(driver, evidence, failure);
                    throw failure;
                }
            }
        }
    }
    private static void click(RemoteWebDriver driver, WebDriverWait wait, String id) {
        click(driver, wait, By.id(id));
    }

    private static void click(RemoteWebDriver driver, WebDriverWait wait, By locator) {
        try {
            wait.until(browser -> {
                try {
                    var element = browser.findElement(locator);
                    if (!element.isDisplayed() || !element.isEnabled()) return false;
                    driver.executeScript("arguments[0].scrollIntoView({behavior:'instant',block:'center',inline:'nearest'});", element);
                    boolean ready = Boolean.TRUE.equals(driver.executeAsyncScript("""
                            const target=arguments[0],done=arguments[arguments.length-1];
                            const before=target.getBoundingClientRect();
                            requestAnimationFrame(()=>requestAnimationFrame(()=>{
                                const after=target.getBoundingClientRect();
                                const x=after.left+after.width/2,y=after.top+after.height/2;
                                const hit=document.elementFromPoint(x,y);
                                const stable=['left','top','width','height'].every(key=>Math.abs(before[key]-after[key])<1);
                                done(target.isConnected && stable && after.width>0 && after.height>0
                                    && x>0 && x<innerWidth && y>0 && y<innerHeight
                                    && Boolean(hit && (hit===target || target.contains(hit))));
                            }));
                            """, element));
                    if (!ready) return false;
                    // The driver still performs the user action; scripts only scroll and observe readiness.
                    element.click();
                    return true;
                } catch (StaleElementReferenceException | ElementNotInteractableException transientLayout) {
                    return false;
                }
            });
        } catch (TimeoutException failure) {
            throw new AssertionError("Native click did not reach a stable, unobscured target: " + locator, failure);
        }
    }

    private static void captureFailure(RemoteWebDriver driver, Path evidence, Throwable failure) {
        try {
            Files.write(evidence.resolve("failure.png"), driver.getScreenshotAs(OutputType.BYTES));
        } catch (Exception capture) { failure.addSuppressed(capture); }
        try {
            Files.writeString(evidence.resolve("failure.html"), driver.getPageSource());
        } catch (Exception capture) { failure.addSuppressed(capture); }
        try {
            String status = driver.findElements(By.id("archiReturnStatus")).stream()
                    .map(WebElement::getText).findFirst().orElse("Return status is absent");
            Files.writeString(evidence.resolve("failure.txt"), driver.getCurrentUrl() + "\n" + status + "\n" + failure);
        } catch (Exception capture) { failure.addSuppressed(capture); }
        try {
            String geometry = (String) driver.executeScript("""
                    const selectors=['#mainNavTabs [data-page="architecture"]',
                        '#architectureSubTabs [data-subtab="arch-export"]','#archiReturnPanel summary',
                        '#archiReturnPreview','#archiReturnAccept','#archiReturnApply'];
                    return JSON.stringify({url:location.href,readyState:document.readyState,innerWidth,innerHeight,
                        scrollX,scrollY,activeElement:document.activeElement?.id,
                        contextOpen:document.getElementById('applicationContextDisclosure')?.open,
                        targets:selectors.map(selector=>{
                            const target=document.querySelector(selector);
                            if(!target)return {selector,missing:true};
                            const r=target.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);
                            return {selector,disabled:target.disabled,rect:{left:r.left,top:r.top,width:r.width,height:r.height},
                                hit:hit?{tag:hit.tagName,id:hit.id,className:String(hit.className)}:null};
                        })},null,2);
                    """);
            Files.writeString(evidence.resolve("failure-geometry.json"), geometry);
        } catch (Exception capture) { failure.addSuppressed(capture); }
    }
}

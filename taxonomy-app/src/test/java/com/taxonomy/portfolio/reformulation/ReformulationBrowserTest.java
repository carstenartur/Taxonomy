package com.taxonomy.portfolio.reformulation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.test.context.support.WithMockUser;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.*;
import org.openqa.selenium.support.ui.*;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.chromium.HasCdp;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"taxonomy.admin-password=Reformulation-Browser-2026!","taxonomy.security.require-password-change=false"})
@AutoConfigureMockMvc @WithMockUser(username="architect",roles="ARCHITECT")
class ReformulationBrowserTest extends ReformulationWorkflowFixture {
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired SaveGate saveGate;
    @org.springframework.boot.test.context.TestConfiguration
    static class DelayConfiguration {
        @org.springframework.context.annotation.Bean SaveGate saveGate() { return new SaveGate(); }
    }
    static class SaveGate extends org.springframework.web.filter.OncePerRequestFilter {
        volatile java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(0);
        volatile java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(0);
        final java.util.concurrent.atomic.AtomicBoolean armed=new java.util.concurrent.atomic.AtomicBoolean();
        void arm() { entered=new java.util.concurrent.CountDownLatch(1);release=new java.util.concurrent.CountDownLatch(1);armed.set(true); }
        @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,jakarta.servlet.FilterChain chain) throws java.io.IOException,jakarta.servlet.ServletException {
            if(request.getMethod().equals("POST") && request.getRequestURI().endsWith("/revisions") && armed.compareAndSet(true,false)) {
                entered.countDown();try { if(!release.await(20,java.util.concurrent.TimeUnit.SECONDS))throw new jakarta.servlet.ServletException("Save gate timed out"); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new jakarta.servlet.ServletException(failure); }
            }
            chain.doFilter(request,response);
        }
    }
    @Test void unsavedControlsSurviveOtherAnswerRunSourceRefreshAndBlockedOfferTransitions() throws Exception {
        var p=seed();var alternative=seed();
        inBrowser("draft-controls",p.id(),(driver,wait)->{
            var editor=driver.findElement(By.cssSelector("[data-reformulation-editor]"));editor.clear();editor.sendKeys("Unsaved main draft");
            click(driver,By.xpath("//div[@id='statement-capture']/parent::details/summary"));
            var statement=driver.findElement(By.cssSelector("#statement-capture textarea"));statement.clear();statement.sendKeys("Unsaved selected paragraph");
            driver.findElement(By.cssSelector("#question-text textarea")).sendKeys("Unsaved question wording");
            driver.findElement(By.cssSelector("#question-channel [data-other-answer]")).sendKeys("Unsaved other choice");
            var reason=driver.findElement(By.cssSelector("#question-text label:last-of-type input"));reason.clear();reason.sendKeys("Unsaved rationale");
            new Select(driver.findElement(By.cssSelector("#question-boolean select"))).selectByValue("true");
            var old=driver.findElement(By.id("question-boolean"));questionButton(driver,"boolean","Save answer").click();wait.until(ExpectedConditions.stalenessOf(old));
            assertDraftControls(driver);
            var revision=reformulations.get(project.id(),requirement.id(),p.id(),"architect",context).currentRevision();
            var run=reformulations.beginRun(project.id(),requirement.id(),p.id(),revision.number(),"TEST","test","v1","v1","frozen","architect",context);
            reformulations.finishRun(project.id(),requirement.id(),p.id(),run.id(),new com.taxonomy.reformulation.ReformulationDocument("New completed model wording",revision.sections(),revision.statements(),revision.questions(),revision.validation(),List.of()),null,"architect",context);
            old=driver.findElement(By.id("question-boolean"));click(driver,By.xpath("//section[@id='reformulationOffers']//button[normalize-space()='Refresh status']"));wait.until(ExpectedConditions.stalenessOf(old));assertDraftControls(driver);
            projects.addRequirementVersion(project.id(),requirement.id(),new com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest("External source change","Separate version",null),"architect",context);
            click(driver,By.xpath("//section[@id='reformulationOffers']//button[normalize-space()='Refresh status']"));
            wait.until(d->d.findElement(By.id("reformulationList")).getText().contains("older source version"));assertDraftControls(driver);
            int count=reformulations.list(project.id(),requirement.id(),"architect",context).size();
            click(driver,By.xpath("//section[@id='reformulationOffers']//button[normalize-space()='Propose reformulation']"));
            wait.until(d->d.findElement(By.cssSelector("[data-reformulation-status]")).getText().contains("Unsaved"));assertDraftControls(driver);
            assertThat(reformulations.list(project.id(),requirement.id(),"architect",context)).hasSize(count);
            new Select(driver.findElement(By.cssSelector("#reformulationList > select"))).selectByValue(alternative.id());
            assertThat(driver.findElement(By.cssSelector("#reformulationList > select")).getDomProperty("value")).isEqualTo(p.id());assertDraftControls(driver);
        });
    }
    private static void assertDraftControls(RemoteWebDriver driver) {
        org.assertj.core.api.SoftAssertions.assertSoftly(soft->{
            soft.assertThat(driver.findElement(By.cssSelector("[data-reformulation-editor]")).getDomProperty("value")).isEqualTo("Unsaved main draft");
            soft.assertThat(driver.findElement(By.cssSelector("#statement-capture textarea")).getDomProperty("value")).isEqualTo("Unsaved selected paragraph");
            soft.assertThat(driver.findElement(By.cssSelector("#question-text textarea")).getDomProperty("value")).isEqualTo("Unsaved question wording");
            soft.assertThat(driver.findElement(By.cssSelector("#question-channel [data-other-answer]")).getDomProperty("value")).isEqualTo("Unsaved other choice");
            soft.assertThat(driver.findElement(By.cssSelector("#question-text label:last-of-type input")).getDomProperty("value")).isEqualTo("Unsaved rationale");
        });
    }
    @Test void typingDuringActualHttpSaveRetainsNewerDraftGeneration() throws Exception {
        var p=seed();inBrowser("in-flight-save",p.id(),(driver,wait)->{
            var editor=driver.findElement(By.cssSelector("[data-reformulation-editor]"));editor.clear();editor.sendKeys("Submitted text");
            saveGate.arm();try {
                click(driver,By.cssSelector("[data-reformulation-save]"));assertThat(saveGate.entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                editor.sendKeys(" plus newer typing");
            } finally {saveGate.release.countDown();}
            wait.until(ExpectedConditions.stalenessOf(editor));wait.until(d->d.findElement(By.cssSelector("[data-reformulation-status]")).getText().contains("Saved"));
            assertThat(reformulations.get(project.id(),requirement.id(),p.id(),"architect",context).currentRevision().text()).isEqualTo("Submitted text");
            assertThat(driver.findElement(By.cssSelector("[data-reformulation-editor]")).getDomProperty("value")).isEqualTo("Submitted text plus newer typing");
        });
    }
    @Test void nodeAndDirectedEdgeLinksOpenSafeDetailsFromOfferFrozenSnapshot() throws Exception {
        withBoundary=true;snapshot=snapshot(requirement);String frozenSnapshot=snapshot;
        String edgeId="edge-"+analyses.getSnapshot(project.id(),snapshot,"architect",context).relationMappings().getFirst().id();
        questionTransform=qs->{var result=new ArrayList<>(qs);var q=qs.getFirst();
            var discovery=new com.taxonomy.reformulation.DecisionQuestion.Discovery("BP-1","Capture","Frozen reference",List.of(),List.of("BP-1"),List.of(edgeId));
            result.set(0,new com.taxonomy.reformulation.DecisionQuestion(q.id(),q.key(),q.wording(),List.of(discovery),q.affectedStatementIds(),q.answerSchema(),q.prerequisites(),q.dependentQuestionIds(),q.consequences(),q.state()));return result;};
        var p=seed();withBoundary=false;snapshot(requirement);
        inBrowser("frozen-details",p.id(),(driver,wait)->{
            click(driver,By.cssSelector("#question-channel > details > summary"));
            click(driver,By.linkText("Architecture reference BP-1"));
            assertThat(driver.findElements(By.cssSelector("[data-reformulation-architecture-detail]"))).hasSize(1);
            var detail=driver.findElement(By.cssSelector("[data-reformulation-architecture-detail]"));
            assertThat(detail.getDomAttribute("data-snapshot-id")).isEqualTo(frozenSnapshot);
            assertThat(detail.getText()).contains("BP-1","Frozen <img src=x onerror=alert(1)> node detail");
            click(driver,By.linkText("Architecture reference "+edgeId));detail=driver.findElement(By.cssSelector("[data-reformulation-architecture-detail]"));
            assertThat(detail.getDomAttribute("data-snapshot-id")).isEqualTo(frozenSnapshot);
            assertThat(detail.getText()).contains("BP-1 → BP-2","FLOW","Frozen <b>directed boundary</b>");
            assertThat(detail.findElements(By.cssSelector("img,b,script"))).isEmpty();
        });
    }
    @FunctionalInterface interface BrowserAction { void run(RemoteWebDriver driver,WebDriverWait wait) throws Exception; }
    private void inBrowser(String name,String proposalId,BrowserAction action) throws Exception {
        var options=new ChromeOptions();options.addArguments("--headless=new","--no-sandbox","--disable-dev-shm-usage");
        String binary=System.getProperty("civilian.chrome.binary");if(binary!=null)options.setBinary(binary);
        BrowserWebDriverContainer<?> container=null;RemoteWebDriver driver;String origin;
        if(System.getProperty("webdriver.chrome.driver")!=null){driver=new ChromeDriver(options);origin="http://localhost:"+port;}
        else {Testcontainers.exposeHostPorts(port);origin="http://host.testcontainers.internal:"+port;container=new BrowserWebDriverContainer<>(DockerImageName.parse(System.getProperty("selenium.container.image","selenium/standalone-chrome:"+new BuildInfo().getReleaseLabel())));container.start();driver=new RemoteWebDriver(container.getSeleniumAddress(),options);}
        Path output=Path.of("target/civilian-acceptance/reformulation-browser",name);Files.createDirectories(output);
        try {
            var wait=new WebDriverWait(driver,Duration.ofSeconds(20));driver.manage().window().setSize(new Dimension(1440,1000));driver.get(origin+"/login");
            driver.findElement(By.name("username")).sendKeys("admin");driver.findElement(By.name("password")).sendKeys("Reformulation-Browser-2026!");driver.findElement(By.cssSelector("form")).submit();wait.until(d->!d.getCurrentUrl().contains("/login"));
            driver.get(origin+"/projects/"+project.id()+"/requirements/"+requirement.id()+"?lang=en&proposal="+proposalId);wait.until(d->!d.findElements(By.cssSelector("[data-reformulation-editor]")).isEmpty());
            action.run(driver,wait);Files.write(output.resolve("passed.png"),driver.getScreenshotAs(OutputType.BYTES));
        } catch(Throwable failure) {
            Files.write(output.resolve("failure.png"),driver.getScreenshotAs(OutputType.BYTES));Files.writeString(output.resolve("failure-page.html"),driver.getPageSource());
            Files.writeString(output.resolve("failure-geometry.json"),String.valueOf(driver.executeScript("return JSON.stringify(window.__reformulationClickGeometry || {})")));throw failure;
        } finally {saveGate.release.countDown();driver.quit();if(container!=null)container.close();}
    }
    @Test void wideAndNarrowWorkspacePreservesEditsWhitespaceAndExplicitProposalSave() throws Exception {
        var proposal=seed();var before=projects.getRequirement(project.id(),requirement.id(),"architect",context);
        var options=new ChromeOptions(); options.addArguments("--headless=new","--no-sandbox","--disable-dev-shm-usage");
        String binary=System.getProperty("civilian.chrome.binary");if(binary!=null)options.setBinary(binary);
        BrowserWebDriverContainer<?> container=null;RemoteWebDriver driver;String origin;
        if(System.getProperty("webdriver.chrome.driver")!=null) {driver=new ChromeDriver(options);origin="http://localhost:"+port;}
        else {
            Testcontainers.exposeHostPorts(port);origin="http://host.testcontainers.internal:"+port;
            container=new BrowserWebDriverContainer<>(DockerImageName.parse(System.getProperty("selenium.container.image","selenium/standalone-chrome:"+new BuildInfo().getReleaseLabel())));
            container.start();driver=new RemoteWebDriver(container.getSeleniumAddress(),options);
        }
        try {
            var wait=new WebDriverWait(driver,Duration.ofSeconds(20));
            driver.manage().window().setSize(new Dimension(1440,1000));driver.get(origin+"/login");
            driver.findElement(By.name("username")).sendKeys("admin");driver.findElement(By.name("password")).sendKeys("Reformulation-Browser-2026!");driver.findElement(By.cssSelector("form")).submit();
            wait.until(d->!d.getCurrentUrl().contains("/login"));driver.get(origin+"/projects/"+project.id()+"/requirements/"+requirement.id()+"?lang=de&proposal="+proposal.id());
            wait.until(d->d.findElements(By.cssSelector("[data-reformulation-editor]")).size()>0);
            var editor=driver.findElement(By.cssSelector("[data-reformulation-editor]"));editor.clear();editor.sendKeys("Menschlicher Absatz\n  Einrückung <script>data</script>");
            assertThat(driver.findElement(By.cssSelector("[data-reformulation-original]")).getDomProperty("textContent")).isEqualTo(ORIGINAL);
            assertThat(driver.findElement(By.cssSelector("[data-reformulation-original]")).getCssValue("white-space")).isEqualTo("pre-wrap");
            assertThat(driver.findElements(By.cssSelector("#reformulationOffers img"))).isEmpty();
            Path output=Path.of("target/civilian-acceptance/reformulation-browser");Files.createDirectories(output);
            driver.executeScript("document.getElementById('reformulationOffers').scrollIntoView()");
            Files.write(output.resolve("wide-de.png"),driver.getScreenshotAs(OutputType.BYTES));
            ((HasCdp)new Augmenter().augment(driver)).executeCdpCommand("Emulation.setDeviceMetricsOverride",Map.of("width",390,"height",844,"deviceScaleFactor",1,"mobile",true));
            wait.until(d->((Number)driver.executeScript("return window.innerWidth")).intValue()==390);
            click(driver,By.cssSelector("[data-reformulation-view='original']"));
            assertThat(driver.findElement(By.cssSelector("[data-reformulation-original]")).getCssValue("white-space")).isEqualTo("pre-wrap");
            Files.write(output.resolve("narrow-original-de.png"),driver.getScreenshotAs(OutputType.BYTES));
            click(driver,By.cssSelector("[data-reformulation-view='questions']"));
            click(driver,By.cssSelector("[data-reformulation-view='proposal']"));
            assertThat(editor.getDomProperty("value")).contains("Menschlicher Absatz\n  Einrückung");
            assertThat((Boolean)driver.executeScript("return document.documentElement.scrollWidth <= window.innerWidth")).isTrue();
            driver.findElement(By.cssSelector("[data-reformulation-save]")).sendKeys(Keys.ENTER);
            wait.until(d->d.findElement(By.cssSelector("[data-reformulation-status]")).getText().contains("Gespeichert"));
            assertThat(driver.findElement(By.cssSelector("[data-reformulation-status]")).getDomAttribute("aria-live")).isEqualTo("polite");
            Files.write(output.resolve("narrow-de.png"),driver.getScreenshotAs(OutputType.BYTES));
            assertThat(reformulations.get(project.id(),requirement.id(),proposal.id(),"architect",context).currentRevision().text()).contains("Menschlicher Absatz");
            assertThat(projects.getRequirement(project.id(),requirement.id(),"architect",context)).isEqualTo(before);
            click(driver,By.cssSelector("[data-reformulation-view='questions']"));
            click(driver,By.cssSelector("#question-channel input[value='Terminal']"));
            submit(driver,wait,"channel");
            assertThat(driver.switchTo().activeElement().getDomAttribute("id")).isEqualTo("question-channel");
            click(driver,By.cssSelector("#question-multiple input[value='Browser']"));
            click(driver,By.cssSelector("#question-multiple input[value='Terminal']"));submit(driver,wait,"multiple");
            driver.findElement(By.cssSelector("#question-text textarea")).sendKeys("Text <b>untrusted</b>");submit(driver,wait,"text");
            driver.findElement(By.cssSelector("#question-number input[type='number']")).sendKeys("2.5");submit(driver,wait,"number");
            new Select(driver.findElement(By.cssSelector("#question-boolean select"))).selectByValue("true");submit(driver,wait,"boolean");
            click(driver,By.cssSelector("#question-correction input[value='Not needed']"));submit(driver,wait,"correction");
            click(driver,By.cssSelector("#question-channel input[value='Other']"));
            driver.findElement(By.cssSelector("#question-channel [data-other-answer]")).sendKeys("Papierkarte mit späterer Eingabe");submit(driver,wait,"channel");
            questionButton(driver,"channel","Noch offen / zurückstellen").click();wait.until(d->d.findElement(By.cssSelector("#question-channel")).getText().contains("Zurückgestellt"));
            questionButton(driver,"correction","Nicht anwendbar").click();wait.until(d->d.findElement(By.cssSelector("[data-reformulation-status]")).getText().contains("Gespeichert"));
            assertThat(reformulations.get(project.id(),requirement.id(),proposal.id(),"architect",context).currentRevision().answers()).hasSize(9);
            assertThat(projects.getRequirement(project.id(),requirement.id(),"architect",context)).isEqualTo(before);
            assertThat(driver.findElements(By.cssSelector("#reformulationOffers b"))).isEmpty();
            var current=reformulations.get(project.id(),requirement.id(),proposal.id(),"architect",context).currentRevision();
            var late=reformulations.beginRun(project.id(),requirement.id(),proposal.id(),current.number(),"TEST","test","v1","v1","frozen","architect",context);
            click(driver,By.cssSelector("[data-reformulation-view='proposal']"));
            var pending=driver.findElement(By.cssSelector("[data-reformulation-editor]"));pending.sendKeys("\nNoch nicht gespeichert");
            reformulations.finishRun(project.id(),requirement.id(),proposal.id(),late.id(),new com.taxonomy.reformulation.ReformulationDocument("Late <b>candidate</b>",current.sections(),current.statements(),current.questions(),current.validation(),List.of()),null,"architect",context);
            click(driver,By.xpath("//section[@id='reformulationOffers']//button[normalize-space()='Status aktualisieren']"));
            wait.until(d->d.findElements(By.cssSelector("[data-reformulation-candidate]")).size()==1);
            assertThat(driver.findElement(By.cssSelector("[data-reformulation-editor]")).getDomProperty("value")).contains("Noch nicht gespeichert");
            click(driver,By.cssSelector("[data-reformulation-candidate] button"));
            assertThat(driver.findElement(By.id("reformulationComparison")).getText()).contains("Late <b>candidate</b>","Menschlicher Absatz");
            assertThat(reformulations.get(project.id(),requirement.id(),proposal.id(),"architect",context).currentRevision()).isEqualTo(current);
            click(driver,By.cssSelector("[data-reformulation-save]"));wait.until(d->d.findElement(By.cssSelector("[data-reformulation-status]")).getText().contains("Gespeichert"));
            projects.addRequirementVersion(project.id(),requirement.id(),new com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest("Explicit separate source change","Version change outside proposal",null),"architect",context);
            click(driver,By.xpath("//section[@id='reformulationOffers']//button[normalize-space()='Status aktualisieren']"));
            wait.until(d->d.findElement(By.id("reformulationList")).getText().contains("älteren Quellversion"));
            assertThat(driver.findElement(By.cssSelector("[data-reformulation-original]")).getDomProperty("textContent")).isEqualTo(ORIGINAL);
            driver.get(origin+"/projects/"+project.id()+"/requirements/"+requirement.id()+"?lang=en&proposal="+proposal.id());
            wait.until(d->d.findElement(By.cssSelector("[data-reformulation-save]")).getText().equals("Save draft"));
            ((HasCdp)new Augmenter().augment(driver)).executeCdpCommand("Emulation.clearDeviceMetricsOverride",Map.of());
            driver.manage().window().setSize(new Dimension(1440,1000));
            driver.executeScript("document.getElementById('reformulationOffers').scrollIntoView()");
            Files.write(output.resolve("wide-en.png"),driver.getScreenshotAs(OutputType.BYTES));
            assertThat(driver.findElement(By.id("reformulationList")).getText()).contains("older source version");
        } catch(Throwable failure) {
            Path output=Path.of("target/civilian-acceptance/reformulation-browser");Files.createDirectories(output);
            Files.write(output.resolve("failure.png"),driver.getScreenshotAs(OutputType.BYTES));
            Files.writeString(output.resolve("failure-page.html"),driver.getPageSource());
            Files.writeString(output.resolve("failure-geometry.json"),String.valueOf(driver.executeScript("return JSON.stringify(window.__reformulationClickGeometry || {})")));throw failure;
        } finally {driver.quit();if(container!=null)container.close();}
    }
    private static WebElement questionButton(RemoteWebDriver driver,String id,String text) {
        var button=driver.findElement(By.id("question-"+id)).findElement(By.xpath(".//button[normalize-space()='"+text+"']"));
        awaitNativeClickTarget(driver,button);return button;
    }
    private static void submit(RemoteWebDriver driver,WebDriverWait wait,String id) {
        var box=driver.findElement(By.id("question-"+id));questionButton(driver,id,"Antwort speichern").click();
        wait.until(ExpectedConditions.stalenessOf(box));
        wait.until(d->d.findElement(By.cssSelector("[data-reformulation-status]")).getText().contains("Gespeichert"));
    }
    private static void awaitNativeClickTarget(RemoteWebDriver driver,WebElement element) {
        driver.executeScript("arguments[0].scrollIntoView({block:'center'})",element);
        String[] previous = {""};
        new WebDriverWait(driver,Duration.ofSeconds(20)).until(d -> {
            @SuppressWarnings("unchecked") var geometry=(Map<String,Object>)driver.executeScript("""
                const el=arguments[0], r=el.getBoundingClientRect();
                const x=r.left+r.width/2, y=r.top+r.height/2, hit=document.elementFromPoint(x,y);
                const g={scrollX,scrollY,width:innerWidth,height:innerHeight,
                  rect:{x:r.x,y:r.y,width:r.width,height:r.height},
                  scrollBehavior:getComputedStyle(document.documentElement).scrollBehavior,
                  target:el.outerHTML.slice(0,500),hit:hit?.outerHTML.slice(0,500),
                  ready:x>=0 && x<innerWidth && y>=0 && y<innerHeight && !!hit && (hit===el || el.contains(hit))};
                window.__reformulationClickGeometry=g; return g;
                """,element);
            String position=geometry.get("rect").toString()+geometry.get("scrollY");
            boolean stable=position.equals(previous[0]);previous[0]=position;
            return stable && Boolean.TRUE.equals(geometry.get("ready")) && element.isEnabled();
        });
    }
    private static void click(RemoteWebDriver driver,By selector) {
        var element=new WebDriverWait(driver,Duration.ofSeconds(20)).until(ExpectedConditions.elementToBeClickable(selector));
        awaitNativeClickTarget(driver,element);element.click();
    }
}

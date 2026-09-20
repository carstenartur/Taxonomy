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

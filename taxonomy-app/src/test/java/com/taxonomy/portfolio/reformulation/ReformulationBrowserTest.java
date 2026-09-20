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
            Files.write(output.resolve("wide-de.png"),driver.getScreenshotAs(OutputType.BYTES));
            ((HasCdp)new Augmenter().augment(driver)).executeCdpCommand("Emulation.setDeviceMetricsOverride",Map.of("width",390,"height",844,"deviceScaleFactor",1,"mobile",true));
            driver.findElement(By.cssSelector("[data-reformulation-view='questions']")).click();
            driver.findElement(By.cssSelector("[data-reformulation-view='proposal']")).click();
            assertThat(editor.getDomProperty("value")).contains("Menschlicher Absatz\n  Einrückung");
            assertThat((Boolean)driver.executeScript("return document.documentElement.scrollWidth <= window.innerWidth")).isTrue();
            driver.findElement(By.cssSelector("[data-reformulation-save]")).sendKeys(Keys.ENTER);
            wait.until(d->d.findElement(By.cssSelector("[data-reformulation-status]")).getText().contains("Gespeichert"));
            assertThat(driver.findElement(By.cssSelector("[data-reformulation-status]")).getDomAttribute("aria-live")).isEqualTo("polite");
            Files.write(output.resolve("narrow-de.png"),driver.getScreenshotAs(OutputType.BYTES));
            assertThat(reformulations.get(project.id(),requirement.id(),proposal.id(),"architect",context).currentRevision().text()).contains("Menschlicher Absatz");
            assertThat(projects.getRequirement(project.id(),requirement.id(),"architect",context)).isEqualTo(before);
        } finally {driver.quit();if(container!=null)container.close();}
    }
}

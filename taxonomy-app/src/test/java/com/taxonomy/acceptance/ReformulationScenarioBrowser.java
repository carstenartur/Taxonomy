package com.taxonomy.acceptance;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.*;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.remote.*;
import org.openqa.selenium.support.ui.*;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Real DOM and downloads against the HTTP-created analysed offer. No browser state is injected. */
final class ReformulationScenarioBrowser implements AutoCloseable {
    private final RemoteWebDriver driver;
    private final BrowserWebDriverContainer<?> container;
    private final WebDriverWait wait;
    private final Path output, downloads;
    private final String origin;
    private boolean completed;

    ReformulationScenarioBrowser(int port, Path output) throws Exception {
        this.output = Files.createDirectories(output.resolve("browser"));
        downloads = Files.createDirectories(this.output.resolve("downloads")).toAbsolutePath();
        var options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage");
        if (System.getProperty("webdriver.chrome.driver") != null) {
            String binary = System.getProperty("scenario.chrome.binary"); if (binary != null) options.setBinary(binary);
            options.setExperimentalOption("prefs", Map.of("download.default_directory", downloads.toString(), "download.prompt_for_download", false));
            container = null; origin = "http://localhost:" + port; driver = new ChromeDriver(options);
        } else {
            Testcontainers.exposeHostPorts(port); origin = "http://host.testcontainers.internal:" + port;
            container = new BrowserWebDriverContainer<>(DockerImageName.parse(System.getProperty("selenium.container.image",
                    "selenium/standalone-chrome:" + new BuildInfo().getReleaseLabel())))
                    .withEnv("SE_NODE_ENABLE_MANAGED_DOWNLOADS", "true");
            try {
                container.start(); options.setEnableDownloads(true);
                options.addArguments("--unsafely-treat-insecure-origin-as-secure=" + origin);
                driver = new RemoteWebDriver(container.getSeleniumAddress(), options);
            } catch (RuntimeException failure) { container.close(); throw failure; }
        }
        wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        driver.manage().window().setSize(new Dimension(1440, 1000));
    }
    void login(String password) {
        driver.get(origin + "/login");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.name("username"))).sendKeys("admin");
        driver.findElement(By.name("password")).sendKeys(password); driver.findElement(By.cssSelector("form")).submit();
        wait.until(d -> !d.getCurrentUrl().contains("/login"));
    }
    private void open(String path) {
        int split = path.indexOf("/reformulations/");
        driver.get(origin + path.substring(4, split) + "?lang=en&proposal=" + path.substring(split + 16));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[data-reformulation-editor]")));
    }
    void inspect(String path, String original, String questionId) throws Exception {
        open(path);
        assertThat(driver.findElement(By.cssSelector("[data-reformulation-original]")).getDomProperty("textContent")).isEqualTo(original);
        assertThat(driver.findElement(By.cssSelector("[data-reformulation-status]")).getDomAttribute("aria-live")).isEqualTo("polite");
        var editor = driver.findElement(By.cssSelector("[data-reformulation-editor]"));
        editor.clear(); editor.sendKeys("Unsaved scenario wording — keep this across status refresh.");
        shot("desktop-offer.png");
        ((HasCdp) new Augmenter().augment(driver)).executeCdpCommand("Emulation.setDeviceMetricsOverride",
                Map.of("width", 390, "height", 844, "deviceScaleFactor", 1, "mobile", true));
        wait.until(d -> ((Number) driver.executeScript("return window.innerWidth")).intValue() == 390);
        selectView("questions");
        String cardId = "question-" + questionId;
        var card = driver.findElement(By.id(cardId));
        var choice = driver.findElement(By.cssSelector("#" + cardId + " input[value='Retain last observation with timestamp']"));
        scroll(choice); choice.sendKeys(Keys.SPACE);
        wait.until(d -> choice.isSelected());
        var rationale = driver.findElement(By.cssSelector("#" + cardId + " label:last-of-type input"));
        rationale.clear(); rationale.sendKeys("Browser acceptance answer");
        var save = driver.findElement(By.xpath("//*[@id='" + cardId + "']//button[normalize-space()='Save answer']"));
        scroll(save); save.sendKeys(Keys.ENTER); wait.until(ExpectedConditions.stalenessOf(card));
        assertThat(driver.switchTo().activeElement().getDomAttribute("id")).isEqualTo(cardId);
        assertThat(driver.findElements(By.cssSelector("#reformulationList input[type='number']"))).hasSize(2);
        shot("mobile-questions.png");
        selectView("proposal");
        click(By.xpath("//section[@id='reformulationOffers']//button[normalize-space()='Refresh status']"));
        wait.until(d -> d.findElement(By.cssSelector("[data-reformulation-editor]")).getDomProperty("value").startsWith("Unsaved scenario wording"));
        assertThat((Boolean) driver.executeScript("return document.documentElement.scrollWidth <= window.innerWidth")).isTrue();
        click(By.cssSelector("[data-reformulation-report='json']"));
        String file = wait.until(d -> downloaded().stream().filter(n -> n.endsWith(".json")).findFirst().orElse(null));
        if (container != null) driver.downloadFile(file, downloads);
        var report = new tools.jackson.databind.ObjectMapper().readTree(Files.readString(downloads.resolve(file)));
        assertThat(report.path("kind").asText()).isEqualTo("PROPOSAL_REVISION");
        assertThat(report.toString()).doesNotContain("Unsaved scenario wording");
        assertThat(driver.findElement(By.cssSelector("[data-reformulation-editor]")).getDomProperty("value")).startsWith("Unsaved scenario wording");
        shot("mobile-draft-and-download.png");
    }
    void preview(String path) throws Exception {
        open(path); click(By.xpath("//button[normalize-space()='Compare with predecessor']"));
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("reformulationComparison"), "15 minutes"));
        click(By.xpath("//button[normalize-space()='Review adoption…']"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("dialog [data-adoption-action='confirm']")));
        assertThat(driver.findElement(By.cssSelector("dialog [data-adoption-action='confirm']")).isEnabled()).isFalse();
        assertThat(driver.findElement(By.cssSelector("dialog")).getText()).contains("Decision questions remain open", "Exactly this text will be adopted");
        shot("mobile-adoption-preview.png");
    }
    void confirm(String rationale) throws Exception {
        var confirmed = driver.findElement(By.cssSelector("dialog [data-adoption-action='confirmed']")); scroll(confirmed); confirmed.sendKeys(Keys.SPACE);
        assertThat(driver.findElement(By.cssSelector("dialog [data-adoption-action='confirm']")).isEnabled()).isFalse();
        var warnings = driver.findElement(By.cssSelector("dialog [data-adoption-action='warnings']")); scroll(warnings); warnings.sendKeys(Keys.SPACE);
        driver.findElement(By.cssSelector("dialog [data-adoption-action='rationale']")).sendKeys(rationale);
        var submit = driver.findElement(By.cssSelector("dialog [data-adoption-action='confirm']"));
        wait.until(d -> submit.isEnabled()); scroll(submit); submit.sendKeys(Keys.ENTER);
        wait.until(d -> d.findElement(By.cssSelector("dialog [role='status']")).getText().contains("Adoption recorded"));
        shot("mobile-adoption-recorded.png"); completed = true;
    }
    private Set<String> downloaded() {
        if (container != null) return new TreeSet<>(driver.getDownloadableFiles());
        try (var files = Files.list(downloads)) { return new TreeSet<>(files.map(p -> p.getFileName().toString()).toList()); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    private void selectView(String view) {
        By tab = By.cssSelector("[data-reformulation-view='" + view + "']");
        click(tab);
        wait.until(ExpectedConditions.attributeToBe(tab, "aria-pressed", "true"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[data-panel='" + view + "']")));
    }
    private void click(By by) {
        var element = wait.until(ExpectedConditions.elementToBeClickable(by)); scroll(element);
        driver.executeScript("window.__reformulationLastClickGeometry=window.__reformulationClickGeometry");
        element.click();
    }
    private void scroll(WebElement element) {
        // Native clicks must not race CSS smooth scrolling after a responsive layout change.
        driver.executeScript("arguments[0].scrollIntoView({behavior:'instant',block:'center',inline:'nearest'})", element);
        String[] previous = {""};
        wait.until(d -> {
            @SuppressWarnings("unchecked") var geometry = (Map<String, Object>) driver.executeScript("""
                    const el=arguments[0], r=el.getBoundingClientRect();
                    const x=r.left+r.width/2, y=r.top+r.height/2, hit=document.elementFromPoint(x,y);
                    const g={scrollX,scrollY,width:innerWidth,height:innerHeight,
                      rect:{x:r.x,y:r.y,width:r.width,height:r.height},
                      target:el.outerHTML.slice(0,500),hit:hit?.outerHTML.slice(0,500),
                      ready:x>=0 && x<innerWidth && y>=0 && y<innerHeight && !!hit && (hit===el || el.contains(hit))};
                    window.__reformulationClickGeometry=g; return g;
                    """, element);
            String position = geometry.get("rect").toString() + geometry.get("scrollY");
            boolean stable = position.equals(previous[0]); previous[0] = position;
            return stable && Boolean.TRUE.equals(geometry.get("ready")) && element.isEnabled();
        });
    }
    private void shot(String name) throws Exception { Files.write(output.resolve(name), driver.getScreenshotAs(OutputType.BYTES)); }
    @Override public void close() throws Exception {
        try { if (!completed) {
            shot("failure.png"); Files.writeString(output.resolve("failure-page.html"), driver.getPageSource());
            Files.writeString(output.resolve("failure-geometry.json"), String.valueOf(driver.executeScript("""
                    return JSON.stringify({lastClick:window.__reformulationLastClickGeometry,
                      current:window.__reformulationClickGeometry,focus:document.activeElement?.outerHTML.slice(0,500),
                      panels:Array.from(document.querySelectorAll('[data-panel]'),p=>({panel:p.dataset.panel,active:p.dataset.active})),
                      viewport:{width:innerWidth,height:innerHeight,scale:visualViewport?.scale,
                        left:visualViewport?.offsetLeft,top:visualViewport?.offsetTop}});
                    """)));
        } }
        finally { driver.quit(); if (container != null) container.close(); }
    }
}

package com.taxonomy.ui;

import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.testsupport.BrowserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the real login, template, CSRF transport and persisted dismissal. */
@Tag("browser")
@Tag("ci-app-shard-browser")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "taxonomy.admin-password=Database-Setup-Browser-2026!",
        "taxonomy.security.require-password-change=false",
        "spring.datasource.url=jdbc:hsqldb:mem:database-setup-browser",
        "server.forward-headers-strategy=framework"
})
@AutoConfigureMockMvc
class DatabaseSetupBrowserTest {
    @LocalServerPort int port;
    @Autowired PreferencesService preferences;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;
    @Autowired ConfigurableEnvironment environment;

    @BeforeEach
    void offerSetup() throws Exception {
        preferences.update(Map.of("setup.database.dismissed", false), "browser-test");
    }

    @Test
    void dismissalAndPreparationRetainTheActiveDatabaseAndWork() throws Exception {
        String originalUrl;
        try (var connection = dataSource.getConnection()) {
            originalUrl = connection.getMetaData().getURL();
        }
        inBrowser("optional-setup", (driver, wait, origin) -> {
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("databaseSetupStart")));
            assertThat(driver.findElements(By.cssSelector("dialog[open]"))).hasSize(1);
            driver.findElement(By.id("onboardingDismiss")).click();
            wait.until(d -> preferences.getBoolean("setup.database.dismissed", false));
            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
            var businessText = driver.findElement(By.id("businessText"));
            businessText.sendKeys("Keep this unfinished requirement while preparing a database.");

            openWizard(driver, wait);
            driver.findElement(By.id("databaseSetupNext")).click();
            var host = driver.findElement(By.id("databaseSetupHost"));
            host.clear();
            host.sendKeys("postgres.database.svc.cluster.local");
            driver.findElement(By.id("databaseSetupPassword")).sendKeys("not-in-preview-2026!");
            // Exercise the real conflict response without changing the initialized datasource.
            String conflictSource = "database-setup-browser-schema-conflict";
            environment.getPropertySources().addFirst(new MapPropertySource(conflictSource, Map.of(
                    "spring.jpa.properties.hibernate.hbm2ddl.auto", "create-drop")));
            try {
                driver.findElement(By.id("databaseSetupNext")).click();
                wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("databaseSetupStatus"),
                        "Existing schema-generation settings would override this template"));
                assertThat(driver.findElement(By.id("databaseSetupProperties")).isDisplayed()).isFalse();
                wait.until(ExpectedConditions.elementToBeClickable(By.id("databaseSetupNext")));
            } finally {
                environment.getPropertySources().remove(conflictSource);
            }
            driver.manage().window().setSize(new Dimension(420, 720));
            driver.findElement(By.id("databaseSetupNext")).click();
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("databaseSetupProperties")));
            assertThat(driver.executeScript("""
                    var body = document.querySelector('#databaseSetupDialog .database-setup-body');
                    var focused = document.activeElement;
                    var bounds = body.getBoundingClientRect();
                    var focusBounds = focused.getBoundingClientRect();
                    return body.contains(focused) && focusBounds.top >= bounds.top
                        && focusBounds.bottom <= bounds.bottom;
                    """)).as("the deployment step keeps keyboard focus in its visible scroll area").isEqualTo(true);
            String preview = driver.findElement(By.id("databaseSetupProperties")).getText();
            assertThat(preview).contains("jdbc:postgresql://postgres.database.svc.cluster.local:5432/taxonomy")
                    .contains("sslmode=verify-full")
                    .doesNotContain("not-in-preview-2026!");
            assertThat(driver.findElement(By.id("databaseSetupHelm")).getText())
                    .doesNotContain("not-in-preview-2026!");
            assertThat(driver.findElement(By.id("databaseSetupHelp")).getDomAttribute("href"))
                    .contains("#help/DATABASE_SETUP");
            driver.manage().window().setSize(new Dimension(1440, 1000));
            Files.write(Path.of("target/scenario-acceptance/database-setup/optional-setup/preview.png"),
                    driver.getScreenshotAs(OutputType.BYTES));
            driver.findElement(By.id("databaseSetupClose")).click();
            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("databaseSetupDialog")));
            assertThat(driver.findElement(By.id("databaseSetupPassword")).getDomProperty("value")).isEmpty();
            assertThat(businessText.getDomProperty("value"))
                    .isEqualTo("Keep this unfinished requirement while preparing a database.");

            openWizard(driver, wait);
            driver.findElement(By.id("databaseSetupType")).sendKeys(Keys.ESCAPE);
            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("databaseSetupDialog")));
            openWizard(driver, wait);
            driver.findElement(By.id("databaseSetupDismiss")).click();
            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("databaseSetupDialog")));

            driver.navigate().refresh();
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("analysisTaskProgress")));
            assertThat(driver.findElements(By.id("databaseSetupStart"))).isEmpty();
            assertThat(driver.findElements(By.cssSelector("dialog[open]"))).isEmpty();
        });
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(originalUrl);
        }
    }

    @Test
    void existingWelcomePreferenceDoesNotHideFirstDatabaseOfferAndAllTargetsHaveGuidance() throws Exception {
        inBrowser("returning-admin", (driver, wait, origin) -> {
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("databaseSetupStart")));
            // A browser may have seen the original welcome before this feature existed.
            driver.executeScript("localStorage.setItem('taxonomy_onboarded', '1')");
            driver.navigate().refresh();
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("databaseSetupStart")));
            driver.findElement(By.id("databaseSetupStart")).click();
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("databaseSetupDialog")));
            var type = new Select(driver.findElement(By.id("databaseSetupType")));
            assertThat(type.getOptions()).hasSize(3);
            for (String target : new String[]{"mssql", "oracle"}) {
                type.selectByValue(target);
                assertThat(driver.findElement(By.id("databaseSetupSupport")).getText())
                        .containsIgnoringCase("evaluation");
            }
            driver.findElement(By.id("databaseSetupNext")).click();
            assertThat(driver.findElement(By.id("databaseSetupPort")).getDomProperty("value")).isEqualTo("1521");
            assertThat(driver.findElement(By.id("databaseSetupCaGroup")).isDisplayed()).isFalse();
            assertThat(driver.findElement(By.id("databaseSetupTls")).isSelected()).isTrue();
            driver.findElement(By.id("databaseSetupClose")).click();
            wait.until(d -> preferences.getBoolean("setup.database.dismissed", false));
        });
    }

    @Test
    void settingsCanRetryAfterAnInitialAccountLookupFailure() throws Exception {
        inBrowser("bootstrap-recovery", """
                var original = client.getAccountContext;
                client.getAccountContext = function () {
                    return window.databaseSetupAccountReady ? original()
                        : Promise.reject(new Error('Simulated temporary account failure'));
                };
                """, (driver, wait, origin) -> {
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("analysisTaskProgress")));
            wait.until(ExpectedConditions.elementToBeClickable(By.id("onboardingDismiss"))).click();
            assertThat(preferences.getBoolean("setup.database.dismissed", false)).isFalse();
            driver.findElement(By.cssSelector("#applicationSettings > summary")).click();
            wait.until(ExpectedConditions.elementToBeClickable(By.id("databaseSetupMenuAction"))).click();
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("databaseSetupNotice")));
            assertThat(driver.executeScript("""
                    var bounds = document.getElementById('databaseSetupNotice').getBoundingClientRect();
                    return bounds.top >= 0 && bounds.bottom <= window.innerHeight;
                    """)).as("a failed Settings action shows its notice in the current viewport").isEqualTo(true);
            assertThat(driver.findElements(By.cssSelector("#databaseSetupDialog[open]"))).isEmpty();
            driver.executeScript("window.databaseSetupAccountReady = true");
            openWizard(driver, wait);
            assertThat(driver.findElement(By.id("databaseSetupNotice")).isDisplayed()).isFalse();
            driver.findElement(By.id("databaseSetupDismiss")).click();
            wait.until(d -> preferences.getBoolean("setup.database.dismissed", false));
        });
    }

    @Test
    void openingSettingsDuringStatusLoadingDoesNotStackTheWelcomeDialog() throws Exception {
        inBrowser("pending-status", """
                var original = client.getJson;
                client.getJson = function (url, options) {
                    var response = original(url, options);
                    if (url !== '/api/admin/database-setup') return response;
                    return response.then(function (state) {
                        return new Promise(function (resolve) {
                            window.databaseSetupStatusPending = true;
                            window.releaseDatabaseSetupStatus = function () { resolve(state); };
                        });
                    });
                };
                """, (driver, wait, origin) -> {
            // Hold the real status response until the explicit Settings action is waiting for it.
            wait.until(d -> Boolean.TRUE.equals(driver.executeScript("return window.databaseSetupStatusPending === true")));
            driver.findElement(By.cssSelector("#applicationSettings > summary")).click();
            wait.until(ExpectedConditions.elementToBeClickable(By.id("databaseSetupMenuAction"))).click();
            driver.executeScript("window.releaseDatabaseSetupStatus()");
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("databaseSetupDialog")));
            assertThat(driver.findElements(By.cssSelector("dialog[open]")))
                    .as("an explicit setup request replaces the pending automatic welcome").hasSize(1);
            assertThat(driver.findElements(By.cssSelector("#onboardingOverlay[open]"))).isEmpty();
            driver.findElement(By.id("databaseSetupDismiss")).click();
            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("databaseSetupDialog")));
            wait.until(d -> preferences.getBoolean("setup.database.dismissed", false));
        });
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void wizardHelpAndScriptKeepTheRancherIngressPrefix() throws Exception {
        mvc.perform(get("/").param("lang", "en")
                        .header("X-Forwarded-Prefix", "/taxonomy")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "taxonomy.example.test"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/taxonomy/?lang=en#help/DATABASE_SETUP")))
                .andExpect(content().string(containsString("/taxonomy/js/shared/taxonomy-database-setup.js")));
    }

    private static void openWizard(RemoteWebDriver driver, WebDriverWait wait) {
        var settings = driver.findElement(By.id("applicationSettings"));
        if (!"true".equals(settings.getDomProperty("open"))) {
            settings.findElement(By.tagName("summary")).click();
        }
        wait.until(ExpectedConditions.elementToBeClickable(By.id("databaseSetupMenuAction"))).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("databaseSetupDialog")));
    }

    @FunctionalInterface
    interface BrowserAction {
        void run(RemoteWebDriver driver, WebDriverWait wait, String origin) throws Exception;
    }

    private void inBrowser(String name, BrowserAction action) throws Exception {
        inBrowser(name, null, action);
    }

    private void inBrowser(String name, String clientSetup, BrowserAction action) throws Exception {
        Path output = Path.of("target/scenario-acceptance/database-setup", name);
        Files.createDirectories(output);
        try (var session = BrowserSession.open(port, output.resolve("downloads"))) {
            var driver = session.driver();
            var wait = new WebDriverWait(driver, Duration.ofSeconds(25));
            driver.manage().window().setSize(new Dimension(1440, 1000));
            try {
                if (clientSetup != null) {
                    HasCdp cdp = driver instanceof HasCdp local ? local : (HasCdp) new Augmenter().augment(driver);
                    cdp.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", Map.of("source", """
                            Object.defineProperty(window, 'TaxonomyApiClient', {
                                configurable: true,
                                set: function (client) {
                                    Object.defineProperty(window, 'TaxonomyApiClient', {
                                        value: client, writable: true, configurable: true
                                    });
                            """ + clientSetup + """
                                }
                            });
                            """));
                }
                driver.get(session.origin() + "/login?lang=en");
                driver.findElement(By.name("username")).sendKeys("admin");
                driver.findElement(By.name("password")).sendKeys("Database-Setup-Browser-2026!");
                driver.findElement(By.cssSelector("form")).submit();
                wait.until(d -> !d.getCurrentUrl().contains("/login"));
                action.run(driver, wait, session.origin());
                Files.write(output.resolve("passed.png"), driver.getScreenshotAs(OutputType.BYTES));
            } catch (Throwable failure) {
                Files.write(output.resolve("failure.png"), driver.getScreenshotAs(OutputType.BYTES));
                throw failure;
            }
        }
    }
}

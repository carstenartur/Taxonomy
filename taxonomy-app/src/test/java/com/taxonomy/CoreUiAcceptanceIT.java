package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real browser acceptance test for the normal application path.
 *
 * <p>No DOM result injection, fallback HTML, forced status badges, or direct API
 * state manipulation is permitted here. Documentation screenshot fixtures live
 * in {@link ScreenshotGeneratorIT} and are intentionally a separate concern.</p>
 */
@Tag("ui-acceptance")
@Tag("docker-required")
@Tag("ci-app-shard-browser")
class CoreUiAcceptanceIT {

    private static final String ADMIN_PASSWORD = "Ui-Acceptance-Password-2026!";

    private static Network network;
    private static GenericContainer<?> application;
    private static ContainerTestUtils.BrowserSession browserSession;
    private static RemoteWebDriver driver;
    private static WebDriverWait wait;

    @BeforeAll
    static void startApplicationAndBrowser() {
        network = Network.newNetwork();
        application = ContainerTestUtils.appContainer(network)
                .withEnv("TAXONOMY_ADMIN_PASSWORD", ADMIN_PASSWORD)
                .withEnv("TAXONOMY_REQUIRE_PASSWORD_CHANGE", "false")
                .withEnv("TAXONOMY_EMBEDDING_ENABLED", "false")
                .withEnv("TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD", "false")
                .withEnv("TAXONOMY_INIT_ASYNC", "true")
                .withEnv("TAXONOMY_THYMELEAF_CACHE", "false");
        application.start();

        browserSession = ContainerTestUtils.startBrowser(network);
        driver = browserSession.driver();
        driver.manage().window().setSize(new Dimension(1440, 1000));
        wait = new WebDriverWait(driver, Duration.ofSeconds(120));
    }

    @AfterAll
    static void stopApplicationAndBrowser() throws Exception {
        ContainerTestUtils.closeAll(browserSession, application, network);
    }

    @Test
    void authenticatedUserCanLoadAndNavigateTheRealApplication() {
        driver.get(ContainerTestUtils.APP_ORIGIN + "/");

        wait.until(ExpectedConditions.presenceOfElementLocated(By.name("username")))
                .sendKeys("admin");
        driver.findElement(By.name("password")).sendKeys(ADMIN_PASSWORD);
        driver.findElement(By.cssSelector("form")).submit();

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("applicationWorkspaces")));
        dismissOnboardingWhenShown();
        wait.until(d -> {
            WebElement tree = d.findElement(By.id("taxonomyTree"));
            String rendered = tree.getAttribute("data-view-rendered");
            return rendered != null && !rendered.isBlank();
        });

        assertThat(driver.getPageSource())
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("cdnjs.cloudflare.com")
                .contains("/webjars/bootstrap/")
                .contains("taxonomy-ergonomics.css");

        WebElement activeTab = driver.findElement(
                By.cssSelector("#applicationRequirementLink"));
        assertThat(activeTab.getAttribute("aria-current")).isEqualTo("page");

        activeTab.click();

        By architectureTabSelector =
                By.cssSelector("#applicationArchitectureLink");
        WebElement architectureTab = driver.findElement(architectureTabSelector);
        architectureTab.sendKeys(Keys.ENTER);
        wait.until(ExpectedConditions.attributeToBe(architectureTabSelector, "aria-current", "page"));
        assertThat(driver.findElement(By.id("tab-architecture")).isDisplayed()).isTrue();

        WebElement settings = driver.findElement(
                By.cssSelector("#applicationTools > summary"));
        settings.click();
        WebElement adminLink = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#applicationTools #adminNavTab")));
        adminLink.click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("tab-admin")));

        settings.click();
        WebElement preferencesLink = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#applicationTools #preferencesNavTab")));
        preferencesLink.click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("tab-preferences")));
    }

    private void dismissOnboardingWhenShown() {
        List<WebElement> dismissButtons = driver.findElements(By.id("onboardingDismiss"));
        if (dismissButtons.isEmpty() || !dismissButtons.getFirst().isDisplayed()) {
            return;
        }
        dismissButtons.getFirst().click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
    }
}

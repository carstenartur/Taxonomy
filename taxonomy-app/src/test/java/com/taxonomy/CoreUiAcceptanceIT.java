package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real browser acceptance test for the normal application path.
 *
 * <p>No DOM result injection, fallback HTML, forced status badges, or direct API
 * state manipulation is permitted here. Documentation screenshot fixtures live
 * in {@link ScreenshotGeneratorIT} and are intentionally a separate concern.</p>
 */
@Tag("ui-acceptance")
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

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("mainNavTabs")));
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
                By.cssSelector("#mainNavTabs .nav-link[data-page='analyze']"));
        assertThat(activeTab.getAttribute("role")).isEqualTo("tab");
        assertThat(activeTab.getAttribute("aria-selected")).isEqualTo("true");

        activeTab.click();
        activeTab.sendKeys(Keys.ARROW_RIGHT);
        By architectureTabSelector =
                By.cssSelector("#mainNavTabs .nav-link[data-page='architecture']");
        wait.until(ExpectedConditions.attributeToBe(
                architectureTabSelector, "aria-selected", "true"));
        WebElement architectureTab = driver.findElement(architectureTabSelector);
        assertThat(architectureTab.getAttribute("tabindex")).isEqualTo("0");
        assertThat(driver.findElement(By.id("tab-architecture")).isDisplayed()).isTrue();

        WebElement adminTab = driver.findElement(
                By.cssSelector("#mainNavTabs .nav-link[data-page='admin']"));
        assertThat(adminTab.isDisplayed()).isTrue();
    }
}

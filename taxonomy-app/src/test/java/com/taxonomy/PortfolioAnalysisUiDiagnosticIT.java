package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused real-browser contract for accepted analysis-job discovery.
 *
 * <p>The full workflow remains in {@link PortfolioUiAcceptanceIT}; this focused
 * contract reports server and browser state immediately when the accepted job
 * cannot be rendered.</p>
 */
@Tag("ui-acceptance")
class PortfolioAnalysisUiDiagnosticIT {

    private static final String ADMIN_PASSWORD = "Portfolio-Analysis-Diagnostic-2026!";

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
                .withEnv("TAXONOMY_THYMELEAF_CACHE", "false")
                .withEnv("LLM_MOCK", "true");
        application.start();
        browserSession = ContainerTestUtils.startBrowser(network);
        driver = browserSession.driver();
        driver.manage().window().setSize(new Dimension(1440, 1000));
        wait = new WebDriverWait(driver, Duration.ofSeconds(60));
        login();
    }

    @AfterAll
    static void stopApplicationAndBrowser() throws Exception {
        ContainerTestUtils.closeAll(browserSession, application, network);
    }

    @Test
    void acceptedAnalysisJobIsRenderedFromTheServerResource() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        driver.get(ContainerTestUtils.APP_ORIGIN + "/projects?lang=en");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("portfolioMain")));

        @SuppressWarnings("unchecked")
        Map<String, Object> setup = (Map<String, Object>) driver.executeAsyncScript("""
                const suffix = arguments[0];
                const done = arguments[arguments.length - 1];
                (async function () {
                    const token = document.querySelector('meta[name="_csrf"]')?.content;
                    const headerName = document.querySelector('meta[name="_csrf_header"]')?.content
                        || 'X-CSRF-TOKEN';
                    const headers = { Accept: 'application/json', 'Content-Type': 'application/json' };
                    if (token) headers[headerName] = token;
                    async function post(url, body) {
                        const response = await fetch(url, {
                            method: 'POST', headers: headers, credentials: 'same-origin',
                            body: JSON.stringify(body)
                        });
                        const text = await response.text();
                        if (!response.ok) throw new Error(url + ' -> HTTP ' + response.status + ': ' + text);
                        return JSON.parse(text);
                    }
                    const project = await post('/api/projects', {
                        projectKey: 'P-DIAG-' + suffix,
                        title: 'Analysis browser diagnostic'
                    });
                    const requirement = await post('/api/projects/' + project.id + '/requirements', {
                        requirementKey: 'REQ-DIAG-' + suffix,
                        title: 'Traceable secure communication',
                        text: 'Provide traceable secure communication with auditable decisions.',
                        requirementType: 'SECURITY'
                    });
                    localStorage.setItem('taxonomy.portfolio.projectId', String(project.id));
                    done({ projectId: project.id, requirementId: requirement.id,
                        projectKey: project.projectKey });
                }()).catch(error => done({ error: String(error && error.stack || error) }));
                """, suffix);
        assertThat(setup).doesNotContainKey("error");
        long projectId = ((Number) setup.get("projectId")).longValue();

        driver.navigate().refresh();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(
                By.id("selectedProjectKey"), String.valueOf(setup.get("projectKey"))));
        wait.until(ExpectedConditions.textToBePresentInElementLocated(
                By.cssSelector("#requirementsTable tbody"), "REQ-DIAG-" + suffix));
        WebElement analyze = wait.until(ExpectedConditions.elementToBeClickable(By.id("analyzeAllBtn")));
        analyze.click();

        WebDriverWait discoveryWait = new WebDriverWait(driver, Duration.ofSeconds(12));
        boolean discovered;
        try {
            discoveryWait.until(browser -> !browser.findElements(
                    By.cssSelector("#portfolioJobList .portfolio-job")).isEmpty());
            discovered = true;
        } catch (org.openqa.selenium.TimeoutException timeout) {
            discovered = false;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) driver.executeAsyncScript("""
                const projectId = arguments[0];
                const done = arguments[arguments.length - 1];
                (async function () {
                    const response = await fetch('/api/projects/' + projectId + '/analysis-jobs', {
                        headers: { Accept: 'application/json' }, credentials: 'same-origin', cache: 'no-store'
                    });
                    done({
                        serverStatus: response.status,
                        serverBody: await response.text(),
                        errorText: document.getElementById('portfolioError')?.textContent || '',
                        errorVisible: !document.getElementById('portfolioError')?.classList.contains('d-none'),
                        infoText: document.getElementById('portfolioInfo')?.textContent || '',
                        jobListText: document.getElementById('portfolioJobList')?.textContent || '',
                        storedJobs: localStorage.getItem('taxonomy.portfolio.analysisJobs.v2') || '',
                        storedProjectId: localStorage.getItem('taxonomy.portfolio.projectId') || '',
                        bridgeType: typeof window.taxonomyPortfolioRegisterJob,
                        analyzeDisabled: document.getElementById('analyzeAllBtn')?.disabled === true
                    });
                }()).catch(error => done({ error: String(error && error.stack || error) }));
                """, projectId);

        assertThat(discovered)
                .as("Analysis job was not rendered. diagnostics=%s browserLogs=%s",
                        diagnostics, browserLogs())
                .isTrue();
    }

    private static List<String> browserLogs() {
        try {
            return driver.manage().logs().get(LogType.BROWSER).getAll().stream()
                    .map(Object::toString)
                    .toList();
        } catch (RuntimeException unsupported) {
            return List.of("Browser logs unavailable: " + unsupported.getMessage());
        }
    }

    private static void login() {
        driver.get(ContainerTestUtils.APP_ORIGIN + "/login");
        wait.until(ExpectedConditions.presenceOfElementLocated(By.name("username")))
                .sendKeys("admin");
        driver.findElement(By.name("password")).sendKeys(ADMIN_PASSWORD);
        driver.findElement(By.cssSelector("form")).submit();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("mainNavTabs")));
        List<WebElement> dismissButtons = driver.findElements(By.id("onboardingDismiss"));
        if (!dismissButtons.isEmpty() && dismissButtons.getFirst().isDisplayed()) {
            dismissButtons.getFirst().click();
            wait.until(ExpectedConditions.invisibilityOfElementLocated(By.id("onboardingOverlay")));
        }
    }
}

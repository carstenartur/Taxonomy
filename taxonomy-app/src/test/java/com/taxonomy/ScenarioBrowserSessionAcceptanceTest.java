package com.taxonomy;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SourceReference;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.testsupport.BrowserSession;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real application, browser, login form and servlet expiry; no analysis is submitted. */
@Tag("browser")
@Tag("ci-app-shard-browser")
@SpringBootTest(classes = TaxonomyApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:hsqldb:mem:scenario-browser-session;shutdown=true",
        "embedding.enabled=false", "embedding.allow-download=false", "llm.mock=true",
        "taxonomy.admin-password=" + ScenarioArchitectureAcceptanceTest.PASSWORD,
        "taxonomy.security.require-password-change=false"
})
@Import(ScenarioBrowserSessionAcceptanceTest.SessionProbeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScenarioBrowserSessionAcceptanceTest {
    private static final String REQUIREMENT_TEXT =
            "Keep the saved requirement available when an architect returns after a long analysis.";
    private static final String ORIGINAL_SOURCE =
            "Original review note\n  Preserve this source fragment and the existing requirement version.";

    @LocalServerPort int port;
    @TempDir Path directory;
    @Autowired ProjectPortfolioService projects;
    @Autowired WorkspaceContextResolver workspaceContexts;
    @Autowired RequirementAnalysisJobRepository jobs;
    @Autowired RequirementAnalysisSnapshotRepository snapshots;
    @Autowired SessionProbe probe;

    @Test
    void refreshReusesLiveSessionAndRecoversActualExpiryWithoutChangingRequirementOrStartingAnalysis() throws Exception {
        try (var session = BrowserSession.open(port, directory.resolve("downloads"))) {
            var browser = session.driver();
            var wait = new WebDriverWait(browser, Duration.ofSeconds(20));
            browser.get(session.origin() + "/login");
            var form = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("loginForm")));
            form.findElement(By.name("username")).sendKeys("admin");
            form.findElement(By.name("password")).sendKeys(ScenarioArchitectureAcceptanceTest.PASSWORD);
            form.findElement(By.cssSelector("button[type='submit']")).click();
            wait.until(driver -> !URI.create(driver.getCurrentUrl()).getPath().equals("/login"));

            var repository = workspaceContexts.resolveRepositoryContextForUser("admin");
            var context = new WorkspaceContext(repository.username(), repository.workspaceId(),
                    repository.branch(), repository.repositoryId());
            var project = projects.createProject(new CreateProjectRequest("SESSION-REFRESH", "Session refresh",
                    "Real browser authentication regression", null, null, null, null, null), "admin", context);
            var created = projects.createRequirement(project.id(), new CreateRequirementRequest(
                    "SESSION-001", "Return to the retained requirement", REQUIREMENT_TEXT,
                    null, null, null, null, null, "admin", "Initial source capture",
                    new SourceReference(null, null, List.of(), "Review notes", 1, ORIGINAL_SOURCE)), "admin", context);
            var requirement = projects.getRequirement(project.id(), created.id(), "admin", context);
            String operationUrl = session.origin() + "/projects/" + project.id() + "/requirements/"
                    + requirement.id() + "?lang=en#requirementCopilotCard";
            probe.operationPath = URI.create(operationUrl).getPath();
            browser.get(operationUrl);
            assertRequirementPage(browser, wait, operationUrl, requirement);
            assertUnchangedRequirement(requirement, context);
            HttpSession authenticated = authenticatedSession(browser);
            String originalSessionId = authenticated.getId();
            assertThat(probe.loginSubmissions.get()).isEqualTo(1);
            int loginPages = probe.loginPages.get();

            ScenarioBrowserWalkthrough.refreshAuthenticatedOperation(browser, wait, operationUrl);

            assertRequirementPage(browser, wait, operationUrl, requirement);
            assertThat(sessionId(browser).equals(originalSessionId))
                    .as("Refreshing a live browser session preserves its session identity").isTrue();
            assertThat(probe.loginSubmissions.get()).as("A live session needs no repeated login").isEqualTo(1);
            assertThat(probe.loginPages.get()).isEqualTo(loginPages);
            assertUnchangedRequirement(requirement, context);

            // Invalidate the actual container session, as idle expiry does, without sleeping
            // or changing the application's timeout, filters, credentials or security rules.
            authenticated.invalidate();
            assertThat(probe.sessions.containsKey(originalSessionId))
                    .as("The container notified the listener that the authenticated session was destroyed").isFalse();

            ScenarioBrowserWalkthrough.refreshAuthenticatedOperation(browser, wait, operationUrl);

            assertThat(probe.expiredPageRedirects.get())
                    .as("The expired protected-page refresh really redirected to production /login").isEqualTo(1);
            assertThat(probe.loginPages.get())
                    .as("The production login page was rendered after actual session expiry").isGreaterThan(loginPages);
            assertRequirementPage(browser, wait, operationUrl, requirement);
            assertThat(probe.loginSubmissions.get())
                    .as("Recovery submits exactly one additional real form login").isEqualTo(2);
            assertThat(authenticatedSession(browser).getId().equals(originalSessionId))
                    .as("The expired session is replaced by a newly authenticated servlet session").isFalse();
            assertUnchangedRequirement(requirement, context);
            assertThat(probe.portfolioWrites)
                    .as("Refreshing and signing in cannot submit a requirement mutation or another analysis").isEmpty();
        }
    }

    private void assertUnchangedRequirement(RequirementView original, WorkspaceContext context) {
        assertThat(projects.getRequirement(original.projectId(), original.id(), "admin", context))
                .as("The persisted requirement, immutable version and original source are unchanged").isEqualTo(original);
        assertThat(jobs.count()).as("No analysis job was submitted").isZero();
        assertThat(snapshots.count()).as("No analysis result was fabricated or regenerated").isZero();
    }

    private static void assertRequirementPage(WebDriver browser, WebDriverWait wait,
                                              String operationUrl, RequirementView requirement) {
        // Fail at the wrong route immediately, rather than waiting for controls on a login page.
        assertThat(browser.getCurrentUrl()).as("Return to the exact retained requirement URL").isEqualTo(operationUrl);
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("requirementKey"), requirement.requirementKey()));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("copilotRun")));
        assertThat(browser.findElements(By.id("copilotOpenResult"))).hasSize(1);
        assertThat(browser.findElements(By.id("loginForm"))).isEmpty();
        assertThat(browser.findElement(By.id("currentText")).getDomProperty("textContent")).isEqualTo(REQUIREMENT_TEXT);
        assertThat(browser.findElement(By.id("sourceText")).getDomProperty("textContent")).isEqualTo(ORIGINAL_SOURCE);
        assertThat(browser.findElement(By.id("detailError")).isDisplayed()).isFalse();
    }

    private HttpSession authenticatedSession(WebDriver browser) {
        HttpSession session = probe.sessions.get(sessionId(browser));
        assertThat(session).as("The browser cookie identifies an actual servlet session").isNotNull();
        var security = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(security).as("The real login stored Spring Security's context in the session").isNotNull();
        assertThat(security.getAuthentication().isAuthenticated()).isTrue();
        assertThat(security.getAuthentication().getName()).isEqualTo("admin");
        return session;
    }

    private static String sessionId(WebDriver browser) {
        var cookie = browser.manage().getCookieNamed("JSESSIONID");
        assertThat(cookie).as("Production form login creates its regular session cookie").isNotNull();
        return cookie.getValue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SessionProbeConfiguration {
        // Spring Boot registers servlet listener beans, including fixation ID-change events.
        @Bean SessionProbe scenarioSessionProbe() { return new SessionProbe(); }

        @Bean FilterRegistrationBean<Filter> scenarioSessionRequestProbe(SessionProbe probe) {
            Filter filter = (request, response, chain) -> {
                var http = (HttpServletRequest) request;
                var result = (HttpServletResponse) response;
                String path = http.getRequestURI();
                String method = http.getMethod();
                if (path.equals("/login") && method.equals("POST")) probe.loginSubmissions.incrementAndGet();
                if (path.startsWith("/api/projects/") && !List.of("GET", "HEAD", "OPTIONS").contains(method)) {
                    probe.portfolioWrites.add(method + " " + path);
                }
                chain.doFilter(request, response);
                if (path.equals("/login") && method.equals("GET") && result.getStatus() == 200) {
                    probe.loginPages.incrementAndGet();
                }
                String redirect = result.getHeader("Location");
                if (path.equals(probe.operationPath) && method.equals("GET") && result.getStatus() == 302
                        && redirect != null && URI.create(redirect).getPath().equals("/login")) {
                    probe.expiredPageRedirects.incrementAndGet();
                }
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }

    /** Observes production traffic and retains actual sessions; it serves no fixture endpoints or HTML. */
    static final class SessionProbe implements HttpSessionListener, HttpSessionIdListener {
        final Map<String, HttpSession> sessions = new ConcurrentHashMap<>();
        final AtomicInteger loginSubmissions = new AtomicInteger();
        final AtomicInteger loginPages = new AtomicInteger();
        final AtomicInteger expiredPageRedirects = new AtomicInteger();
        final List<String> portfolioWrites = new CopyOnWriteArrayList<>();
        volatile String operationPath = "";

        @Override public void sessionCreated(HttpSessionEvent event) {
            sessions.put(event.getSession().getId(), event.getSession());
        }

        @Override public void sessionDestroyed(HttpSessionEvent event) {
            sessions.remove(event.getSession().getId());
        }

        @Override public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
            sessions.remove(oldSessionId);
            sessions.put(event.getSession().getId(), event.getSession());
        }
    }
}

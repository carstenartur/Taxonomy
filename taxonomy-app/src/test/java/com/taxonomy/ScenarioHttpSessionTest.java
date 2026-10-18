package com.taxonomy;

import com.taxonomy.security.config.AuthorizationRulesConfigurer;
import com.taxonomy.security.config.BrowserSessionConfiguration;
import com.taxonomy.security.config.PasswordChangeRequiredFilter;
import com.taxonomy.security.config.SecurityConfig;
import com.taxonomy.security.controller.AccountContextController;
import com.taxonomy.security.webdav.WebDavApplicationCredentialFilter;
import com.taxonomy.security.webdav.WebDavApplicationCredentialService;
import com.taxonomy.shared.config.RateLimitFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Actual HTTP, production form/session/CSRF rules and BCrypt; only account storage and endpoint data are fixtures. */
class ScenarioHttpSessionTest {
    @TempDir Path directory;
    private Tomcat server;
    private AnnotationConfigWebApplicationContext context;
    private ScenarioArchitectureAcceptanceTest driver;
    private Probe probe;
    private CountingPasswordEncoder passwords;

    @BeforeEach
    void openActualSecurityServer() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.register(Fixture.class);
        server = new Tomcat();
        server.setBaseDir(directory.resolve("tomcat").toString());
        server.setPort(0);
        var web = server.addContext("", Files.createDirectory(directory.resolve("web")).toString());
        web.addServletContainerInitializer((classes, servletContext) -> {
            context.setServletContext(servletContext);
            context.refresh();
            servletContext.addListener(new HttpSessionEventPublisher());
            var security = servletContext.addFilter("springSecurityFilterChain",
                    new DelegatingFilterProxy("springSecurityFilterChain", context));
            security.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            var dispatcher = servletContext.addServlet("dispatcher", new DispatcherServlet(context));
            dispatcher.addMapping("/");
            dispatcher.setLoadOnStartup(1);
        }, null);
        server.getConnector().setProperty("address", "127.0.0.1");
        server.start();
        probe = context.getBean(Probe.class);
        passwords = context.getBean(CountingPasswordEncoder.class);
        driver = new ScenarioArchitectureAcceptanceTest();
        driver.port = server.getConnector().getLocalPort();
        assertThat(driver.port).as("The real HTTP security fixture is listening").isPositive();
    }

    @AfterEach
    void closeServerAndClient() throws Exception {
        if (driver != null) driver.http.close();
        if (server != null) { server.stop(); server.destroy(); }
        if (context != null) context.close();
    }

    @Test
    void actualScenarioRequestsReuseOneAuthenticatedSessionForReadsAndWrites() throws Exception {
        var first = driver.get("/api/projects/1/session-probe");
        var values = new ArrayList<JsonNode>();
        values.add(first);
        for (int index = 0; index < 4; index++) values.add(driver.get("/api/projects/1/session-probe"));
        values.add(driver.post("/api/projects/1/session-probe", Map.of("instruction", "continue once"), 200));

        assertThat(passwords.matches.get()).as("Repeated status reads do not repeat BCrypt authentication").isEqualTo(1);
        assertThat(values).allSatisfy(value -> {
            assertThat(value.path("principal").asText()).isEqualTo("admin");
            assertThat(value.path("sessionId").asText()).isNotBlank().isEqualTo(first.path("sessionId").asText());
        });
        assertThat(probe.requests).hasSize(6).allSatisfy(request -> assertThat(request.authorization()).isNull());
        assertThat(probe.requests.getLast().csrfHeader()).isNotBlank();
        assertThat(probe.writes.get()).isEqualTo(1);
        assertThat(context.getBean(SessionRegistry.class).getSessionInformation(first.path("sessionId").asText())).isNotNull();
        assertThat(probe.loginSessions).hasSize(2);
        assertThat(probe.loginSessions.getFirst()).isNotEqualTo(probe.loginSessions.getLast());
        assertThat(probe.loginSessions.getLast()).isEqualTo(first.path("sessionId").asText());
    }

    @Test
    void concurrentInitialStatusReadersPerformOnlyOneFormLogin() {
        var readers = java.util.stream.IntStream.range(0, 3).mapToObj(index ->
                CompletableFuture.supplyAsync(() -> {
                    try { return driver.get("/api/projects/1/session-probe"); }
                    catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                })).toList();
        var values = readers.stream().map(CompletableFuture::join).toList();
        assertThat(passwords.matches.get()).isEqualTo(1);
        assertThat(values).allSatisfy(value -> assertThat(value.path("sessionId").asText())
                .isNotBlank().isEqualTo(values.getFirst().path("sessionId").asText()));
        assertThat(probe.requests).hasSize(3).allSatisfy(request -> assertThat(request.authorization()).isNull());
    }

    @Test
    void sessionWritesStillRequireTheRealCsrfTokenAndKeepEndpointScopeChecks() throws Exception {
        driver.get("/api/projects/1/session-probe");
        var uri = URI.create("http://localhost:" + driver.port + "/api/projects/1/session-probe");
        for (String token : List.of("", "invalid-csrf")) {
            var request = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"));
            if (!token.isEmpty()) request.header("X-CSRF-TOKEN", token);
            assertThat(driver.http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
        assertThat(probe.writes.get()).isZero();
        driver.post("/api/projects/1/session-probe", Map.of("instruction", "continue once"), 200);
        driver.request("POST", "/api/projects/2/session-probe", Map.of("instruction", "foreign project"), 404);
        assertThat(probe.writes.get()).isEqualTo(1);
        assertThat(passwords.matches.get()).isEqualTo(1);
        assertThat(probe.requests).allSatisfy(request -> assertThat(request.authorization()).isNull());
    }

    @Test
    void anExpiredSessionIsVisibleAndNeverAutomaticallyReauthenticatedOrResubmitted() throws Exception {
        driver.get("/api/projects/1/session-probe");
        assertThat(probe.session).as("A regular servlet session was authenticated").isNotNull();
        probe.session.invalidate();
        assertThatThrownBy(() -> driver.post("/api/projects/1/session-probe", Map.of("instruction", "continue once"), 200))
                .isInstanceOf(AssertionError.class);
        assertThat(passwords.matches.get()).isEqualTo(1);
        assertThat(probe.writes.get()).isZero();
        assertThat(probe.requests).hasSize(1);
    }

    @Test
    void rejectedCredentialsRemainVisibleWithoutAnyScenarioMutation() {
        var users = context.getBean(InMemoryUserDetailsManager.class);
        users.deleteUser("admin");
        users.createUser(User.withUsername("admin").password(passwords.encode("different-test-password"))
                .roles("ADMIN").build());
        assertThatThrownBy(() -> driver.post("/api/projects/1/session-probe", Map.of("instruction", "continue once"), 200))
                .isInstanceOf(AssertionError.class);
        assertThat(probe.requests).isEmpty();
        assertThat(probe.writes.get()).isZero();
        assertThat(passwords.matches.get()).isEqualTo(1);
    }

    @Test
    void anonymousClientsRemainUnauthenticated() throws Exception {
        try (var anonymous = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + driver.port + "/api/projects/1/session-probe"))
                    .header("Accept", "application/json").GET().build();
            assertThat(anonymous.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        assertThat(probe.requests).isEmpty();
        assertThat(passwords.matches.get()).isZero();
    }

    @Test
    void nativeEditorAndMultipartRequestsKeepTheirContractsInTheSameAuthenticatedSession() throws Exception {
        ScenarioIntegrationWalkthrough.editorRequest(driver, "/api/projects/1/session-probe",
                Map.of("instruction", "review once"), 7);
        byte[] file = "<model>retained native exchange bytes</model>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ScenarioIntegrationWalkthrough.uploadXmi(driver, "/api/integrations/session-probe",
                "?workspaceId=session-test", file);

        assertThat(passwords.matches.get()).isEqualTo(1);
        assertThat(probe.writes.get()).isEqualTo(2);
        assertThat(probe.requests).hasSize(3).allSatisfy(request -> assertThat(request.authorization()).isNull());
        assertThat(probe.requests.getFirst().csrfHeader()).isNotBlank();
        assertThat(probe.requests.getFirst().ifMatch()).isEqualTo("\"workspace-revision-7\"");
        assertThat(probe.requests.getLast().csrfHeader()).isNotBlank();
        assertThat(probe.requests.getLast().query()).isEqualTo("workspaceId=session-test");
        assertThat(probe.multipartBodies).singleElement().asString()
                .contains("<model>retained native exchange bytes</model>", "\"completeScope\":true", "scope-v1");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    @Import({SecurityConfig.class, BrowserSessionConfiguration.class, AuthorizationRulesConfigurer.class,
            AccountContextController.class, Probe.class})
    static class Fixture {
        @Bean CountingPasswordEncoder passwords() { return new CountingPasswordEncoder(); }
        @Bean InMemoryUserDetailsManager users(CountingPasswordEncoder passwords) {
            return new InMemoryUserDetailsManager(User.withUsername("admin")
                    .password(passwords.encode(ScenarioArchitectureAcceptanceTest.PASSWORD)).roles("ADMIN").build());
        }
        @Bean PasswordChangeRequiredFilter passwordChange() { return new PasswordChangeRequiredFilter(null, false); }
        @Bean RateLimitFilter rateLimit() { return new RateLimitFilter(); }
        @Bean WebDavApplicationCredentialFilter webDav() {
            return new WebDavApplicationCredentialFilter(mock(WebDavApplicationCredentialService.class));
        }
    }

    static final class CountingPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = new BCryptPasswordEncoder();
        final AtomicInteger matches = new AtomicInteger();
        public String encode(CharSequence raw) { return delegate.encode(raw); }
        public boolean matches(CharSequence raw, String encoded) { matches.incrementAndGet(); return delegate.matches(raw, encoded); }
    }

    record ObservedRequest(String authorization, String csrfHeader, String ifMatch, String query) { }

    @RestController
    static class Probe {
        final List<ObservedRequest> requests = new CopyOnWriteArrayList<>();
        final List<String> loginSessions = new CopyOnWriteArrayList<>();
        final List<String> multipartBodies = new CopyOnWriteArrayList<>();
        final AtomicInteger writes = new AtomicInteger();
        volatile HttpSession session;

        @GetMapping(value = "/login", produces = "text/html")
        String login(HttpServletRequest request) {
            var token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            String csrf = token.getToken();
            loginSessions.add(request.getSession(false).getId());
            return "<html><form action=\"/login\" method=\"post\"><input type=\"hidden\" name=\""
                    + token.getParameterName() + "\" value=\"" + csrf + "\"></form></html>";
        }
        @GetMapping("/api/projects/{projectId}/session-probe")
        Map<String, String> read(@PathVariable long projectId, Authentication authentication, HttpServletRequest request) {
            if (projectId != 1) throw new ResponseStatusException(NOT_FOUND, "Project outside this fixture's scope");
            observe(request);
            return Map.of("principal", authentication.getName(), "sessionId", session == null ? "" : session.getId());
        }
        @PostMapping("/api/projects/{projectId}/session-probe")
        Map<String, String> write(@PathVariable long projectId, @RequestBody Map<String, Object> body,
                                 Authentication authentication, HttpServletRequest request) {
            var result = read(projectId, authentication, request);
            writes.incrementAndGet();
            return result;
        }
        @GetMapping("/api/integrations/session-probe")
        Map<String, Object> integration(HttpServletRequest request) {
            requireWorkspace(request);
            observe(request);
            return Map.of("current", Map.of("version", "scope-v1"));
        }
        @PostMapping(value = "/api/integrations/session-probe/previews", consumes = "multipart/form-data")
        Map<String, Object> upload(@RequestBody byte[] body, HttpServletRequest request) {
            requireWorkspace(request);
            observe(request);
            multipartBodies.add(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            writes.incrementAndGet();
            return Map.of("status", "PREVIEWED");
        }
        private void observe(HttpServletRequest request) {
            session = request.getSession(false);
            requests.add(new ObservedRequest(request.getHeader("Authorization"), request.getHeader("X-CSRF-TOKEN"),
                    request.getHeader("If-Match"), request.getQueryString()));
        }
        private void requireWorkspace(HttpServletRequest request) {
            if (!"workspaceId=session-test".equals(request.getQueryString())) {
                throw new ResponseStatusException(NOT_FOUND, "Workspace outside this fixture's scope");
            }
        }
    }
}

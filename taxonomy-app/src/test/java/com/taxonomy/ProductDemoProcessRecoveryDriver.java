package com.taxonomy;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.composition.demo.ProductDemoService;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.CopilotJobControlService;
import com.taxonomy.portfolio.service.PortfolioAnalysisClaimPersistenceService;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioAnalysisWorkQueue;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.PortfolioAnalysisWorkQueue.WorkItem;
import com.taxonomy.shared.features.FeatureAssembly;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Executes each side of an actual application process death against the same file-backed database. */
public final class ProductDemoProcessRecoveryDriver {
    static final String COMPLETE = "PRODUCT_DEMO_PORTFOLIO_RECOVERY_OK";
    private static final String ACTOR = "admin";
    private static final String PASSWORD = "Disposable-Demo-Recovery-Fixture-42";
    private static final int CLAIM_SECONDS = 60;

    private record Checkpoint(WorkspaceContext scope, String runId, String scenario, String provider,
                              WorkItem demo, WorkItem normal, Instant committedAt) { }

    private final ConfigurableApplicationContext app;
    private final ObjectMapper json;
    private final PortfolioAnalysisPersistenceService store;
    private final ProductDemoService demos;

    private ProductDemoProcessRecoveryDriver(ConfigurableApplicationContext app) {
        this.app = app;
        this.json = app.getBean(ObjectMapper.class);
        this.store = app.getBean(PortfolioAnalysisPersistenceService.class);
        this.demos = app.getBean(ProductDemoService.class);
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]);
        var traffic = new AtomicInteger();
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/", exchange -> {
            try (exchange) {
                traffic.incrementAndGet();
                exchange.sendResponseHeaders(500, -1);
            }
        });
        provider.start();
        FeatureAssembly.discover(Thread.currentThread().getContextClassLoader());
        try (var app = new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--spring.datasource.url=jdbc:hsqldb:file:" + directory.resolve("portfolio") + ";shutdown=true;hsqldb.write_delay=false",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--server.address=127.0.0.1", "--server.port=0", "--taxonomy.init.async=false", "--embedding.enabled=false",
                "--taxonomy.admin-password=" + PASSWORD, "--taxonomy.security.require-password-change=false",
                "--taxonomy.portfolio.analysis-claim-timeout-seconds=" + CLAIM_SECONDS,
                "--llm.mock=false", "--llm.provider=CUSTOM_OPENAI", "--custom.llm.model=controlled-recovery",
                "--custom.llm.api.key=disposable-fixture", "--custom.llm.url=http://127.0.0.1:"
                        + provider.getAddress().getPort() + "/v1/chat/completions")) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(ACTOR, null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_ARCHITECT"),
                            new SimpleGrantedAuthority("ROLE_USER"))));
            var driver = new ProductDemoProcessRecoveryDriver(app);
            Path checkpoint = directory.resolve("claims.json");
            switch (args[0]) {
                case "produce" -> {
                    Files.writeString(checkpoint, driver.json.writeValueAsString(driver.claimBoth()));
                    assertThat(traffic.get()).isZero();
                    Files.createFile(directory.resolve("claims-committed"));
                    new CountDownLatch(1).await();
                }
                case "recover" -> {
                    driver.recover(driver.json.readValue(Files.readString(checkpoint), Checkpoint.class));
                    assertThat(traffic.get()).isZero();
                    System.out.println(COMPLETE);
                }
                default -> throw new IllegalArgumentException(args[0]);
            }
        } finally {
            provider.stop(0);
        }
    }

    private Checkpoint claimBoth() throws java.io.IOException {
        var repo = app.getBean(DslGitRepositoryFactory.class).getSystemRepository();
        if (repo.getHeadCommit("draft") == null) {
            repo.commitDsl("draft", app.getBean(TaxDslExportService.class).newProject("default"), "system", "Recovery fixture");
        }
        var manager = app.getBean(WorkspaceManager.class);
        var workspace = manager.createWorkspace(ACTOR, "Recovery proof", "Portfolio owner recovery");
        manager.provisionWorkspaceRepository(ACTOR, workspace.getWorkspaceId());
        manager.switchWorkspace(ACTOR, workspace.getWorkspaceId());
        var scope = app.getBean(WorkspaceResolver.class).resolveCurrentContext();
        var projects = app.getBean(ProjectPortfolioService.class);
        var project = projects.createProject(new CreateProjectRequest("NORMAL-RECOVERY", "Normal recovery", null,
                ProjectStatus.ACTIVE, null, null, null, null), ACTOR, scope);
        var requirement = projects.createRequirement(project.id(), new CreateRequirementRequest("NORMAL-REQ",
                "Normal inspection", "Inspect field equipment.", RequirementStatus.APPROVED, 50, Criticality.HIGH,
                RequirementType.FUNCTIONAL, ReviewStatus.CONFIRMED, ACTOR, "Initial", null), ACTOR, scope);
        var demo = demos.start(null, ACTOR, scope);
        WorkItem normal = claim(project.id(), requirement.id(), "CUSTOM_OPENAI", scope);
        WorkItem replay = claim(demo.projectId(), demo.requirementId(), demo.activeProvider(), scope);
        assertThat(replay.requirementText()).isEqualTo(demo.requirementText());
        return new Checkpoint(scope, demo.id(), demo.scenarioFingerprint(), demo.activeProvider(), replay, normal, Instant.now());
    }

    private WorkItem claim(Long project, Long requirement, String provider, WorkspaceContext scope) {
        var job = store.createOrReuseJob(project, List.of(requirement), provider, 25, null, ACTOR, scope);
        String key = PortfolioScope.key(ACTOR, scope);
        var item = app.getBean(PortfolioAnalysisWorkQueue.class).pending(job.id(), project, key).getFirst();
        store.markJobRunning(job.id(), project, key);
        assertThat(item.attempt()).isOne();
        assertThat(store.getJob(job.id(), project, ACTOR, scope).status()).isEqualTo(AnalysisStatus.RUNNING);
        return item;
    }

    private void recover(Checkpoint checkpoint) throws Exception {
        var scope = checkpoint.scope();
        var normal = checkpoint.normal();
        var demo = checkpoint.demo();
        var restored = demos.get(checkpoint.runId(), ACTOR, scope);
        assertThat(restored.scenarioFingerprint()).isEqualTo(checkpoint.scenario());
        assertThat(restored.currentRequirementVersion()).isOne();
        assertThat(restored.requirementText()).isEqualTo(demo.requirementText());
        assertThat(restored.activeProvider()).isEqualTo(checkpoint.provider());
        var controls = app.getBean(CopilotJobControlService.class);
        var cancelled = controls.cancel(normal.jobId(), normal.projectId(), ACTOR, scope);
        assertThat(cancelled.status()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(controls.cancel(normal.jobId(), normal.projectId(), ACTOR, scope).completedAt()).isEqualTo(cancelled.completedAt());
        assertExpiredOwner(normal, scope);
        assertThat(store.getJob(demo.jobId(), demo.projectId(), ACTOR, scope).status()).isEqualTo(AnalysisStatus.RUNNING);

        Duration remaining = Duration.between(Instant.now(), checkpoint.committedAt().plusSeconds(CLAIM_SECONDS + 1));
        if (remaining.isPositive()) Thread.sleep(remaining);
        var cookies = new CookieManager();
        var http = HttpClient.newBuilder().cookieHandler(cookies).build();
        var session = new ScenarioHttpSession(http, cookies, json, ACTOR, PASSWORD);
        var port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
        var path = UriComponentsBuilder.fromPath("/api/projects/{project}/analysis-jobs/{job}/retry-failed")
                .queryParam("workspaceId", scope.workspaceId()).queryParam("workspaceScopeKey", scope.workspaceId())
                .queryParam("repositoryId", scope.repositoryId()).queryParam("branch", scope.currentBranch())
                .buildAndExpand(demo.projectId(), demo.jobId()).encode().toUriString();
        var request = session.request(port, "POST", path).header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        var receipt = json.readTree(response.body());
        assertThat(receipt.path("id").asString()).isEqualTo(demo.jobId());
        assertThat(receipt.path("provider").asString()).isEqualTo(checkpoint.provider());
        assertExpiredOwner(demo, scope);
        AnalysisJobView result = await().pollInSameThread().atMost(Duration.ofMinutes(4)).pollInterval(Duration.ofMillis(300))
                .until(() -> store.getJob(demo.jobId(), demo.projectId(), ACTOR, scope),
                        job -> job.status() != AnalysisStatus.RUNNING && job.status() != AnalysisStatus.PENDING);
        assertThat(result.status()).isEqualTo(AnalysisStatus.PARTIAL);
        var item = result.items().getFirst();
        assertThat(item.attempt()).isEqualTo(2);
        assertThat(result.provider()).isEqualTo(checkpoint.provider());
        assertThat(store.listRequirementSnapshots(demo.projectId(), demo.requirementId(), ACTOR, scope))
                .singleElement().satisfies(snapshot -> {
                    assertThat(snapshot.id()).isEqualTo(item.snapshotId());
                    assertThat(snapshot.requirementVersionId()).isEqualTo(demo.requirementVersionId());
                    assertThat(snapshot.jobId()).isEqualTo(demo.jobId());
                });
        assertExpiredOwner(demo, scope);
        assertExpiredOwner(normal, scope);
        assertThat(store.getJob(normal.jobId(), normal.projectId(), ACTOR, scope).status()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(store.listRequirementSnapshots(normal.projectId(), normal.requirementId(), ACTOR, scope)).isEmpty();
    }

    private void assertExpiredOwner(WorkItem old, WorkspaceContext scope) {
        var claims = app.getBean(PortfolioAnalysisClaimPersistenceService.class);
        String id = UUID.randomUUID().toString();
        var result = new AnalysisResult(Map.of("UA-1605", 60), List.of());
        result.setStatus("SUCCESS");
        assertThatThrownBy(() -> claims.persistSnapshot(old, id, "portfolio:" + id, result, null, null, null,
                "CUSTOM_OPENAI", null, "prompt", "catalogue", ACTOR, scope, 1L))
                .isInstanceOf(PortfolioException.class).hasMessageContaining("claim is no longer active");
        assertThatThrownBy(() -> claims.failItem(old, new IllegalStateException("late worker")))
                .isInstanceOf(PortfolioException.class).hasMessageContaining("claim is no longer active");
    }
}

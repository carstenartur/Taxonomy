package com.taxonomy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taxonomy.dto.RepositoryState;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.ExportedPortfolioDsl;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scoped HTTP response fixtures for {@link PortfolioUiAcceptanceIT}.
 * Templates, Bootstrap, browser modules and authentication always come from the
 * real application. Only the reserved QA resources below have substituted API
 * responses. The inactive proxy preserves the existing backend acceptance flow.
 */
final class PortfolioContextHttpFixture implements AutoCloseable {
    static final long PROJECT_A = 739101;
    static final long PROJECT_B = 739102;
    static final long REQUIREMENT_A = 7391011;
    static final List<String> BRANCHES = List.of("qa-context-main", "qa-context-review", "qa-context-candidate");
    static final String ZERO_EVIDENCE = "QA: bewusst geprüfte Abdeckung von 0 %.";
    static final String CONFLICT = "QA: Diese Anforderung wurde inzwischen geändert. Bitte den Entwurf prüfen und erneut versuchen.";
    private static final Instant TIME = Instant.parse("2026-10-07T09:00:00Z");
    private static final Set<String> HOP_HEADERS = Set.of("connection", "content-length", "expect", "host",
            "upgrade", "transfer-encoding", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient upstreamClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile URI upstream;
    private volatile String context = "";
    private volatile boolean active;
    volatile boolean projectsAvailable;
    volatile boolean unknownCoverage;
    volatile List<AnalysisJobView> analysisJobs = List.of();
    volatile boolean jobPollingUnavailable;
    final AtomicInteger jobListReads = new AtomicInteger();
    final AtomicInteger successfulJobPolls = new AtomicInteger();
    volatile String head = "a".repeat(40);
    volatile ReadGate readGate;
    final List<String> unexpected = new CopyOnWriteArrayList<>();
    final List<CreateRequirementVersionRequest> submittedVersions = new CopyOnWriteArrayList<>();
    final Set<String> reads = ConcurrentHashMap.newKeySet();

    final List<ProjectView> projects = List.of(project(PROJECT_A, "QA-CONTEXT-A", "Pumpensteuerung im Notbetrieb", 4, 1),
            project(PROJECT_B, "QA-CONTEXT-B", "Leitstelle Nord", 1, 0));
    final List<RequirementView> requirements = List.of(
            requirement(REQUIREMENT_A, PROJECT_A, "QA-REQ-0", "Pumpe bei Notabschaltung stoppen"),
            requirement(7391012, PROJECT_A, "QA-REQ-65", "Status an die Leitstelle melden"),
            requirement(7391013, PROJECT_A, "QA-REQ-EMPTY", "Prüfprotokoll bereitstellen"),
            requirement(7391014, PROJECT_A, "QA-REQ-UNKNOWN", "Abdeckung fachlich nachprüfen"),
            requirement(7391021, PROJECT_B, "QA-REQ-B", "Alarm in der Leitstelle bestätigen"));

    PortfolioContextHttpFixture(URI upstream) throws IOException {
        this.upstream = upstream;
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::serve);
        server.start();
    }

    int port() { return server.getAddress().getPort(); }

    void upstream(URI uri, String contextPath) {
        if (active) throw new IllegalStateException("Finish the fixture before replacing its application");
        upstream = uri;
        context = contextPath;
    }

    void activate(String contextPath) {
        context = contextPath;
        projectsAvailable = false;
        unknownCoverage = false;
        analysisJobs = List.of();
        jobPollingUnavailable = false;
        jobListReads.set(0); successfulJobPolls.set(0);
        head = "a".repeat(40);
        unexpected.clear(); submittedVersions.clear(); reads.clear();
        active = true;
    }

    void deactivate() {
        if (readGate != null) readGate.release.countDown();
        readGate = null;
        active = false;
    }

    ReadGate delayNextRepositoryRead() { readGate = new ReadGate(); return readGate; }

    static final class ReadGate {
        final AtomicBoolean seen = new AtomicBoolean();
        final CountDownLatch release = new CountDownLatch(1);
    }

    RequirementView primaryRequirement(long projectId) {
        return requirements.stream().filter(item -> item.projectId() == projectId).findFirst().orElseThrow();
    }

    void showAnalysisJobs() {
        analysisJobs = List.of(analysisJob("qa-pending", AnalysisStatus.PENDING),
                analysisJob("qa-running", AnalysisStatus.RUNNING), analysisJob("qa-failed", AnalysisStatus.FAILED));
    }

    void completeRunningAnalysis() {
        analysisJobs = analysisJobs.stream().map(job -> job.id().equals("qa-running")
                ? analysisJob(job.id(), AnalysisStatus.SUCCESS) : job).toList();
        jobPollingUnavailable = false;
    }

    private AnalysisJobView analysisJob(String id, AnalysisStatus status) {
        boolean pending = status == AnalysisStatus.PENDING;
        boolean failed = status == AnalysisStatus.FAILED;
        boolean completed = status == AnalysisStatus.SUCCESS || failed;
        var items = new ArrayList<AnalysisJobItemView>();
        for (int index = 0; index < 2; index++) {
            RequirementView requirement = requirements.get(index);
            AnalysisStatus itemStatus = status == AnalysisStatus.RUNNING && index == 0
                    ? AnalysisStatus.SUCCESS : status;
            boolean itemCompleted = itemStatus == AnalysisStatus.SUCCESS || itemStatus == AnalysisStatus.FAILED;
            items.add(new AnalysisJobItemView(7391951L + index, requirement.id(), requirement.requirementKey(),
                    requirement.currentVersionId(), requirement.currentVersion().versionNumber(), itemStatus,
                    itemStatus == AnalysisStatus.SUCCESS ? "qa-snapshot-" + index : null, pending ? 0 : 1,
                    pending ? null : TIME, itemCompleted ? TIME : null,
                    failed ? "QA: Analyse für diesen Eintrag fehlgeschlagen." : null));
        }
        return new AnalysisJobView(id, PROJECT_A, status, null, "Mock", 250, "qa-fixture", "qa-context-workspace",
                TIME, pending ? null : TIME, completed ? TIME : null, 2,
                status == AnalysisStatus.SUCCESS ? 2 : status == AnalysisStatus.RUNNING ? 1 : 0, 0,
                failed ? 2 : 0, failed ? "QA: Zwei Analyse-Einträge sind fehlgeschlagen." : null, items);
    }

    private void serve(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (active) {
                boolean inContext = context.isEmpty() || path.startsWith(context + "/");
                String relative = inContext ? path.substring(context.length()) : null;
                String versionPath = "/api/projects/" + PROJECT_A + "/requirements/" + REQUIREMENT_A + "/versions";
                if (!Set.of("GET", "HEAD", "OPTIONS").contains(method)) {
                    if (method.equals("POST") && versionPath.equals(relative)
                            && exchange.getRequestURI().getRawQuery() == null) {
                        submittedVersions.add(json.readValue(exchange.getRequestBody(), CreateRequirementVersionRequest.class));
                        sendJson(exchange, 409, Map.of("type", "about:blank", "title", "Conflict", "status", 409,
                                "detail", CONFLICT, "instance", context + versionPath));
                    } else reject(exchange, "Only the QA version form submission is permitted");
                    return;
                }
                if (!inContext && fixturePath(path)) {
                    reject(exchange, "Fixture request escaped the exact application context");
                    return;
                }
                if ("/__qa_portfolio_context_cleanup__".equals(relative)) {
                    send(exchange, 200, "text/html", "<!doctype html><html><head><title>QA cleanup</title></head><body></body></html>".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (relative != null && fixturePath(relative)) {
                    if (relative.equals("/api/git/state")) {
                        ReadGate gate = readGate;
                        if (gate != null && gate.seen.compareAndSet(false, true)
                                && !gate.release.await(30, TimeUnit.SECONDS)) {
                            reject(exchange, "Timed out waiting for the explicit repository-read release");
                            return;
                        }
                    }
                    Object payload = payload(relative);
                    if (payload == null) { reject(exchange, "Unhandled reserved QA resource"); return; }
                    reads.add(relative);
                    if (payload instanceof AnalysisJobView job && job.id().equals("qa-running")) {
                        if (jobPollingUnavailable) {
                            sendJson(exchange, 503, Map.of("detail", "QA: Job polling is temporarily unavailable."));
                            return;
                        }
                        successfulJobPolls.incrementAndGet();
                    }
                    if (relative.endsWith("/copilot/latest")) send(exchange, 204, "application/json", new byte[0]);
                    else sendJson(exchange, 200, payload);
                    return;
                }
            }
            forward(exchange);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            unexpected.add("Interrupted HTTP fixture: " + exchange.getRequestURI());
            send(exchange, 502, "text/plain", new byte[0]);
        } catch (RuntimeException failure) {
            unexpected.add("HTTP fixture failure: " + failure);
            send(exchange, 502, "text/plain", new byte[0]);
        } finally { exchange.close(); }
    }

    private static boolean fixturePath(String path) {
        return path.equals("/api/projects") || path.equals("/api/products") || path.equals("/api/git/state")
                || path.equals("/api/projects/git/export")
                || path.matches(".*/api/projects/(739101|739102)(/.*)?")
                || path.endsWith("/api/projects") || path.endsWith("/api/products")
                || path.endsWith("/api/git/state") || path.endsWith("/api/projects/git/export");
    }

    private Object payload(String path) {
        if (path.equals("/api/projects")) return projectsAvailable ? projects : List.of();
        if (path.equals("/api/products")) return List.of();
        if (path.equals("/api/git/state")) return new RepositoryState(BRANCHES.getFirst(), head, TIME,
                "qa-fixture", "QA read fixture", BRANCHES, false, null, head, BRANCHES.getFirst(), TIME,
                false, head, false, 2, false);
        if (path.equals("/api/projects/git/export")) return new ExportedPortfolioDsl("qa-context-workspace",
                "qa-fixture", BRANCHES.getFirst(), head, "project QA-CONTEXT-A\n# QA preview " + head.substring(0, 8),
                2, 5, 1, 0, TIME);
        for (ProjectView project : projects) {
            String prefix = "/api/projects/" + project.id();
            if (path.equals(prefix)) return project;
            if (path.equals(prefix + "/portfolio")) return portfolio(project);
            if (path.equals(prefix + "/requirements")) return requirements(project.id());
            if (path.equals(prefix + "/analysis-jobs")) {
                jobListReads.incrementAndGet();
                return analysisJobs.stream().filter(job -> job.projectId().equals(project.id())).toList();
            }
            if (path.startsWith(prefix + "/analysis-jobs/")) {
                String jobId = path.substring((prefix + "/analysis-jobs/").length());
                return analysisJobs.stream().filter(job -> job.projectId().equals(project.id()) && job.id().equals(jobId))
                        .findFirst().orElse(null);
            }
            for (RequirementView requirement : requirements(project.id())) {
                String detail = prefix + "/requirements/" + requirement.id();
                if (path.equals(detail)) return requirement;
                if (path.equals(detail + "/versions")) return List.of(requirement.currentVersion());
                if (List.of("/snapshots", "/reformulations", "/copilot/latest").stream()
                        .anyMatch(suffix -> path.equals(detail + suffix))) return List.of();
            }
        }
        return null;
    }

    private List<RequirementView> requirements(long projectId) {
        return requirements.stream().filter(item -> item.projectId() == projectId).toList();
    }

    private ProjectPortfolioView portfolio(ProjectView project) {
        boolean withSolution = project.id() == PROJECT_A;
        var items = requirements(project.id());
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put("QA-REQ-0", 0); values.put("QA-REQ-65", 65);
        // Deliberate malformed-response injection; the normal sparse integer
        // matrix service omits unknown relationships instead of returning null.
        if (unknownCoverage) values.put("QA-REQ-UNKNOWN", null);
        MatrixView empty = new MatrixView(List.of(), List.of(), Map.of());
        MatrixView matrix = withSolution ? new MatrixView(List.of("QA-SOL-A"),
                items.stream().map(RequirementView::requirementKey).toList(), Map.of("QA-SOL-A", values)) : empty;
        return new ProjectPortfolioView(project,
                new PortfolioMetrics(items.size(), 0, 0, withSolution ? 2 : items.size(), withSolution ? 1 : 0,
                        withSolution ? Map.of(ActionStatus.REUSE, 1) : Map.of(), 0, 0, 0, 0),
                items, List.of(), withSolution ? List.of(solution()) : List.of(), List.of(), empty, matrix, empty);
    }

    private ProjectSolutionView solution() {
        var solution = new SolutionView(7391802L, "QA-SOL-A", "Steuerungsdienst", "Lokale QA-Lösung",
                SolutionType.APPLICATION, OperatingModel.ON_PREMISES, LifecycleStatus.ACTIVE, 3,
                "qa-fixture", null, null, null, null, null, Map.of(), TIME, TIME, List.of());
        var links = new ArrayList<RequirementSolutionLinkView>();
        for (int index = 0; index < 2; index++) {
            var item = requirements.get(index);
            links.add(new RequirementSolutionLinkView(7391811L + index, item.id(), item.requirementKey(), null,
                    index == 0 ? 0 : 65, RequirementSolutionRole.USES, ReviewStatus.CONFIRMED,
                    index == 0 ? ZERO_EVIDENCE : "QA: teilweise bestätigte Abdeckung.", "qa-fixture", TIME));
        }
        return new ProjectSolutionView(7391801L, PROJECT_A, solution, ProjectSolutionStatus.SELECTED,
                ActionStatus.REUSE, 50, "Geprüfte Zuordnung für den Matrix-Workflow.", "qa-fixture", TIME, TIME, links, List.of());
    }

    private static ProjectView project(long id, String key, String title, int requirements, int solutions) {
        return new ProjectView(id, key, title, "Lokale QA-Daten für Navigation und die Prüfung gespeicherter Anforderungen.",
                ProjectStatus.ACTIVE, "qa-fixture", "qa-context-workspace", null, null, null, null,
                TIME, TIME, requirements, solutions, 0);
    }

    private static RequirementView requirement(long id, long project, String key, String title) {
        var source = new SourceReference(7391901L, 7391902L, List.of(7391903L), "4.2", 12, "Originaltext der geprüften Anforderung.");
        var version = new RequirementVersionView(id * 10, 4, title + ".\nDie Prüfung muss nachvollziehbar bleiben.",
                "a".repeat(64), "Geprüfter Ausgangsstand", "qa-fixture", TIME, source);
        return new RequirementView(id, project, key, title, RequirementStatus.DRAFT, 50, Criticality.HIGH,
                RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, "qa-fixture", version.id(), null, TIME, TIME, version);
    }

    private void reject(HttpExchange exchange, String reason) throws IOException {
        unexpected.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + ": " + reason);
        send(exchange, 599, "text/plain", reason.getBytes(StandardCharsets.UTF_8));
    }

    private void forward(HttpExchange exchange) throws IOException, InterruptedException {
        URI target = forwardingTarget(exchange.getRequestURI());
        var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(120));
        exchange.getRequestHeaders().forEach((key, values) -> {
            if (!HOP_HEADERS.contains(key.toLowerCase(java.util.Locale.ROOT))) {
                values.forEach(value -> request.header(key, value));
            }
        });
        var response = upstreamClient.send(request.method(exchange.getRequestMethod(),
                HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        response.headers().map().forEach((key, values) -> {
            if (!HOP_HEADERS.contains(key.toLowerCase(java.util.Locale.ROOT))) {
                for (String value : values) {
                    // Spring Security may issue absolute local redirects. Keep
                    // this browser on the same proxy origin and cookie boundary.
                    if (key.equalsIgnoreCase("location")) {
                        URI location = URI.create(value);
                        if (upstream.getScheme().equals(location.getScheme())
                                && upstream.getAuthority().equals(location.getAuthority())) {
                            value = location.getRawPath();
                            if (value == null || value.isEmpty()) value = "/";
                            if (location.getRawQuery() != null) value += "?" + location.getRawQuery();
                            if (location.getRawFragment() != null) value += "#" + location.getRawFragment();
                        }
                    }
                    exchange.getResponseHeaders().add(key, value);
                }
            }
        });
        send(exchange, response.statusCode(), null, response.body());
    }

    URI forwardingTarget(URI requested) {
        String requestTarget = requested.toASCIIString();
        if (requested.isAbsolute() || !requestTarget.startsWith("/")) {
            throw new IllegalArgumentException("Expected an origin-relative HTTP request target");
        }
        // Concatenate onto the fixed test application origin: URI.resolve would
        // interpret a leading // as a different host and could forward cookies.
        return URI.create(upstream.toString() + requestTarget);
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, status == 409 ? "application/problem+json" : "application/json", json.writeValueAsBytes(value));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] content) throws IOException {
        if (type != null) exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        if (status == 204 || status == 304 || exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, content.length);
            exchange.getResponseBody().write(content);
        }
    }

    @Override public void close() {
        deactivate(); server.stop(0); executor.shutdownNow(); upstreamClient.close();
    }
}

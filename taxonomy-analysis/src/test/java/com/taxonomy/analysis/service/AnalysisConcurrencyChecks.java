package com.taxonomy.analysis.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real Spring transport and registry regressions; HTTP is restricted to a loopback fixture. */
public final class AnalysisConcurrencyChecks {
    private AnalysisConcurrencyChecks() { }
    @FunctionalInterface public interface Check { void run() throws Exception; }

    public static Map<String, Check> checks() {
        Map<String, Check> cases = new LinkedHashMap<>();
        cases.put("queued cancellation releases admission before any provider call", AnalysisConcurrencyChecks::queuedCancellation);
        cases.put("per-user waiting does not stop another user's admitted run", AnalysisConcurrencyChecks::ownerIsolation);
        cases.put("reverse HTTP replies retain each user's prompt response and telemetry", AnalysisConcurrencyChecks::reverseReplies);
        cases.put("a server-error retry re-enters the physical request rate gate", AnalysisConcurrencyChecks::retryIsRateLimited);
        cases.put("transient 429 resumes with a bounded Retry-After policy", AnalysisConcurrencyChecks::transientRateLimit);
        cases.put("permanent quota exhaustion never triggers automatic HTTP retries", AnalysisConcurrencyChecks::permanentQuota);
        cases.put("queued deadline prevents a late worker from starting HTTP", AnalysisConcurrencyChecks::queueDeadline);
        cases.put("configured provider concurrency bounds simultaneous HTTP exchanges", AnalysisConcurrencyChecks::providerConcurrency);
        cases.put("Spring initializes and validates provider admission before use", AnalysisConcurrencyChecks::springConfiguration);
        cases.put("a persisted caller waits for live backlog capacity without losing its identity", AnalysisConcurrencyChecks::durableBackpressure);
        return cases;
    }

    public static void main(String[] args) throws Exception {
        for (var entry : checks().entrySet()) {
            if (args.length > 0 && !entry.getKey().contains(args[0])) continue;
            entry.getValue().run();
            System.out.println("PASS " + entry.getKey());
        }
    }

    private static AnalysisProgressRegistry registry(Map<String, Object> properties) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("concurrency-test", properties));
        return new AnalysisProgressRegistry(environment);
    }
    private static WorkspaceContext scope(String owner) { return new WorkspaceContext(owner, "work-" + owner, "draft", "repository"); }
    private static String id() { return UUID.randomUUID().toString(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError(message);
            Thread.sleep(5);
        }
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        check(latch.await(5, TimeUnit.SECONDS), "fixture barrier not reached");
    }
    private static void expectStopped(Runnable body) {
        try { body.run(); throw new AssertionError("expected a cooperative stop"); }
        catch (AnalysisStoppedException expected) { /* Required: no provider invocation. */ }
    }

    private static void queuedCancellation() {
        var registry = registry(Map.of("taxonomy.analysis.max-concurrent-jobs", 1, "taxonomy.analysis.queue-capacity", 1));
        String first = id(), replacement = id();
        try (var reservation = registry.reserve(first, "alice", scope("alice"), null)) {
            check("QUEUED".equals(registry.snapshot(first, "alice", scope("alice")).status()), "reservation must not claim RUNNING");
            check("CANCELLED".equals(registry.cancel(first, "alice", scope("alice")).status()), "queued cancellation must finish immediately");
            try (var next = registry.reserve(replacement, "bob", scope("bob"), null);
                 var run = next.open()) {
                AnalysisRunControl.checkpoint();
                run.finish("SUCCESS");
            }
            try (var cancelled = reservation.open()) { check(cancelled.id().equals(first), "wrong cancelled identity"); expectStopped(AnalysisRunControl::checkpoint); }
        }
        check(!AnalysisRunControl.active(), "thread-local context leaked");
    }

    private static void ownerIsolation() throws Exception {
        var registry = registry(Map.of("taxonomy.analysis.max-concurrent-jobs", 2,
                "taxonomy.analysis.max-concurrent-jobs-per-user", 1));
        String waiting = id(), bobId = id();
        try (var first = registry.open(id(), "alice", scope("alice"), null);
             var reservation = registry.reserve(waiting, "alice", scope("alice"), null);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch requested = new CountDownLatch(1);
            var queued = workers.submit(() -> {
                requested.countDown();
                try (var run = reservation.open()) { check(run.id().equals(waiting), "wrong waiting identity"); expectStopped(AnalysisRunControl::checkpoint); }
                return AnalysisRunControl.active();
            });
            await(requested);
            try (var bob = registry.open(bobId, "bob", scope("bob"), null)) {
                check("RUNNING".equals(registry.snapshot(bobId, "bob", scope("bob")).status()), "bob was blocked by alice");
                check("QUEUED".equals(registry.snapshot(waiting, "alice", scope("alice")).status()), "per-owner active cap was bypassed");
                registry.cancel(waiting, "alice", scope("alice"));
                check(!queued.get(5, TimeUnit.SECONDS), "worker context leaked after queued cancellation");
                bob.finish("SUCCESS");
            } finally { registry.cancel(waiting, "alice", scope("alice")); }
            first.finish("SUCCESS");
        }
    }

    private static void reverseReplies() throws Exception {
        var registry = registry(Map.of());
        CountDownLatch aliceArrived = new CountDownLatch(1), releaseAlice = new CountDownLatch(1);
        ObjectMapper mapper = new ObjectMapper();
        try (var server = new Loopback(exchange -> {
            var body = mapper.readTree(exchange.getRequestBody());
            String prompt = body.path("messages").get(0).path("content").stringValue();
            if (prompt.equals("alice-private")) {
                aliceArrived.countDown();
                try { await(releaseAlice); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
            reply(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"" + prompt + "\"}}]}");
        }); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var gateway = gateway(server.url(), settings(0), mapper);
            String alice = id(), bob = id();
            try {
                var first = workers.submit(() -> observedRequest(registry, gateway, alice, "alice"));
                await(aliceArrived);
                var second = workers.submit(() -> observedRequest(registry, gateway, bob, "bob"));
                check(second.get(5, TimeUnit.SECONDS).equals("bob-private"), "bob received another response");
                check(!first.isDone(), "fixture did not reverse responses");
                releaseAlice.countDown();
                check(first.get(5, TimeUnit.SECONDS).equals("alice-private"), "alice received another response");
                for (String owner : List.of("alice", "bob")) {
                    String operation = owner.equals("alice") ? alice : bob;
                    var snapshot = registry.snapshot(operation, owner, scope(owner));
                    check(snapshot.calls().size() == 1, "foreign or missing call telemetry");
                    var detail = registry.callDetail(operation, 1, owner, scope(owner));
                    check(detail.prompt().equals(owner + "-private"), "prompt leaked across users");
                    check(detail.response().contains(owner + "-private"), "response leaked across users");
                }
                try { registry.snapshot(alice, "bob", scope("bob")); throw new AssertionError("cross-user observation allowed"); }
                catch (ResponseStatusException denied) { check(denied.getStatusCode().value() == 404, "wrong denial"); }
            } finally { releaseAlice.countDown(); }
        }
    }

    private static String observedRequest(AnalysisProgressRegistry registry, OpenAiCompatibleGateway gateway, String id, String owner) {
        try (var run = registry.open(id, owner, scope(owner), null)) {
            var detail = AnalysisRunControl.call("OPENAI", "CP", () -> {
                String prompt = owner + "-private";
                String response = gateway.sendHttpRequest(prompt, "");
                var result = new LlmCallDetail();
                result.setPrompt(prompt); result.setRawResponse(response); result.setScores(Map.of());
                return result;
            });
            run.finish("SUCCESS");
            return gateway.extractResponseText(detail.getRawResponse());
        }
    }

    private static void retryIsRateLimited() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var registry = registry(Map.of());
        String operation = id();
        try (var server = new Loopback(exchange -> reply(exchange, calls.incrementAndGet() == 1 ? 500 : 200, "{}"));
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var gateway = gateway(server.url(), settings(1), new ObjectMapper());
            var work = workers.submit(() -> {
                try (var run = registry.open(operation, "alice", scope("alice"), null)) {
                    check(run.id().equals(operation), "wrong retry identity");
                    expectStopped(() -> gateway.sendHttpRequest("retry-evidence", ""));
                }
                return null;
            });
            try {
                await(() -> calls.get() > 0, "first HTTP attempt was not observed");
                await(() -> work.isDone() || "WAITING_RATE_LIMIT".equals(registry.snapshot(operation, "alice", scope("alice")).phase()),
                        "retry did not re-enter rate admission");
                check(calls.get() == 1 && !work.isDone(), "server-error retry bypassed the physical RPM gate");
            } finally {
                registry.cancel(operation, "alice", scope("alice"));
                work.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void transientRateLimit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var server = new Loopback(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "0");
            reply(exchange, calls.incrementAndGet() == 1 ? 429 : 200, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}");
        })) {
            check(gateway(server.url(), settings(0), new ObjectMapper()).sendHttpRequest("transient", "") != null, "retry did not return");
            check(calls.get() == 2, "transient 429 must use exactly one permitted retry");
        }
    }
    private static void permanentQuota() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var server = new Loopback(exchange -> {
            calls.incrementAndGet(); exchange.getResponseHeaders().set("Retry-After", "0");
            reply(exchange, 429, "{\"error\":{\"code\":\"insufficient_quota\"}}");
        })) {
            try { gateway(server.url(), settings(0), new ObjectMapper()).sendHttpRequest("quota", ""); throw new AssertionError("quota exhaustion accepted"); }
            catch (LlmRateLimitException expected) { check(calls.get() == 1, "permanent exhaustion was retried"); }
        }
    }

    private static void queueDeadline() throws Exception {
        var registry = registry(Map.of("taxonomy.analysis.maximum-queue-wait-seconds", 1));
        String operation = id();
        try (var waiting = registry.reserve(operation, "alice", scope("alice"), null)) {
            await(() -> "PARTIAL".equals(registry.snapshot(operation, "alice", scope("alice")).status()), "queued deadline never became terminal");
            try (var run = waiting.open()) { check(run.id().equals(operation), "wrong expired identity"); expectStopped(AnalysisRunControl::checkpoint); }
            var terminal = registry.snapshot(operation, "alice", scope("alice"));
            check("TIME_LIMIT".equals(terminal.stopReason()), "queue deadline lost its reason");
            check(terminal.executionMillis() == 0, "unstarted job acquired execution time");
        }
    }

    private static void providerConcurrency() throws Exception {
        AtomicInteger inside = new AtomicInteger(), peak = new AtomicInteger();
        CountDownLatch firstArrived = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var server = new Loopback(exchange -> {
            peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
            firstArrived.countDown();
            try { await(release); reply(exchange, 200, "{}"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            finally { inside.decrementAndGet(); }
        }); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var gateway = gateway(server.url(), settings(0), new ObjectMapper());
            gateway.configureRequestLimits(new ProviderRequestLimiter.Limits(1, 4));
            try {
                var first = workers.submit(() -> gateway.sendHttpRequest("first", ""));
                await(firstArrived);
                var registry = registry(Map.of());
                String secondId = id();
                CountDownLatch registered = new CountDownLatch(1);
                var second = workers.submit(() -> {
                    try (var run = registry.open(secondId, "bob", scope("bob"), null)) {
                        registered.countDown();
                        String result = gateway.sendHttpRequest("second", "");
                        run.finish("SUCCESS");
                        return result;
                    }
                });
                await(registered);
                await(() -> peak.get() > 1 || "WAITING_PROVIDER_CAPACITY".equals(
                        registry.snapshot(secondId, "bob", scope("bob")).phase()), "second exchange did not reach admission");
                check(peak.get() == 1, "second HTTP request reached the provider while its only permit was held");
                release.countDown();
                check(first.get(5, TimeUnit.SECONDS) != null && second.get(5, TimeUnit.SECONDS) != null, "queued exchange failed");
                check(peak.get() == 1, "provider HTTP concurrency limit exceeded");
            } finally { release.countDown(); }
        }
    }

    private static void springConfiguration() {
        for (int limit : new int[]{1, 0}) {
            try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("provider-test", Map.of(
                        "taxonomy.llm.max-concurrent-requests", limit)));
                context.registerBean(LlmGatewayRegistry.class, () -> new LlmGatewayRegistry(
                        new LlmProviderConfig(null), new RestTemplate((uri, method) -> {
                            throw new AssertionError("Configuration must not contact a provider");
                        }), new ObjectMapper(), settings(0), null, null));
                try {
                    context.refresh();
                    check(limit == 1, "invalid startup capacity accepted");
                    check(context.getBean(LlmGatewayRegistry.class).getGateway(LlmProvider.GEMINI) != null,
                            "gateway registration was lost");
                } catch (org.springframework.beans.factory.BeanCreationException failure) {
                    check(limit == 0 && failure.getMostSpecificCause() instanceof IllegalArgumentException,
                            "unexpected Spring configuration failure: " + failure.getMessage());
                }
            }
        }
    }

    @SuppressWarnings("try") // Release the deliberately held queue place before the worker completes.
    private static void durableBackpressure() throws Exception {
        var registry = registry(Map.of("taxonomy.analysis.queue-capacity", 1));
        var actor = scope("alice");
        try (var held = registry.reserve(id(), "alice", actor, null);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var release = new CountDownLatch(1);
            var future = workers.submit(() -> {
                release.countDown();
                try (var run = registry.open(null, "alice", actor,
                        new com.taxonomy.dto.AnalysisProvenance(1L, 2L, "snapshot", "portfolio:snapshot"))) {
                    AnalysisRunControl.checkpoint();
                    run.finish("SUCCESS");
                    return registry.snapshot(run.id(), "alice", actor).status();
                }
            });
            try {
                await(release);
                try { future.get(100, TimeUnit.MILLISECONDS); throw new AssertionError("full backlog bypassed"); }
                catch (java.util.concurrent.TimeoutException expected) { /* Existing durable work is back-pressured. */ }
                held.close();
                check("COMPLETED".equals(future.get(5, TimeUnit.SECONDS)), "durable caller failed instead of waiting");
            } finally { held.close(); }
        }
        check(!AnalysisRunControl.active(), "durable wait leaked thread context");
    }

    private static AnalysisRuntimeSettings settings(int rpm) {
        return (key, fallback) -> key.equals("llm.retry.max") ? 1
                : key.startsWith("llm.rpm") ? rpm : fallback;
    }
    private static OpenAiCompatibleGateway gateway(String url, AnalysisRuntimeSettings settings, ObjectMapper mapper) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000); factory.setReadTimeout(5000);
        return new OpenAiCompatibleGateway(LlmProvider.OPENAI, url, "loopback-test", 0,
                new RestTemplate(factory), mapper, new LlmResponseParser(mapper), settings, null, null);
    }
    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private static final class Loopback implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        Loopback(com.sun.net.httpserver.HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers); server.createContext("/", handler); server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
        @Override public void close() { server.stop(0); workers.shutdownNow(); }
    }
}

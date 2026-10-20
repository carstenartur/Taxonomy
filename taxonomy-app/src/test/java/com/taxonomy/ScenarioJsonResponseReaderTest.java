package com.taxonomy;

import com.taxonomy.acceptance.ScenarioPromptEvidenceQuality;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real JDK body subscribers with a bounded, demand-driven wire fixture; no socket or Spring context. */
class ScenarioJsonResponseReaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CHUNK = 16 * 1024;
    @TempDir Path directory;

    @Test void scenarioJsonReadsAvoidTheLegacyWholeResponseArrayAcrossAllThreads() throws Exception {
        var snapshot = JSON.valueToTree(Map.of("summary", Map.of("id", "immutable-source"), "analysis", Map.of(
                "recovery", Map.of("id", "immutable-journal"), "relationSearchReport", snapshotRelationReport(),
                "source", "Complete original source — 水位 🌊; quoted evidence.\n".repeat(160_000))));
        byte[] wire = JSON.writeValueAsBytes(snapshot);
        var expectedCycle = ScenarioPromptEvidenceQuality.verifyCycle(snapshot);
        try (var client = new WireClient(wire)) {
            var driver = driver(client, Duration.ofSeconds(30));
            // A positive control proves this JDK records the actual body-sized join allocation.
            var legacy = allocations("binary-control", () -> assertThat(driver.request("GET", "/export.bin", null, 200).body()).isEqualTo(wire));
            assertThat(wholeBodyJoins(legacy, wire.length)).as("The unchanged binary path still consumes complete response bytes").isNotEmpty();
            var actual = allocations("json-response", () -> {
                var value = driver.get("/api/projects/1/snapshots/immutable-source");
                assertThat(value).isEqualTo(snapshot);
                assertThat(ScenarioPromptEvidenceQuality.verifyCycle(value)).isEqualTo(expectedCycle);
                assertThat(driver.observedAnalysisCycles).containsEntry("immutable-source", expectedCycle);
            });
            // JFR records allocations on every thread, including a virtual JSON reader and its feeder.
            assertThat(wholeBodyJoins(actual, wire.length)).as("JSON responses must preserve every field without joining all HTTP buffers into a document-sized byte[]").isEmpty();
        }
    }

    @Test void jsonPostsKeepTheirAuthenticatedHeadersPublisherStatusAndCompleteContent() throws Exception {
        var expected = JSON.valueToTree(Map.of("allFields", List.of(1, true, "水位 🌊"), "nested", Map.of("source", "unchanged")));
        try (var client = new WireClient(JSON.writeValueAsBytes(expected))) {
            client.status = 201;
            var driver = driver(client, Duration.ofSeconds(30));
            var body = Map.of("instruction", "continue once", "version", 7);
            assertThat(driver.post("/api/projects/1/continue", body, 201)).isEqualTo(expected);
            assertThat(client.lastRequest.method()).isEqualTo("POST");
            assertThat(client.lastRequest.headers().firstValue("Content-Type")).contains("application/json");
            assertThat(client.lastRequest.headers().firstValue("X-CSRF-TOKEN")).contains("existing-authenticated-token");
            assertThat(client.lastRequest.timeout()).contains(Duration.ofSeconds(30));
            var publisher = client.lastRequest.bodyPublisher().orElseThrow();
            var bytes = new java.io.ByteArrayOutputStream();
            var completed = new CompletableFuture<Void>();
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                @Override public void onNext(ByteBuffer value) { while (value.hasRemaining()) bytes.write(value.get()); }
                @Override public void onError(Throwable failure) { completed.completeExceptionally(failure); }
                @Override public void onComplete() { completed.complete(null); }
            });
            completed.get(3, TimeUnit.SECONDS);
            assertThat(JSON.readTree(bytes.toByteArray())).isEqualTo(JSON.valueToTree(body));
            assertThat(client.bodyClosed).isTrue();
            assertReaderStopped(client);
        }
    }

    @Test void repeatedSnapshotReadsStillRejectAnyMutationOfTheCheckedSource() throws Exception {
        var source = JSON.valueToTree(Map.of("summary", Map.of("id", "immutable-source"), "analysis", Map.of(
                "recovery", Map.of("id", "immutable-journal"), "relationSearchReport", snapshotRelationReport(), "evidence", "original")));
        try (var client = new WireClient(JSON.writeValueAsBytes(source))) {
            var driver = driver(client, Duration.ofSeconds(30));
            driver.get("/api/projects/1/snapshots/immutable-source");
            var changed = source.deepCopy();
            changed.withObject("/analysis").put("evidence", "mutated");
            client.wire = JSON.writeValueAsBytes(changed);
            assertThatThrownBy(() -> driver.get("/api/projects/1/snapshots/immutable-source"))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("Repeated snapshot reads preserve the checked immutable source");
            assertThat(client.bodyClosed).isTrue();
        }
    }

    @Test void statusFailuresKeepTheFirst400Utf16CharactersAndConsumeTheWholeErrorBody() throws Exception {
        String error = "é😀漢".repeat(20_000);
        try (var client = new WireClient(error.getBytes(StandardCharsets.UTF_8))) {
            client.status = 409;
            assertThatThrownBy(() -> read(client, JSON, Duration.ofSeconds(3)))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("GET /json?scope=retained: " + error.substring(0, 400));
            assertThat(client.feeds.getFirst().sent).isEqualTo(client.wire.length);
            assertThat(client.bodyClosed).isTrue();
            assertReaderStopped(client);
        }
    }

    @Test void lateTransportFailureWinsOverAnUnexpectedStatusAsWithACompleteByteArrayRead() throws Exception {
        try (var client = new WireClient("error body ".repeat(10_000).getBytes(StandardCharsets.UTF_8))) {
            client.status = 500;
            client.failAtEnd = true;
            assertThatThrownBy(() -> read(client, JSON, Duration.ofSeconds(3)))
                    .isInstanceOf(IOException.class).hasStackTraceContaining("late transport failure");
            assertThat(client.bodyClosed).isTrue();
        }
    }

    @Test void defaultMapperStillRejectsMalformedAndAdditionalJsonValuesAndClosesTheBody() throws Exception {
        for (String invalid : List.of("{\"source\":[1,2", "{\"source\":true} {\"unread\":true}")) {
            try (var client = new WireClient(invalid.getBytes(StandardCharsets.UTF_8))) {
                assertThatThrownBy(() -> read(client, JSON, Duration.ofSeconds(3))).isInstanceOf(tools.jackson.core.JacksonException.class);
                assertThat(client.bodyClosed).isTrue();
                assertReaderStopped(client);
            }
        }
    }

    @Test void lateIoRemainsVisibleWhenTheActualMapperChecksTheEndOfTheBody() throws Exception {
        try (var client = new WireClient(("{\"source\":true}" + " ".repeat(100_000)).getBytes(StandardCharsets.UTF_8))) {
            client.failAtEnd = true;
            assertThatThrownBy(() -> read(client, JSON, Duration.ofSeconds(3))).hasStackTraceContaining("late transport failure");
            assertThat(client.bodyClosed).isTrue();
        }
    }

    @Test void aMapperThatClosesItsSourceAfterOneValueStillDrainsAndFindsLateIo() throws Exception {
        var early = tools.jackson.databind.json.JsonMapper.builder()
                .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        try (var client = new WireClient(("{\"source\":true}" + " ".repeat(100_000)).getBytes(StandardCharsets.UTF_8))) {
            client.failAtEnd = true;
            assertThatThrownBy(() -> read(client, early, Duration.ofSeconds(3)))
                    .isInstanceOf(IOException.class).hasStackTraceContaining("late transport failure");
            assertThat(client.feeds.getFirst().sent).isEqualTo(client.wire.length);
            assertThat(client.bodyClosed).isTrue();
        }
        try (var client = new WireClient("{\"source\":true} ignored by the supplied mapper".getBytes(StandardCharsets.UTF_8))) {
            assertThat(read(client, early, Duration.ofSeconds(3))).isEqualTo(JSON.readTree("{\"source\":true}"));
            assertThat(client.feeds.getFirst().sent).isEqualTo(client.wire.length);
        }
    }

    @Test void theOriginalDeadlineIncludesWaitingForResponseHeaders() throws Exception {
        try (var client = new WireClient("{}".getBytes(StandardCharsets.UTF_8))) {
            client.headerGate = new CountDownLatch(1);
            assertThatThrownBy(() -> read(client, JSON, Duration.ofMillis(200))).isInstanceOf(HttpTimeoutException.class);
            assertThat(client.sendExited.await(3, TimeUnit.SECONDS)).isTrue();
            assertReaderStopped(client);
            assertThat(client.feeds).isEmpty();
        }
    }

    @Test void theSameDeadlineIncludesHeadersParsingAndTheFinalDrain() throws Exception {
        try (var client = new WireClient("{\"source\":true} ".getBytes(StandardCharsets.UTF_8))) {
            client.headerDelay = Duration.ofSeconds(3);
            client.stallAtEnd = true;
            long start = System.nanoTime();
            assertThatThrownBy(() -> read(client, JSON, Duration.ofSeconds(4))).isInstanceOf(HttpTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("A body timeout must use the original budget, including header time")
                    .isLessThan(Duration.ofSeconds(6));
            assertThat(client.bodyClosed).isTrue();
            assertThat(client.feeds.getFirst().cancelled).isTrue();
            assertThat(client.feeds.getFirst().finished.await(3, TimeUnit.SECONDS)).isTrue();
            assertReaderStopped(client);
        }
    }

    @Test void interruptionClosesTheBlockedBodyCancelsTheFeedAndLeavesNoReader() throws Exception {
        try (var client = new WireClient("{\"source\":true} ".getBytes(StandardCharsets.UTF_8))) {
            client.stallAtEnd = true;
            var failure = new AtomicReference<Throwable>();
            var interrupted = new AtomicBoolean();
            Thread caller = Thread.ofPlatform().start(() -> {
                try { read(client, JSON, Duration.ofSeconds(30)); }
                catch (Throwable caught) { failure.set(caught); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            assertThat(client.bodyOpened.await(3, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(3000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(InterruptedException.class);
            assertThat(interrupted).isTrue();
            assertThat(client.bodyClosed).isTrue();
            assertThat(client.feeds.getFirst().cancelled).isTrue();
            assertThat(client.feeds.getFirst().finished.await(3, TimeUnit.SECONDS)).isTrue();
            assertReaderStopped(client);
        }
    }

    private static Map<String, Object> snapshotRelationReport() {
        return Map.of("maxCalls", 24, "totalCalls", 7, "budget", Map.of(
                "callsPerSlice", 24, "maxTotalCalls", 3096, "newCalls", 7, "spentCalls", 7,
                "automatic", false, "slices", 1, "sourcesPerSlice", 32,
                "workItemsPerSlice", 512, "sourceCohortSize", 32));
    }

    private void assertReaderStopped(WireClient client) throws InterruptedException {
        assertThat(client.readerThread.join(Duration.ofSeconds(3))).as("The request reader must terminate").isTrue();
        assertThat(client.readerThread.isAlive()).isFalse();
    }

    private tools.jackson.databind.JsonNode read(WireClient client, ObjectMapper mapper, Duration timeout) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost/json?scope=retained")).timeout(timeout).GET().build();
        return ScenarioJsonResponseReader.read(client, request, mapper, 200);
    }

    private ScenarioArchitectureAcceptanceTest driver(WireClient client, Duration timeout) {
        var driver = new ScenarioArchitectureAcceptanceTest() {
            @Override HttpRequest.Builder authenticatedRequest(String method, String path) {
                return HttpRequest.newBuilder(URI.create("http://localhost" + path)).timeout(timeout)
                        .header("X-CSRF-TOKEN", "existing-authenticated-token");
            }
        };
        driver.http.close();
        org.springframework.test.util.ReflectionTestUtils.setField(driver, "http", client);
        return driver;
    }

    private List<RecordedEvent> allocations(String name, Throwing action) throws Exception {
        Path path = directory.resolve(name + ".jfr");
        try (var recording = new Recording()) {
            recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
            recording.start();
            action.run();
            recording.stop();
            recording.dump(path);
        }
        return RecordingFile.readAllEvents(path);
    }

    private List<RecordedEvent> wholeBodyJoins(List<RecordedEvent> events, int bodySize) {
        return events.stream().filter(event -> event.getClass("objectClass").getName().equals("[B"))
                .filter(event -> event.getLong("allocationSize") >= bodySize)
                .filter(event -> event.getStackTrace() != null && event.getStackTrace().getFrames().stream().anyMatch(frame ->
                        frame.getMethod().getType().getName().equals("jdk.internal.net.http.ResponseSubscribers$ByteArraySubscriber")
                                && frame.getMethod().getName().equals("join"))).toList();
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }

    private static final class WireClient extends HttpClient {
        volatile byte[] wire;
        int status = 200;
        boolean failAtEnd, stallAtEnd;
        Duration headerDelay = Duration.ZERO;
        CountDownLatch headerGate;
        final CountDownLatch sendExited = new CountDownLatch(1), bodyOpened = new CountDownLatch(1);
        volatile HttpRequest lastRequest;
        volatile Thread readerThread;
        final List<Feed> feeds = new ArrayList<>();
        final AtomicBoolean bodyClosed = new AtomicBoolean();
        WireClient(byte[] wire) { this.wire = wire; }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
            readerThread = Thread.currentThread();
            lastRequest = request;
            try {
                if (headerGate != null) headerGate.await();
                if (!headerDelay.isZero()) TimeUnit.NANOSECONDS.sleep(headerDelay.toNanos());
                var info = new HttpResponse.ResponseInfo() {
                    @Override public int statusCode() { return status; }
                    @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a,b) -> true); }
                    @Override public Version version() { return Version.HTTP_1_1; }
                };
                var subscriber = handler.apply(info);
                var feed = new Feed(wire, subscriber, failAtEnd, stallAtEnd);
                synchronized (feeds) { feeds.add(feed); }
                feed.start();
                try {
                    T value = subscriber.getBody().toCompletableFuture().get();
                    if (value instanceof InputStream stream) {
                        @SuppressWarnings("unchecked") T tracked = (T) new FilterInputStream(stream) {
                            @Override public void close() throws IOException { bodyClosed.set(true); super.close(); }
                        };
                        value = tracked;
                        bodyOpened.countDown();
                    }
                    return new Response<>(request, value, info);
                } catch (java.util.concurrent.ExecutionException failure) {
                    if (failure.getCause() instanceof IOException cause) throw cause;
                    throw new IOException(failure.getCause());
                }
            } finally { sendExited.countDown(); }
        }
        @Override public void close() {
            synchronized (feeds) { feeds.forEach(Feed::close); }
        }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { try { return SSLContext.getDefault(); } catch (Exception e) { throw new IllegalStateException(e); } }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushes) { throw new UnsupportedOperationException(); }
    }

    private record Response<T>(HttpRequest request, T body, HttpResponse.ResponseInfo info) implements HttpResponse<T> {
        @Override public int statusCode() { return info.statusCode(); }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return info.headers(); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return info.version(); }
    }

    private static final class Feed implements Flow.Subscription {
        final byte[] wire;
        final HttpResponse.BodySubscriber<?> subscriber;
        final boolean failAtEnd, stallAtEnd;
        volatile int sent;
        final CountDownLatch finished = new CountDownLatch(1);
        long demand;
        volatile boolean cancelled;
        Thread thread;
        Feed(byte[] wire, HttpResponse.BodySubscriber<?> subscriber, boolean failAtEnd, boolean stallAtEnd) {
            this.wire = wire; this.subscriber = subscriber; this.failAtEnd = failAtEnd; this.stallAtEnd = stallAtEnd;
        }
        @Override public synchronized void request(long count) {
            if (count <= 0) throw new IllegalArgumentException();
            demand = demand > Long.MAX_VALUE - count ? Long.MAX_VALUE : demand + count;
            notifyAll();
        }
        @Override public synchronized void cancel() { cancelled = true; notifyAll(); }
        private synchronized boolean next() throws InterruptedException {
            while (demand == 0 && !cancelled) wait();
            if (cancelled) return false;
            if (demand != Long.MAX_VALUE) demand--;
            return true;
        }
        void start() {
            subscriber.onSubscribe(this);
            thread = Thread.ofPlatform().daemon().name("scenario-json-wire-fixture").start(() -> {
                try {
                    int position = 0;
                    while (next()) {
                        if (position == wire.length) {
                            if (stallAtEnd) { synchronized (this) { while (!cancelled) wait(); } return; }
                            if (failAtEnd) subscriber.onError(new IOException("late transport failure"));
                            else subscriber.onComplete();
                            return;
                        }
                        int count = Math.min(CHUNK, wire.length - position);
                        byte[] bytes = java.util.Arrays.copyOfRange(wire, position, position + count);
                        subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
                        position += count;
                        sent = position;
                    }
                } catch (Throwable failure) {
                    if (!cancelled) subscriber.onError(failure);
                } finally { finished.countDown(); }
            });
        }
        void close() {
            cancel();
            if (thread != null) thread.interrupt();
            try { assertThat(finished.await(3, TimeUnit.SECONDS)).as("The body feed must terminate").isTrue(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
    }
}

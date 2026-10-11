package com.taxonomy.analysis.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

@Timeout(10)
class OpenAiResponsesHttpTransportTest {
    HttpServer server; ExecutorService workers; String url;
    @AfterEach void close() { if (server != null) server.stop(0); if (workers != null) workers.shutdownNow(); }
    void server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(workers);
        server.createContext("/count", handler); server.start(); url = "http://127.0.0.1:" + server.getAddress().getPort() + "/count";
    }
    @Test void realHttpPreservesUtf8RequestAndReturnsOnlyABoundedResponse() throws Exception {
        var request = new AtomicReference<String>();
        server(exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"object\":\"response.input_tokens\",\"input_tokens\":42}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        var result = new OpenAiResponsesHttpTransport().exchange(url, new HttpHeaders(), "界🧭", 1024, 3000);
        assertThat(result).as("A supported bounded transport must actually execute the request").isNotNull();
        assertThat(result.getBody()).contains("input_tokens"); assertThat(request.get()).isEqualTo("界🧭");
    }
    @Test void rejectsOversizedSuccessAndErrorBodiesWhileReadingTheActualStream() throws Exception {
        var status = new java.util.concurrent.atomic.AtomicInteger(200);
        server(exchange -> {
            byte[] bytes = "x".repeat(32_768).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), 0);
            try { exchange.getResponseBody().write(bytes); } catch (java.io.IOException stopped) { }
            exchange.close();
        });
        for (int code : new int[]{200, 429}) {
            status.set(code);
            assertThatThrownBy(() -> new OpenAiResponsesHttpTransport().exchange(url, new HttpHeaders(), "{}", 1024, 3000))
                    .as("Response byte bound is enforced before success/error-body buffering")
                    .isInstanceOf(code == 429 ? org.springframework.web.client.HttpClientErrorException.class : LlmProviderException.class);
        }
    }
    @Test void totalDeadlineIncludesWaitingForResponseHeaders() throws Exception {
        server(exchange -> {
            try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8)); }
            catch (java.io.IOException stopped) { } finally { exchange.close(); }
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> new OpenAiResponsesHttpTransport().exchange(url, new HttpHeaders(), "{}", 1024, 100))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1000);
    }
    @Test void refusesOversizedRequestBeforeAnyHttp() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        server(exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        assertThatThrownBy(() -> new OpenAiResponsesHttpTransport().exchange(url, new HttpHeaders(), "界".repeat(400_000), 1024, 3000))
                .isInstanceOf(LlmProviderException.class);
        assertThat(requests.get()).isZero();
    }
    @Test void totalDeadlineAlsoBoundsABodyThatStopsAfterItsFirstByte() throws Exception {
        server(exchange -> {
            exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { exchange.getResponseBody().write('}'); } catch (java.io.IOException stopped) { } finally { exchange.close(); }
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> new OpenAiResponsesHttpTransport().exchange(url, new HttpHeaders(), "{}", 1024, 100))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1000);
    }
    @Test void scopedCancellationStopsAnActualResponseBodyWait() throws Exception {
        var canceled = new java.util.concurrent.atomic.AtomicBoolean();
        var bodyStarted = new CountDownLatch(1); var release = new CountDownLatch(1);
        server(exchange -> {
            exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            bodyStarted.countDown(); canceled.set(true);
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        var guard = new AnalysisMemoryGuard(new AnalysisMemoryGuard.Policy(70, 90, 1_048_576, 1000, 10_000),
                () -> new AnalysisMemoryGuard.Sample(1_048_576, 64 * 1_048_576), System::currentTimeMillis);
        long started = System.nanoTime();
        try (var control = AnalysisRunControl.worker("cancel-bounded-count", canceled::get, guard)) {
            assertThatThrownBy(() -> new OpenAiResponsesHttpTransport().exchange(url, new HttpHeaders(), "{}", 1024, 3000))
                    .isInstanceOf(AnalysisStoppedException.class);
            assertThat(bodyStarted.getCount()).isZero();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1000);
        } finally { release.countDown(); }
    }
}

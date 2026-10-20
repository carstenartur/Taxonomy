package com.taxonomy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Reads complete scenario JSON without first joining the HTTP body into a document-sized byte array. */
final class ScenarioJsonResponseReader {
    private ScenarioJsonResponseReader() { }

    static JsonNode read(HttpClient client, HttpRequest request, ObjectMapper json, int status) throws Exception {
        String path = request.uri().getRawPath() + (request.uri().getRawQuery() == null ? "" : "?" + request.uri().getRawQuery());
        var openBody = new AtomicReference<InputStream>();
        var cancelled = new AtomicBoolean();
        try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
            // ofInputStream completes at headers, so the same request budget must also
            // cover parsing and EOF. Keep one deadline for the entire exchange.
            long deadline = System.nanoTime() + request.timeout().orElseThrow().toNanos();
            var pending = reader.submit(() -> {
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    openBody.set(body);
                    if (cancelled.get()) throw new InterruptedException("Scenario JSON request was cancelled");
                    if (response.statusCode() != status) {
                        String prefix = new String(body.readNBytes(1600), StandardCharsets.UTF_8);
                        String excerpt = prefix.substring(0, Math.min(prefix.length(), 400));
                        body.transferTo(OutputStream.nullOutputStream());
                        assertThat(response.statusCode()).as("%s %s: %s", request.method(), path, excerpt)
                                .isEqualTo(status);
                    }
                    // Preserve the supplied mapper's validation settings and automatic
                    // parser cleanup while leaving the HTTP stream available for drain.
                    var source = new FilterInputStream(body) {
                        @Override public void close() { }
                    };
                    JsonNode result = json.readTree(source);
                    body.transferTo(OutputStream.nullOutputStream());
                    return result;
                } finally {
                    openBody.set(null);
                }
            });
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException();
                return pending.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException failure) {
                cancel(pending, cancelled, openBody, failure);
                var timeout = new HttpTimeoutException(request.method() + " " + path + ": JSON response deadline exceeded");
                timeout.initCause(failure);
                throw timeout;
            } catch (InterruptedException failure) {
                cancel(pending, cancelled, openBody, failure);
                Thread.currentThread().interrupt();
                throw failure;
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw new IllegalStateException(failure.getCause());
            }
        }
    }

    private static void cancel(Future<?> pending, AtomicBoolean cancelled, AtomicReference<InputStream> openBody, Exception failure) {
        cancelled.set(true);
        pending.cancel(true);
        InputStream body = openBody.getAndSet(null);
        if (body != null) try { body.close(); }
        catch (IOException close) { failure.addSuppressed(close); }
    }
}

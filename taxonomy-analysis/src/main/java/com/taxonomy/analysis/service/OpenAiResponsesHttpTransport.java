package com.taxonomy.analysis.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Actual HTTP with bounded request/body buffering, total deadline and cooperative cancellation. */
class OpenAiResponsesHttpTransport {
    ResponseEntity<String> exchange(String url, HttpHeaders headers, String body, int responseLimit, long timeoutMillis) {
        byte[] request = body.getBytes(StandardCharsets.UTF_8);
        if (request.length > OpenAiResponsesTokenContract.MAX_REQUEST_BYTES || responseLimit < 1
                || responseLimit > OpenAiResponsesTokenContract.MAX_GENERATION_RESPONSE_BYTES
                || timeoutMillis < 1 || timeoutMillis > OpenAiResponsesTokenContract.MAX_OPERATION_MILLIS) throw rejected();
        AnalysisRunControl.checkpoint();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMillis))
                .POST(HttpRequest.BodyPublishers.ofByteArray(request));
        headers.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        var responseInfo = new AtomicReference<HttpResponse.ResponseInfo>();
        // A client belongs to this one physical attempt. Closing it also closes outstanding body
        // subscriptions; no abandoned Count can keep a background transport alive after admission.
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofMillis(Math.min(timeoutMillis, 5000))).build()) {
            var future = client.sendAsync(builder.build(), info -> {
                responseInfo.set(info); return new BoundedBody(responseLimit);
            });
            try {
                while (true) {
                    AnalysisRunControl.checkpoint();
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw timeout();
                    try {
                        var response = future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
                        return result(response.statusCode(), response.headers().map(), response.body());
                    } catch (TimeoutException pending) { /* poll only the scoped cancellation/deadline */ }
                }
            } catch (ExecutionException failure) {
                if (causedBy(failure, ResponseLimitException.class)) {
                    var info = responseInfo.get();
                    // Even an oversized 429 remains an actual 429 with its bounded headers. Its
                    // omitted body must not bypass shared cooldown or imply known provider usage.
                    if (info != null && info.statusCode() >= 400) return result(info.statusCode(), info.headers().map(), new byte[0]);
                    throw rejected();
                }
                if (causedBy(failure, java.net.http.HttpTimeoutException.class)) throw timeout();
                throw new ResourceAccessException("Responses transport unavailable");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
            } finally {
                if (!future.isDone()) future.cancel(true);
                client.shutdownNow();
            }
        }
    }
    private static ResponseEntity<String> result(int status, java.util.Map<String, List<String>> values, byte[] body) {
        var headers = new HttpHeaders(); values.forEach(headers::put);
        var code = HttpStatusCode.valueOf(status);
        if (code.is4xxClientError()) throw HttpClientErrorException.create(code, "Responses request rejected", headers, body, StandardCharsets.UTF_8);
        if (code.is5xxServerError()) throw HttpServerErrorException.create(code, "Responses request failed", headers, body, StandardCharsets.UTF_8);
        if (!code.is2xxSuccessful()) throw rejected();
        return new ResponseEntity<>(new String(body, StandardCharsets.UTF_8), headers, code);
    }
    // Some JDKs wrap subscriber failures in an IOException before they reach the future.
    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return true;
        }
        return false;
    }
    private static ResourceAccessException timeout() {
        return new ResourceAccessException("Responses transport time limit exceeded", new SocketTimeoutException("Responses transport deadline"));
    }
    private static LlmProviderException rejected() {
        return new LlmProviderException(LlmProviderException.Reason.REQUEST_REJECTED, "Responses transport byte contract exceeded");
    }
    private static final class ResponseLimitException extends RuntimeException { }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return completed; }
        public void onSubscribe(Flow.Subscription incoming) { subscription = incoming; incoming.request(Long.MAX_VALUE); }
        public void onNext(List<ByteBuffer> incoming) {
            for (var buffer : incoming) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); completed.completeExceptionally(new ResponseLimitException()); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
        }
        public void onError(Throwable failure) { completed.completeExceptionally(failure); }
        public void onComplete() { completed.complete(bytes.toByteArray()); }
    }
}

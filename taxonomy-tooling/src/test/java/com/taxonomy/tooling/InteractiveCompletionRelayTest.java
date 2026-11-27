package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InteractiveCompletionRelayTest {
    @TempDir Path temporary;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test void deliversFreshSpoolRepliesWithVerifiableBytesAndNoInventedUsage() throws Exception {
        try (var relay = InteractiveCompletionRelay.start(temporary, 0, "assistant-qa", Duration.ofSeconds(5))) {
            String request = "{\n  \"model\":\"assistant-qa\",\"messages\":[{\"role\":\"user\",\"content\":\"Prüfe Backup.\"}],"
                    + "\"response_format\":{\"type\":\"json_object\"}}";
            var pending = post(relay, request);
            Path queued = awaitRequest(1);
            assertThat(Files.readString(queued)).isEqualTo(request);
            String id = stem(queued);
            String reply = "{\"content\":\"{\\\"status\\\":\\\"UNRESOLVED\\\"}\",\"responder\":\"isolated-assistant\"}";
            atomicReply(id, reply);
            var response = pending.get(5, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            var body = FlatJson.parseObject(response.body());
            assertThat(body).containsEntry("object", "chat.completion").containsEntry("model", "assistant-qa").doesNotContainKey("usage");
            assertThat(body.get("id")).isEqualTo("chatcmpl-" + id);
            var choice = (Map<?, ?>) ((List<?>) body.get("choices")).getFirst();
            assertThat(choice.get("finish_reason")).isEqualTo("stop");
            assertThat(((Map<?, ?>) choice.get("message")).get("content")).isEqualTo("{\"status\":\"UNRESOLVED\"}");
            var receipt = awaitReceipt(id);
            assertThat(receipt).containsEntry("provenance", "assistant-relay").containsEntry("outcome", "delivered")
                    .containsEntry("responder", "isolated-assistant").containsEntry("requestSha256", sha(request))
                    .containsEntry("responseSha256", sha(reply)).containsEntry("deliveredSha256", sha(response.body()));
            assertThat(((Number) receipt.get("elapsedMillis")).longValue()).isGreaterThanOrEqualTo(0L);
            assertThat(Files.readString(temporary.resolve("delivered/" + id + ".json"))).isEqualTo(response.body());

            var second = post(relay, request);
            Path secondQueued = awaitRequest(2);
            assertThat(stem(secondQueued)).isNotEqualTo(id);
            assertThat(second).isNotDone();
            atomicReply(stem(secondQueued), "{\"content\":\"A separately supplied response\"}");
            assertThat(second.get(5, TimeUnit.SECONDS).body()).contains("A separately supplied response");
        }
    }

    @Test void advertisesOnlyConfiguredModelAndUsesExactRoutes() throws Exception {
        try (var relay = InteractiveCompletionRelay.start(temporary, 0, "assistant-qa", Duration.ofSeconds(2))) {
            var models = client.send(HttpRequest.newBuilder(uri(relay, "/v1/models")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(models.statusCode()).isEqualTo(200);
            assertThat(models.body()).contains("assistant-qa", "assistant-relay");
            assertThat(client.send(HttpRequest.newBuilder(uri(relay, "/v1/models/extra")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
            assertThat(client.send(HttpRequest.newBuilder(uri(relay, "/v1/chat/completions")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);
        }
    }

    @Test void rejectsMalformedUnsupportedAndOversizedRequestsWithoutSpooling() throws Exception {
        try (var relay = InteractiveCompletionRelay.start(temporary, 0, "assistant-qa", Duration.ofSeconds(2))) {
            for (String invalid : List.of("{", "{\"model\":\"assistant-qa\",\"model\":\"other\"}",
                    requestWith("\"stream\":true,"), requestWith("\"n\":2,"), requestWith("\"tools\":[],"),
                    "{\"model\":\"different\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}",
                    "{\"model\":\"assistant-qa\",\"messages\":[]}",
                    "{\"model\":\"assistant-qa\",\"messages\":[{\"content\":\"Missing role\"}]}",
                    "{\"model\":\"assistant-qa\",\"messages\":[{\"role\":\"user\",\"content\":[{}]}]}")) {
                assertThat(post(relay, invalid).get(5, TimeUnit.SECONDS).statusCode()).as(invalid).isEqualTo(400);
            }
            assertThat(post(relay, "x".repeat(InteractiveCompletionRelay.MAX_BODY_BYTES + 1)).get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(413);
            try (var requests = Files.list(temporary.resolve("requests"))) { assertThat(requests).isEmpty(); }
        }
    }

    @Test void rejectsExcessiveNestingWithoutCrashingAndCanServeFollowingRequests() throws Exception {
        try (var relay = InteractiveCompletionRelay.start(temporary, 0, "assistant-qa", Duration.ofSeconds(2))) {
            String deep = "{\"x\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}";
            assertThat(post(relay, deep).get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(400);
            assertThat(client.send(HttpRequest.newBuilder(uri(relay, "/v1/models")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
    }

    @Test void recordsTimeoutAndNeverUsesAResponseForAnotherRequest() throws Exception {
        Files.createDirectories(temporary.resolve("responses"));
        Files.writeString(temporary.resolve("responses/old-response.json"), "{\"content\":\"Old response\"}");
        try (var relay = InteractiveCompletionRelay.start(temporary, 0, "assistant-qa", Duration.ofMillis(150))) {
            var pending = post(relay, requestWith(""));
            String id = stem(awaitRequest(1));
            var response = pending.get(5, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(504);
            assertThat(response.body()).doesNotContain("Old response");
            assertThat(awaitReceipt(id)).containsEntry("outcome", "timeout").doesNotContainKey("deliveredSha256");
        }
    }

    @Test void incompleteTemporaryReplyIsIgnoredAndMalformedFinalReplyIsRejected() throws Exception {
        try (var relay = InteractiveCompletionRelay.start(temporary, 0, "assistant-qa", Duration.ofSeconds(5))) {
            var pending = post(relay, requestWith(""));
            String id = stem(awaitRequest(1));
            Files.writeString(temporary.resolve("responses/" + id + ".tmp"), "{partial");
            Thread.sleep(75);
            assertThat(pending).isNotDone();
            atomicReply(id, "{\"content\":23}");
            assertThat(pending.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(502);
            assertThat(awaitReceipt(id)).containsEntry("outcome", "invalid_response");
        }
    }

    @Test void closesPendingWaitersAndCanBeClosedTwice() throws Exception {
        var relay = InteractiveCompletionRelay.start(temporary, 0, "assistant-qa", Duration.ofMinutes(5));
        var pending = post(relay, requestWith(""));
        String id = stem(awaitRequest(1));
        relay.close();
        relay.close();
        assertThat(pending.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(503);
        assertThat(awaitReceipt(id)).containsEntry("outcome", "closed");
    }

    @Test void validatesConfigurationAndRejectsResponseSymlinks() throws Exception {
        assertThatThrownBy(() -> InteractiveCompletionRelay.start(temporary, 0, " ", Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InteractiveCompletionRelay.start(temporary, 0, "assistant-qa", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        try (var relay = InteractiveCompletionRelay.start(temporary, 0, "assistant-qa", Duration.ofSeconds(3))) {
            var pending = post(relay, requestWith(""));
            String id = stem(awaitRequest(1));
            Path outside = temporary.resolve("outside.json");
            Files.writeString(outside, "{\"content\":\"Do not follow\"}");
            Files.createSymbolicLink(temporary.resolve("responses/" + id + ".json"), outside);
            assertThat(pending.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(502);
        }
    }

    @Test void limitsPendingWorkAndRejectsExcessWithoutRecordingCredentials() throws Exception {
        try (var relay = InteractiveCompletionRelay.start(temporary, 0, "assistant-qa", Duration.ofSeconds(5))) {
            var queued = new java.util.ArrayList<CompletableFuture<HttpResponse<String>>>();
            for (int i = 1; i <= 8; i++) { queued.add(post(relay, requestWith(""))); awaitRequest(i); }
            assertThat(post(relay, requestWith("")).get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(429);
            try (var requests = Files.list(temporary.resolve("requests"))) {
                for (Path path : requests.toList()) {
                    assertThat(Files.readString(path)).doesNotContain("never-record-this", "Authorization");
                    atomicReply(stem(path), "{\"content\":\"Completed\"}");
                }
            }
            for (var response : queued) assertThat(response.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
    }

    private CompletableFuture<HttpResponse<String>> post(InteractiveCompletionRelay relay, String body) {
        return client.sendAsync(HttpRequest.newBuilder(uri(relay, "/v1/chat/completions"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer never-record-this")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(InteractiveCompletionRelay relay, String path) { return URI.create("http://127.0.0.1:" + relay.boundPort() + path); }
    private static String requestWith(String extra) { return "{\"model\":\"assistant-qa\"," + extra + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}"; }
    private static String stem(Path path) { return path.getFileName().toString().replace(".json", ""); }

    private Path awaitRequest(int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (var stream = Files.list(temporary.resolve("requests"))) {
                var paths = stream.filter(p -> p.toString().endsWith(".json")).sorted((a, b) -> {
                    try { return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b)); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }).toList();
                if (paths.size() >= count) return paths.get(count - 1);
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Request was not queued");
    }

    private void atomicReply(String id, String content) throws Exception {
        Path draft = temporary.resolve("responses/" + id + ".tmp");
        Files.writeString(draft, content);
        Files.move(draft, temporary.resolve("responses/" + id + ".json"), StandardCopyOption.ATOMIC_MOVE);
    }

    private Map<String, Object> awaitReceipt(String id) throws Exception {
        Path receipt = temporary.resolve("receipts/" + id + ".json");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(receipt) && System.nanoTime() < deadline) Thread.sleep(10);
        return FlatJson.parseObject(Files.readString(receipt));
    }

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}

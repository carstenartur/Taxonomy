package com.taxonomy.tooling;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local, non-streaming OpenAI-compatible transport for independently supplied
 * assistant responses. This relay does not run a model or perform inference.
 */
public final class InteractiveCompletionRelay implements AutoCloseable {
    public static final int MAX_BODY_BYTES = 1_048_576;
    private static final int MAX_PENDING_REQUESTS = 8;
    private static final Set<String> ROLES = Set.of("system", "developer", "user", "assistant");
    private final Path spool;
    private final String model;
    private final Duration timeout;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore pending = new Semaphore(MAX_PENDING_REQUESTS);
    private final AtomicBoolean closed = new AtomicBoolean();

    private InteractiveCompletionRelay(Path spool, int port, String model, Duration timeout) throws IOException {
        if (spool == null || model == null || model.isBlank() || model.length() > 200
                || timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(30)) > 0 || port < 0 || port > 65535) {
            throw new IllegalArgumentException("Require a spool directory, model, port 0..65535 and timeout in (0,30 minutes]");
        }
        this.spool = spool.toAbsolutePath().normalize();
        this.model = model;
        this.timeout = timeout;
        for (String directory : List.of("", "requests", "responses", "delivered", "receipts")) {
            Path path = this.spool.resolve(directory);
            Files.createDirectories(path);
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Spool directories must be real directories: " + path);
            }
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public static InteractiveCompletionRelay start(Path spool, int port, String model, Duration timeout) throws IOException {
        return new InteractiveCompletionRelay(spool, port, model, timeout);
    }

    public int boundPort() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/v1/models")) {
                if (!exchange.getRequestMethod().equals("GET")) { error(exchange, 405, "Use GET for models"); return; }
                send(exchange, 200, json(Map.of("object", "list", "data", List.of(Map.of(
                        "id", model, "object", "model", "owned_by", "assistant-relay")))));
                return;
            }
            if (!path.equals("/v1/chat/completions")) { error(exchange, 404, "Unknown endpoint"); return; }
            if (!exchange.getRequestMethod().equals("POST")) { error(exchange, 405, "Use POST for completions"); return; }
            if (closed.get()) { error(exchange, 503, "Relay is closing"); return; }
            if (!pending.tryAcquire()) { error(exchange, 429, "Maximum pending relay requests reached"); return; }
            try { completion(exchange); }
            finally { pending.release(); }
        }
    }

    private void completion(HttpExchange exchange) throws IOException {
        byte[] request = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (request.length > MAX_BODY_BYTES) { error(exchange, 413, "Request exceeds 1 MiB"); return; }
        try { validateRequest(parse(request)); }
        catch (IllegalArgumentException exception) { error(exchange, 400, exception.getMessage()); return; }

        String requestId = UUID.randomUUID().toString();
        String completionId = "chatcmpl-" + requestId;
        Instant started = Instant.now();
        long startedNanos = System.nanoTime();
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("schemaVersion", 1);
        receipt.put("provenance", "assistant-relay");
        receipt.put("requestId", requestId);
        receipt.put("model", model);
        receipt.put("startedAt", started.toString());
        receipt.put("requestSha256", sha256(request));
        receipt.put("requestBytes", request.length);
        Path responsePath = spool.resolve("responses/" + requestId + ".json");
        String outcome = "io_error";
        try {
            atomicWrite(spool.resolve("requests/" + requestId + ".json"), request);
            while (!Files.exists(responsePath, LinkOption.NOFOLLOW_LINKS)) {
                if (closed.get()) { outcome = "closed"; error(exchange, 503, "Relay closed while awaiting response"); return; }
                if (System.nanoTime() - startedNanos >= timeout.toNanos()) {
                    outcome = "timeout"; error(exchange, 504, "No assistant response before relay timeout"); return;
                }
                try { Thread.sleep(25); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    outcome = "closed"; error(exchange, 503, "Relay interrupted while awaiting response"); return;
                }
            }
            byte[] reply;
            Map<String, Object> response;
            try {
                if (!Files.isRegularFile(responsePath, LinkOption.NOFOLLOW_LINKS) || Files.size(responsePath) > MAX_BODY_BYTES) {
                    throw new IllegalArgumentException("Response must be a regular file of at most 1 MiB");
                }
                try (var input = Files.newInputStream(responsePath, LinkOption.NOFOLLOW_LINKS)) {
                    reply = input.readNBytes(MAX_BODY_BYTES + 1);
                }
                if (reply.length > MAX_BODY_BYTES) throw new IllegalArgumentException("Response exceeds 1 MiB");
                receipt.put("responseSha256", sha256(reply));
                receipt.put("responseBytes", reply.length);
                response = parse(reply);
                if (!(response.get("content") instanceof String content) || content.isBlank()) {
                    throw new IllegalArgumentException("Response requires a nonblank content string");
                }
                if (!Set.of("content", "responder").containsAll(response.keySet())
                        || (response.containsKey("responder") && (!(response.get("responder") instanceof String responder)
                        || responder.isBlank() || responder.length() > 200))) {
                    throw new IllegalArgumentException("Response supports content and an optional responder string only");
                }
            } catch (IllegalArgumentException | IOException invalid) {
                outcome = "invalid_response";
                error(exchange, 502, "Invalid assistant response: " + invalid.getMessage());
                return;
            }
            if (response.containsKey("responder")) receipt.put("responder", response.get("responder"));
            byte[] delivered = json(Map.of("id", completionId, "object", "chat.completion",
                    "created", Instant.now().getEpochSecond(), "model", model,
                    "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", response.get("content")),
                            "finish_reason", "stop"))));
            atomicWrite(spool.resolve("delivered/" + requestId + ".json"), delivered);
            receipt.put("completionId", completionId);
            receipt.put("deliveredSha256", sha256(delivered));
            receipt.put("deliveredBytes", delivered.length);
            outcome = "delivery_failed";
            send(exchange, 200, delivered);
            outcome = "delivered";
        } finally {
            receipt.put("outcome", outcome);
            receipt.put("finishedAt", Instant.now().toString());
            receipt.put("elapsedMillis", Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
            atomicWrite(spool.resolve("receipts/" + requestId + ".json"), json(receipt));
        }
    }

    private void validateRequest(Map<String, Object> request) {
        if (!model.equals(request.get("model"))) throw new IllegalArgumentException("Request model must match " + model);
        if (request.containsKey("stream") && !Boolean.FALSE.equals(request.get("stream"))) {
            throw new IllegalArgumentException("Only non-streaming completions are supported");
        }
        if (request.containsKey("n") && !(request.get("n") instanceof Number n && n.doubleValue() == 1.0)) {
            throw new IllegalArgumentException("Only n=1 is supported");
        }
        if (request.containsKey("tools") || request.containsKey("functions") || request.containsKey("audio")) {
            throw new IllegalArgumentException("Tool, function and audio completions are not supported");
        }
        if (!(request.get("messages") instanceof List<?> messages) || messages.isEmpty() || messages.size() > 1024) {
            throw new IllegalArgumentException("messages must contain 1..1024 text messages");
        }
        for (Object value : messages) {
            if (!(value instanceof Map<?, ?> message) || !(message.get("role") instanceof String role) || !ROLES.contains(role)
                    || !(message.get("content") instanceof String)) {
                throw new IllegalArgumentException("Each message requires a supported role and string content");
            }
        }
    }

    private static Map<String, Object> parse(byte[] bytes) {
        String source;
        try {
            source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) { throw new IllegalArgumentException("Body must be valid UTF-8", exception); }
        // FlatJson is recursive: reject deeply nested input before invoking it.
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (char character : source.toCharArray()) {
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
            } else if (character == '"') quoted = true;
            else if (character == '[' || character == '{') {
                if (++depth > 64) throw new IllegalArgumentException("JSON nesting exceeds 64 levels");
            } else if (character == ']' || character == '}') depth--;
        }
        return FlatJson.parseObject(source);
    }

    private static byte[] json(Object value) { return FlatJson.pretty(value).getBytes(StandardCharsets.UTF_8); }

    private static void error(HttpExchange exchange, int code, String message) throws IOException {
        send(exchange, code, json(Map.of("error", Map.of("message", message, "type", "assistant_relay_error"))));
    }

    private static void send(HttpExchange exchange, int code, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void atomicWrite(Path destination, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".relay-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            // Waiting handlers observe closed, return 503 and finish their receipts.
            server.stop(1);
            executor.shutdownNow();
        }
    }

    /** Usage: InteractiveCompletionRelay SPOOL_DIRECTORY PORT MODEL [TIMEOUT_SECONDS]. */
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("Usage: InteractiveCompletionRelay SPOOL_DIRECTORY PORT MODEL [TIMEOUT_SECONDS]");
        }
        var relay = start(Path.of(args[0]), Integer.parseInt(args[1]), args[2],
                Duration.ofSeconds(args.length == 4 ? Long.parseLong(args[3]) : 300));
        Runtime.getRuntime().addShutdownHook(new Thread(relay::close, "assistant-relay-shutdown"));
        System.out.println("Assistant relay (no inference engine): http://127.0.0.1:" + relay.boundPort()
                + "/v1/chat/completions; model=" + args[2] + "; spool=" + relay.spool);
        new CountDownLatch(1).await();
    }
}

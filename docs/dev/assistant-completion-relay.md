# Assistant completion relay

`InteractiveCompletionRelay` provides a local OpenAI/vLLM-compatible **HTTP transport** for an interactive assistant session. The relay queues actual application requests and waits for a separately supplied assistant answer. It does not contain an inference engine, replay stored answers, choose taxonomy nodes, or implement a benchmark oracle. Describe a run using it as an **assistant-relay session**, not as a vLLM model benchmark.

## Start the endpoint

The implementation uses Java 25 and the JDK HTTP server. It adds no production dependency. Compile it through the normal Maven reactor:

```bash
./mvnw -B -ntp -pl taxonomy-tooling -am package -DskipTests
java -cp taxonomy-tooling/target/classes \
  com.taxonomy.tooling.InteractiveCompletionRelay \
  /absolute/path/to/run-spool 18042 assistant-relay-session 300
```

Arguments are spool directory, port, model name and optional response timeout in seconds (default 300; maximum 1800). Port `0` selects an available port and prints it. The relay binds only `127.0.0.1`; it has no public listener or credential configuration. The spool contains the full prompts and answers. Use a dedicated run directory and only data authorized for that session.

Configure Taxonomy's existing CUSTOM_OPENAI provider:

```bash
export CUSTOM_LLM_URL=http://127.0.0.1:18042/v1/chat/completions
export CUSTOM_LLM_MODEL=assistant-relay-session
export CUSTOM_LLM_CONTEXT_WINDOW_TOKENS=32768
```

The context-window setting describes the responding assistant's actual session limit. It is not enforced by this byte-bounded transport and must not be used to claim a model's token capacity. Select CUSTOM_OPENAI in the application's existing model configuration. Keep mock and replay modes disabled. The relay and application must share a network namespace; in isolated execution environments, start both from the same execution command. A later command can still respond through their shared spool filesystem.

The reusable Java API is:

```java
try (var relay = InteractiveCompletionRelay.start(
        spoolDirectory, 0, "assistant-relay-session", Duration.ofMinutes(5))) {
    int port = relay.boundPort();
    // Run the real application client against 127.0.0.1:port.
}
```

## Supply each response

1. Discover a new `requests/<uuid>.json` file. Its bytes are the actual POST body received from the application, including messages and any requested response schema. Request headers, including authorization, are not recorded.
2. Have the isolated responding assistant read that request. For independent QA, do not provide the reference oracle, expected node IDs or evaluator assertions to the responder. Answer the current request afresh.
3. Write a UTF-8 JSON object into `responses/<uuid>.tmp`:

```json
{
  "content": "The exact assistant response text. JSON model output is encoded as a string here.",
  "responder": "isolated assistant session"
}
```

4. Atomically rename that file to `responses/<uuid>.json` in the same directory. `content` is required and nonblank; `responder` is optional. These are the only supported wrapper fields. A temporary file is ignored until the final name exists. Once published, do not edit the file.

The relay wraps `content` in the ordinary `choices[0].message.content` envelope, with a fresh `chatcmpl-<uuid>` ID, the configured model identifier and `finish_reason: "stop"`. It does not invent token usage. Each HTTP request receives a new UUID even when its prompt is identical. An old response file cannot answer another request.

## Evidence and limits

Every accepted request has a terminal `receipts/<uuid>.json` record with UTC start/finish times, measured elapsed milliseconds, configured model, outcome and SHA-256 of the exact request bytes. A supplied response also has its byte hash; successful output has its completion ID and the hash of `delivered/<uuid>.json`, which contains the actual completion-envelope bytes. The receipt records `provenance: "assistant-relay"`. A `responder` label is operator-supplied metadata, not an attestation of model identity. Hashes identify captured bytes; they do not certify semantic correctness, fresh reasoning or an unmodified external source.

Supported endpoints are `GET /v1/models` and `POST /v1/chat/completions`. Completion requests must use the configured model, one or more text messages, `stream` absent or false and `n` absent or 1. System, developer, user and assistant roles are accepted. Options such as `response_format`, temperature and token limits remain in the raw prompt for the responder; the relay does not perform generation or validate an assistant response against its requested schema. Tool/function calls, audio, multimodal message content and streaming are explicitly rejected.

Requests and response-wrapper files are limited to 1 MiB, JSON nesting to 64 levels and simultaneous pending requests to eight. Responses must be regular files, not symlinks. Malformed requests return 400, oversized requests 413, excess pending requests 429, malformed answers 502, shutdown 503 and an expired response deadline 504. Request paths are exact. Closing the relay releases pending response waits and records their terminal state. The response timeout begins after the bounded request body has been received; the endpoint is intended for trusted local clients, not exposed network use.

Successful evidence requires both a `delivered` receipt and the application's normal parser/validation results. A timeout, invalid answer or disconnected client is retained as such. A file may have been prepared for delivery before a client disconnects; the receipt then says `delivery_failed`. Do not report those records as successful model calls.

## Focused verification

```bash
./mvnw -B -ntp -pl taxonomy-tooling -am test \
  -Dtest=InteractiveCompletionRelayTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.build.cache.enabled=false
```

The tests exercise real local HTTP requests and atomic spool responses, unique IDs, byte-level receipts, no invented usage, independent repeated requests, unsupported inputs, limits, timeout, malformed/partial replies, response symlinks, credentials exclusion and orderly shutdown. Their manually supplied response strings test transport behavior only; they are not presented as live assistant inference or taxonomy QA.

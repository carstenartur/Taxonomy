# Custom OpenAI-Compatible LLM

Taxonomy can call an operator-controlled language-model server through the OpenAI Chat Completions JSON format. This is suitable for an on-premises gateway, a model server in the same Docker or Kubernetes network, or any remote service that exposes a compatible endpoint.

## Required configuration

```bash
export LLM_PROVIDER=CUSTOM_OPENAI
export CUSTOM_LLM_URL=http://llm-server:8080/v1/chat/completions
export CUSTOM_LLM_MODEL=your-served-model-name
```

`CUSTOM_LLM_URL` must be the complete HTTP or HTTPS Chat Completions endpoint and must end in `/chat/completions`. `CUSTOM_LLM_MODEL` is copied into the request body unchanged.

Authentication is optional:

```bash
export CUSTOM_LLM_API_KEY=your-token
```

When `CUSTOM_LLM_API_KEY` is empty, Taxonomy deliberately sends no `Authorization` header. When it is set, Taxonomy sends `Authorization: Bearer <token>`. There is no `CUSTOM_OPENAI_API_KEY` setting.

## Protocol contract

Taxonomy sends one user message:

```json
{
  "model": "your-served-model-name",
  "messages": [
    {
      "role": "user",
      "content": "Taxonomy analysis prompt"
    }
  ]
}
```

The server must return the generated text at `choices[0].message.content`:

```json
{
  "choices": [
    {
      "message": {
        "content": "{\"BP-1000\": {\"score\": 80, \"reason\": \"...\"}}"
      }
    }
  ]
}
```

Streaming responses and provider-specific native APIs are not used by this integration.

## Semantic embeddings are a separate opt-in capability

The selected chat provider does not enable the local ONNX embedding model. Taxonomy now uses safe defaults:

```bash
TAXONOMY_EMBEDDING_ENABLED=false
TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false
```

With these defaults, startup, taxonomy import and Hibernate Search indexing do not initialise or download `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`; documents are indexed without vector values.

For a generative custom LLM together with local ONNX semantic search, keep the
chat provider configured and enable embeddings separately. The node/relation
vector index initializes at startup independently of the chat provider; a
catalogue-only worker does not build a global index. For a mounted model without
outbound downloads:

```bash
export LLM_PROVIDER=CUSTOM_OPENAI
export CUSTOM_LLM_URL=https://llm.example.com/v1/chat/completions
export CUSTOM_LLM_MODEL=your-served-model-name
export TAXONOMY_EMBEDDING_ENABLED=true
export TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false
export TAXONOMY_EMBEDDING_MODEL_PROFILE=MULTILINGUAL_MINILM_L12
export TAXONOMY_EMBEDDING_MODEL_DIR=/models/multilingual-minilm
```

Provision this directory with the [pinned model provisioning guide](../testing/multilingual-model-provisioning.md). The default multilingual profile uses 384-dimensional normalized mean-pooled vectors, a 128-token limit and no query or document prefix. `TAXONOMY_EMBEDDING_MODEL_NAME` defaults to empty and then uses the selected profile's pinned URL; an unset query prefix uses the profile default, while an explicitly empty value stays empty.

A runtime download requires two explicit decisions:

```bash
export TAXONOMY_EMBEDDING_ENABLED=true
export TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=true
```

For production containers and Kubernetes, prefer a mounted, verified model directory over runtime downloads.

For the legacy English model, provision with `MODEL_PROFILE=BGE_SMALL_EN`, set `TAXONOMY_EMBEDDING_MODEL_PROFILE=BGE_SMALL_EN`, and mount its matching bundle. That profile retains CLS pooling, a 512-token limit and the English query prefix. Rebuild the semantic index and discard cached vectors when changing profiles.

## Docker Compose example

When both services are in the same Compose project, use the LLM service name as the host. Do not use `localhost`, because that would refer to the Taxonomy container itself.

```yaml
services:
  taxonomy:
    image: ghcr.io/carstenartur/taxonomy:latest
    ports:
      - "8080:8080"
    environment:
      LLM_PROVIDER: CUSTOM_OPENAI
      CUSTOM_LLM_URL: http://llm-server:8000/v1/chat/completions
      CUSTOM_LLM_MODEL: architecture-model
      TAXONOMY_EMBEDDING_ENABLED: "false"
      TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD: "false"
    depends_on:
      - llm-server

  llm-server:
    image: your-openai-compatible-server:version
    expose:
      - "8000"
```

For a model server running directly on the Docker host, use a host address that is reachable from the container.

## Selection, availability and diagnostics

The provider appears in the frontend selector only when both `CUSTOM_LLM_URL` and `CUSTOM_LLM_MODEL` are valid and non-empty. An API key is not required.

Set `LLM_PROVIDER=CUSTOM_OPENAI` to make it the global default. Without an explicit provider, Taxonomy preserves the existing cloud-provider key priority and uses the custom endpoint after those providers when its URL and model are configured.

Check the resolved status:

```bash
curl http://localhost:8080/api/ai-status
```

The expected provider name is `Custom OpenAI-compatible`, and `CUSTOM_OPENAI` should be present in `availableProviders`.

The admin-only `/api/diagnostics` endpoint distinguishes provider configuration from authentication. An unauthenticated compatible endpoint is reported with `providerConfigured=true`, `apiKeyConfigured=false` and `authenticationMode=NONE`.

Configuration and call failures are reported with their real causes, including missing `CUSTOM_LLM_URL`, missing `CUSTOM_LLM_MODEL`, invalid URI or path, endpoint unreachable, and HTTP 401/403. Authentication errors mention only the optional `CUSTOM_LLM_API_KEY` variable.

## Completion and context limits

Relation discovery requests opt in to a strict JSON schema, `temperature=0`, and an explicit output token limit. Other requests retain their existing wire format. Configure the custom endpoint with:

| Environment variable | Spring property | Default | Meaning |
| --- | --- | --- | --- |
| `CUSTOM_LLM_CONTEXT_WINDOW_TOKENS` | `custom.llm.context-window-tokens` | `0` | Model context size. Zero means unknown. |
| `CUSTOM_LLM_MAX_OUTPUT_TOKENS` | `custom.llm.max-output-tokens` | `4096` | Reserved output allowance and ceiling for opted completion requests. |
| `CUSTOM_LLM_STRUCTURED_OUTPUT_ENABLED` | `custom.llm.structured-output-enabled` | `true` | Send `response_format.type=json_schema` for opted requests. Disable for servers that do not support it. |

Set the context window to the value configured on the model server, for example `8192` for a small deployment. The prompt budget reserves the configured output allowance first and uses the existing four-characters-per-token estimate for input admission. This estimate is not a tokenizer count; multilingual text, schema overhead, and model templates can consume more tokens. Leave room when setting the limits. The provider's actual context enforcement remains authoritative.

Context configuration accepts `0` (unknown) through `1000000000`; output accepts `1` through `1000000`. A known context window must exceed the output allowance. Invalid capacities make the custom provider unavailable with an actionable configuration status and reject explicit custom requests before HTTP.

Unsupported schema requests fail with the normal bounded provider error. Taxonomy does not retry unrelated HTTP 400 or authentication failures without the schema. Set the structured-output flag explicitly for incompatible servers. A `length`, `content_filter`, or error finish reason is incomplete output, and its partial text remains diagnostic data instead of a successful relation answer.

## Rate limit and timeout

The custom provider has no outgoing RPM limit by default. A runtime preference can set one with:

```text
llm.rpm.custom_openai
```

The shared LLM timeout and retry preferences also apply:

```text
llm.timeout.seconds
llm.retry.max
```

## Fresh session-backed integration checks

An assistant session can serve fresh responses through a local OpenAI-compatible
HTTP relay when an independent model server is unavailable. Taxonomy still sends
its original messages through `CUSTOM_OPENAI`; the session reads each new request
and supplies its answer through the relay. This checks the provider transport and
application handling of those answers. It does not measure a vLLM inference engine
or establish the quality or speed of a separately deployed model.

For this explicit QA path, bind the relay to loopback, use a distinct model name,
retain request and response hashes, and stop the relay when the run finishes.
Keep prompt/response evidence under `target/` and separate time awaiting the
assistant from application and relay processing. Do not substitute recorded
responses or silently fall back to a remote provider.

The relay and Maven must share a reachable network context. Some execution tools
share files while isolating loopback networking between invocations. In that
environment, start the relay and Maven within the same invocation and use a shared
file spool to provide fresh answers. A connection-refused failure before the
relay receives any request is a transport/setup failure, not a model result.

The opt-in [architecture dialogue test](../testing/architecture-quality-2026-11-21.md#optionaler-live-test-des-architekturdialogs-mit-vllm)
uses two questions about a synthetic editor checkpoint. Its explicit
`real-llm` selection and disabled Maven build cache ensure that a requested live
run obtains new completions. See the [23 November QA record](../qa/2026-11-23-automatic-continuation-performance.md)
for the fresh session-backed run and its measurement boundaries.

## Security guidance

- Treat `CUSTOM_LLM_URL` as trusted operator configuration. It controls an outbound server-side HTTP connection.
- Use HTTPS and a bearer token when the model server is reached over an untrusted network.
- Keep an unauthenticated model server on an isolated internal network.
- Do not put secrets into `CUSTOM_LLM_URL`; use `CUSTOM_LLM_API_KEY`.
- URLs with embedded user information are rejected.
- Keep runtime embedding downloads disabled in production unless an explicit egress decision was made.
- Ensure the model server accepts the maximum prompt size generated by the selected analysis workflow.

## Compare two requirements against a live vLLM server

`RequirementArchitectureVllmLiveTest` starts the application with an isolated, three-node information-product catalogue. It takes both entries from the packaged bilingual workbook. It sends two municipal-library requirements to `POST /api/analyze` with the same catalogue, source and scope. One asks for an IT service catalogue, the other for a building access list.

On a CPU host with the [vLLM 0.18.0 CPU build and runtime prerequisites](https://docs.vllm.ai/en/v0.18.0/getting_started/installation/cpu/) installed, start the model used in the QA run in a separate terminal. The first start requires access to the pinned model weights or an existing Hugging Face cache.

```bash
VLLM_CPU_KVCACHE_SPACE=1 VLLM_CPU_OMP_THREADS_BIND=nobind OMP_NUM_THREADS=4 \
  vllm serve Qwen/Qwen2.5-0.5B-Instruct \
    --revision 7ae557604adf67be50417f59c2c2f167def9a775 \
    --served-model-name qa-qwen --host 127.0.0.1 --port 8000 \
    --dtype bfloat16 --max-model-len 8192 --max-num-seqs 1 --enforce-eager
```

With Java 25, run this command from the checkout. For an existing vLLM deployment, set its completion URL and served model name instead. For a protected endpoint, supply its key to Taxonomy through `CUSTOM_LLM_API_KEY`.

```bash
export CUSTOM_LLM_URL=http://127.0.0.1:8000/v1/chat/completions
export CUSTOM_LLM_MODEL=qa-qwen
export CUSTOM_LLM_CONTEXT_WINDOW_TOKENS=8192
./mvnw -B -pl taxonomy-app -am test -Pvllm-architecture
```

The default forked run writes both analysis requests and results, the raw provider responses and `comparison.json` to a new `taxonomy-app/target/vllm-architecture/run-*/` directory. With `-DforkCount=0`, the directory is under the reactor root’s `target/vllm-architecture/`. The test disables mock and replay, requires fresh response IDs from the selected model for both requirements, checks score coverage and verifies the graph against the assessed catalogue. The comparison records whether scores, selected nodes and relationships changed. Identical or incorrect judgments remain visible for model evaluation. Model accuracy and latency depend on the deployment.

## Troubleshooting

| Symptom | Actionable cause |
|---|---|
| `CUSTOM_OPENAI` is absent from the provider selector | Set both `CUSTOM_LLM_URL` and `CUSTOM_LLM_MODEL`; use a valid HTTP(S) URI ending in `/chat/completions` |
| `MISSING_URL` or `MISSING_MODEL` | Set the named required variable; an API key cannot compensate for either missing value |
| `INVALID_PATH` | Point `CUSTOM_LLM_URL` to the Chat Completions endpoint, not `/v1`, `/models` or the server root |
| Connection refused/unreachable | Check service name, DNS, network policy and whether the endpoint is listening from the Taxonomy container |
| HTTP 401/403 | The endpoint requires or rejected bearer authentication; set or correct optional `CUSTOM_LLM_API_KEY` |
| Response is received but scores are empty | The server does not return text at `choices[0].message.content`, or the model ignored the required JSON response format |
| Calls time out | Increase `llm.timeout.seconds` and confirm that the model is loaded before analysis starts |
| ONNX model is not used | Enable embeddings explicitly and provide a mounted model or explicitly allow download |

See [AI Providers](../en/AI_PROVIDERS.md) and the [Configuration Reference](../en/CONFIGURATION_REFERENCE.md) for the surrounding provider configuration.

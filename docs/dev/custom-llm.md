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

With these defaults, startup, taxonomy import and Hibernate Search indexing do not initialise or download `BAAI/bge-small-en-v1.5`; documents are indexed without vector values.

To use a mounted model without outbound downloads:

```bash
export TAXONOMY_EMBEDDING_ENABLED=true
export TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false
export TAXONOMY_EMBEDDING_MODEL_DIR=/models/bge-small-en-v1.5
```

A runtime download requires two explicit decisions:

```bash
export TAXONOMY_EMBEDDING_ENABLED=true
export TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=true
```

For production containers and Kubernetes, prefer a mounted, verified model directory over runtime downloads.

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

## Security guidance

- Treat `CUSTOM_LLM_URL` as trusted operator configuration. It controls an outbound server-side HTTP connection.
- Use HTTPS and a bearer token when the model server is reached over an untrusted network.
- Keep an unauthenticated model server on an isolated internal network.
- Do not put secrets into `CUSTOM_LLM_URL`; use `CUSTOM_LLM_API_KEY`.
- URLs with embedded user information are rejected.
- Keep runtime embedding downloads disabled in production unless an explicit egress decision was made.
- Ensure the model server accepts the maximum prompt size generated by the selected analysis workflow.

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

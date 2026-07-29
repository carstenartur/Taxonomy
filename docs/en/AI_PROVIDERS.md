# AI Providers

The Taxonomy Architecture Analyzer supports multiple LLM (Large Language Model) providers for AI-powered analysis. This guide explains how providers are selected, configured, and monitored at runtime.

## Table of Contents

- [Overview](#overview)
- [Supported Providers](#supported-providers)
- [Custom OpenAI-Compatible Endpoint](#custom-openai-compatible-endpoint)
- [Provider Selection](#provider-selection)
- [Per-Request Provider Override](#per-request-provider-override)
- [AI Status Indicator](#ai-status-indicator)
- [LLM Diagnostics](#llm-diagnostics)
- [Rate Limiting and Throttling](#rate-limiting-and-throttling)
- [Mock Mode](#mock-mode)
- [Prompt Template Editor](#prompt-template-editor)
- [LLM Communication Log](#llm-communication-log)
- [Timeout Configuration](#timeout-configuration)
- [Environment Variables](#environment-variables)
- [Related Documentation](#related-documentation)

---

## Overview

The `LlmService` is the central component for AI analysis. It supports eight runtime providers and implements budget-constrained scoring, streaming analysis, rate limiting, diagnostics, and per-request provider selection.

---

## Supported Providers

| Provider | Default model | Configuration | API key required |
|---|---|---|---|
| **Google Gemini** | gemini-3-flash-preview | `GEMINI_API_KEY` | Yes |
| **OpenAI** | gpt-4o-mini | `OPENAI_API_KEY` | Yes |
| **DeepSeek** | deepseek-chat | `DEEPSEEK_API_KEY` | Yes |
| **Qwen** (Alibaba) | qwen-plus | `DASHSCOPE_API_KEY` | Yes |
| **Llama** | llama3.1-70b | `LLAMA_API_KEY` | Yes |
| **Mistral** | mistral-small-latest | `MISTRAL_API_KEY` | Yes |
| **CUSTOM_OPENAI** | operator-defined | `CUSTOM_LLM_URL`, `CUSTOM_LLM_MODEL`, optionally `CUSTOM_LLM_API_KEY` | No |
| **LOCAL_ONNX** | bge-small-en-v1.5 | local embedding settings | No |

`CUSTOM_OPENAI` is a generative LLM integration for OpenAI-compatible Chat Completions servers. `LOCAL_ONNX` is different: it provides local embedding-based similarity scoring and does not generate textual LLM responses.

---

## Custom OpenAI-Compatible Endpoint

Use `CUSTOM_OPENAI` to connect Taxonomy to a self-hosted or otherwise operator-controlled OpenAI-compatible server:

```bash
export LLM_PROVIDER=CUSTOM_OPENAI
export CUSTOM_LLM_URL=http://llm-server:8000/v1/chat/completions
export CUSTOM_LLM_MODEL=architecture-model
```

Authentication is optional:

```bash
export CUSTOM_LLM_API_KEY=secret-token
```

With an empty API key, Taxonomy sends no `Authorization` header. With a configured key, it sends Bearer authentication. Both URL and model are mandatory, and the URL must be a complete `http://` or `https://` Chat Completions endpoint.

See [Custom OpenAI-Compatible LLM](../dev/custom-llm.md) for the JSON contract, Docker examples, security guidance, and troubleshooting.

---

## Provider Selection

The system uses the following priority to determine which provider handles a request:

1. **Per-request override** — selected by the frontend for an individual request.
2. **Explicit configuration** — `LLM_PROVIDER` or `llm.provider`.
3. **Automatic detection** — the first complete configuration in this order:
   - GEMINI → OPENAI → DEEPSEEK → QWEN → LLAMA → MISTRAL → CUSTOM_OPENAI
4. **Default** — GEMINI when no provider configuration can be detected.

For the standard cloud providers, automatic detection checks the corresponding API key. For `CUSTOM_OPENAI`, it checks for a valid `CUSTOM_LLM_URL` and a non-empty `CUSTOM_LLM_MODEL`; its API key remains optional.

### Checking the Active Provider

```bash
curl http://localhost:8080/api/ai-status
```

Example:

```json
{
  "available": true,
  "provider": "Custom OpenAI-compatible",
  "availableProviders": ["LOCAL_ONNX", "CUSTOM_OPENAI"]
}
```

`availableProviders` always contains `LOCAL_ONNX`. Cloud providers are added when their API key is configured; `CUSTOM_OPENAI` is added when URL and model are complete and valid.

---

## Per-Request Provider Override

The frontend can select a provider for an individual analysis request:

1. `llmService.setRequestProvider(provider)` stores the override before analysis.
2. `getActiveProvider()` evaluates the request override first.
3. `llmService.clearRequestProvider()` removes it in a `finally` block.

This allows users to compare providers without changing the global deployment configuration.

---

## AI Status Indicator

The navigation bar shows the current AI state:

| Badge | State | Meaning |
|---|---|---|
| 🟢 **AI: [Provider Name]** | Full | A generative HTTP provider or mock mode is available. |
| 🟡 **AI: [Provider Name]** | Limited | Local embedding-based analysis is available. |
| 🔴 **AI: Unavailable** | Unavailable | The selected provider lacks mandatory configuration and no usable local fallback is loaded. |
| ⚠️ **AI: Unknown** | Error | The status request failed or the application is still starting. |

For `CUSTOM_OPENAI`, verify both `CUSTOM_LLM_URL` and `CUSTOM_LLM_MODEL`. A missing `CUSTOM_LLM_API_KEY` is valid for an unauthenticated trusted server.

---

## LLM Diagnostics

The admin diagnostics panel shows:

- active provider;
- whether its connection configuration is present;
- a masked API-key prefix when a real key is configured;
- total, successful, and failed calls;
- the time and result of the last call;
- the last error.

```bash
curl -u admin:password http://localhost:8080/api/diagnostics
```

Use **Test Connection** in the diagnostics panel to send a real test request.

---

## Rate Limiting and Throttling

### Outgoing LLM throttle

Each HTTP gateway has an independent sliding-window throttle. Provider-specific preferences use:

```text
llm.rpm.<provider-id-in-lowercase>
```

For the custom provider this is:

```text
llm.rpm.custom_openai
```

The custom endpoint defaults to `0`, meaning no outgoing RPM throttle. The Gemini gateway retains its free-tier-oriented default.

### Incoming rate limit

`TAXONOMY_RATE_LIMIT_PER_MINUTE` limits incoming LLM-backed requests per client and returns HTTP `429` when exceeded.

---

## Mock Mode

Enable mock mode to bypass real LLM calls:

```bash
LLM_MOCK=true
```

Mock mode returns deterministic prepared scores, needs no API key, and is intended for tests, screenshots, and offline development.

---

## Prompt Template Editor

The admin prompt-template editor changes the instructions sent to the active provider without redeploying. Saved changes apply to the next analysis request.

---

## LLM Communication Log

The admin communication log records the prompt and raw generated text for analysis calls. Use it to inspect response-format violations, unexpected scores, and prompt effectiveness. Secrets and authorization headers are not written into the prompt body.

---

## Timeout Configuration

The runtime preference `llm.timeout.seconds` controls the HTTP read timeout. `llm.retry.max` controls retries for retryable server errors and timeouts.

```bash
curl -u admin:password -X PUT \
  -H "Content-Type: application/json" \
  -d '{"llm.timeout.seconds": 90, "llm.retry.max": 2}' \
  http://localhost:8080/api/preferences
```

Self-hosted models may require a longer timeout during first-model load.

---

## Environment Variables

| Variable | Description |
|---|---|
| `LLM_PROVIDER` | Explicit provider selection, including `CUSTOM_OPENAI` and `LOCAL_ONNX` |
| `LLM_MOCK` | Enable deterministic mock mode |
| `GEMINI_API_KEY` | Google Gemini API key |
| `OPENAI_API_KEY` | OpenAI API key |
| `DEEPSEEK_API_KEY` | DeepSeek API key |
| `DASHSCOPE_API_KEY` | Alibaba DashScope API key for Qwen |
| `LLAMA_API_KEY` | Llama API key |
| `MISTRAL_API_KEY` | Mistral API key |
| `CUSTOM_LLM_URL` | Complete custom OpenAI-compatible Chat Completions endpoint |
| `CUSTOM_LLM_MODEL` | Model identifier sent to the custom endpoint |
| `CUSTOM_LLM_API_KEY` | Optional Bearer token for the custom endpoint |
| `TAXONOMY_RATE_LIMIT_PER_MINUTE` | Incoming rate limit for LLM-backed endpoints |

See the [Configuration Reference](CONFIGURATION_REFERENCE.md) for the canonical property mapping.

---

## Related Documentation

- [Custom OpenAI-Compatible LLM](../dev/custom-llm.md)
- [Configuration Reference](CONFIGURATION_REFERENCE.md)
- [Preferences](PREFERENCES.md)
- [Security](SECURITY.md)
- [API Reference](API_REFERENCE.md)

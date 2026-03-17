# AI Providers

The Taxonomy Architecture Analyzer supports multiple LLM (Large Language Model) providers for AI-powered analysis. This guide explains how providers are selected, configured, and monitored at runtime.

## Table of Contents

- [Overview](#overview)
- [Supported Providers](#supported-providers)
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

The `LlmService` is the central component for AI analysis. It supports 7 providers and implements budget-constrained scoring, streaming analysis, rate limiting, and diagnostics. Provider selection uses a 3-tier priority system, and providers can be switched at runtime without restarting the application.

---

## Supported Providers

| Provider | Model | API Key Variable | Requires API Key |
|---|---|---|---|
| **Google Gemini** | gemini-3-flash-preview | `GEMINI_API_KEY` | Yes |
| **OpenAI** | gpt-4o-mini | `OPENAI_API_KEY` | Yes |
| **DeepSeek** | deepseek-chat | `DEEPSEEK_API_KEY` | Yes |
| **Qwen** (Alibaba) | qwen-plus | `QWEN_API_KEY` | Yes |
| **Llama** | llama3.1-70b | `LLAMA_API_KEY` | Yes |
| **Mistral** | mistral-small-latest | `MISTRAL_API_KEY` | Yes |
| **LOCAL_ONNX** | bge-small-en-v1.5 | — | No (runs locally) |

`LOCAL_ONNX` uses a local embedding model and does not require any API key. It is always available as a fallback.

---

## Provider Selection

The system uses a **3-tier priority** to determine which provider handles a request:

1. **Per-request override** (highest priority) — Set via `ThreadLocal` when the frontend selects a specific provider for an individual request.
2. **Explicit configuration** — Set via the `LLM_PROVIDER` environment variable or `llm.provider` property.
3. **Auto-detection** — The system checks for API keys in this order:
   - GEMINI → OPENAI → DEEPSEEK → QWEN → LLAMA → MISTRAL
   - The first provider with a configured API key is selected.
4. **Default** — If no API key is found, defaults to GEMINI (analysis will fail until an API key is configured).

### Checking the Active Provider

```bash
curl http://localhost:8080/api/ai-status
```

Response:

```json
{
  "available": true,
  "provider": "Google Gemini",
  "availableProviders": ["Google Gemini", "LOCAL_ONNX"]
}
```

The `availableProviders` list always includes `LOCAL_ONNX`. Additional providers appear when their API keys are configured.

---

## Per-Request Provider Override

The frontend can select a specific provider for individual analysis requests. This is implemented using a `ThreadLocal<LlmProvider>` in the `LlmService`:

1. Before the analysis, `llmService.setRequestProvider(provider)` sets the override.
2. `getActiveProvider()` checks the `ThreadLocal` first (priority 0).
3. After the analysis, `llmService.clearRequestProvider()` is called in a `finally` block.

This allows users to compare results across different providers without changing the global configuration.

---

## AI Status Indicator

The navigation bar shows a badge indicating the current AI status:

| Badge | State | Meaning |
|---|---|---|
| 🟢 **AI: [Provider Name]** | Available | AI analysis is active. Shows the provider name (e.g. "Google Gemini"). |
| 🔴 **AI: Unavailable** | Unavailable | No LLM API key configured. The **Analyze with AI** button is disabled. |
| ⚠️ **AI: Unknown** | Error | Status check failed (network error or server starting up). Auto-refreshes every 30 seconds. |

If you see a red badge:
- Set one of the LLM API keys (`GEMINI_API_KEY`, `OPENAI_API_KEY`, etc.) and restart, or
- Set `LLM_PROVIDER=LOCAL_ONNX` for offline analysis without any API key.

When AI is unavailable, an **inline warning message** appears below the Analyze button listing the required environment variables.

---

## LLM Diagnostics

The **LLM Diagnostics Panel** (admin only) shows runtime statistics:

- Provider name and model version
- Whether an API key is configured (with masked prefix)
- Total number of API calls
- Successful and failed call counts
- Average response latency
- Last call time and status

```bash
curl -u admin:password http://localhost:8080/api/ai-diagnostics
```

Example response:

```json
{
  "provider": "Google Gemini",
  "apiKeyConfigured": true,
  "apiKeyPrefix": "sk-****",
  "totalCalls": 347,
  "successfulCalls": 344,
  "failedCalls": 3,
  "lastCallTime": "2025-01-15T10:30:45Z",
  "lastCallSuccess": true
}
```

Click **Test Connection** in the diagnostics panel to send a test request to the LLM provider and confirm it is responding correctly.

---

## Rate Limiting and Throttling

Two separate rate-limiting mechanisms protect the system:

### Outgoing LLM Throttle

Controlled by the `llm.rpm` preference (default: 5 requests/minute). Uses a sliding-window algorithm:

1. A FIFO queue records timestamps of recent LLM API calls.
2. Before each call, the system checks if the oldest entry in the queue is older than 60 seconds.
3. If the limit would be exceeded, the thread sleeps until the oldest call exits the window.
4. A 50ms grace period is added for clock drift.

This can be adjusted at runtime via the [Preferences](PREFERENCES.md) API.

### Incoming Rate Limit

Controlled by `TAXONOMY_RATE_LIMIT_PER_MINUTE` (default: 10 requests/minute). Applied to analysis endpoints:

- `POST /api/analyze`
- `POST /api/analyze-stream`
- `POST /api/analyze-node`
- `POST /api/justify-leaf`

Returns HTTP `429 Too Many Requests` when exceeded.

---

## Mock Mode

For testing and development, enable mock mode to bypass real LLM calls:

```bash
LLM_MOCK=true
```

Mock mode:
- Returns pre-computed scores from `classpath:mock-scores/secure-voice-comms.json`
- Falls back to hardcoded root scores with deterministic variation
- Does not require any API key
- Used in CI, screenshots, and development environments

---

## Prompt Template Editor

The **Prompt Templates Editor** (admin only) allows customising the instructions sent to the LLM without redeploying:

1. Select a prompt template from the dropdown.
2. Edit the template text.
3. Click **Save** to persist the change, or **Reset** to restore the built-in default.

Changes take effect immediately for the next analysis.

---

## LLM Communication Log

The **LLM Communication Log** (admin only) records the full prompt and raw response for each analysis operation. Use this for:

- Debugging unexpected scoring results
- Verifying prompt effectiveness
- Auditing LLM interactions

---

## Timeout Configuration

The HTTP read timeout for LLM API calls is configurable at runtime:

| Setting | Default | Description |
|---|---|---|
| `llm.timeout.seconds` | `30` | Seconds to wait for an LLM response |

Update via [Preferences](PREFERENCES.md):

```bash
curl -u admin:password -X PUT \
  -H "Content-Type: application/json" \
  -d '{"llm.timeout.seconds": 45}' \
  http://localhost:8080/api/preferences
```

The timeout is applied dynamically to the `RestTemplate` without restarting.

---

## Environment Variables

| Variable | Description |
|---|---|
| `GEMINI_API_KEY` | Google Gemini API key |
| `OPENAI_API_KEY` | OpenAI API key |
| `DEEPSEEK_API_KEY` | DeepSeek API key |
| `QWEN_API_KEY` | Alibaba Qwen API key |
| `LLAMA_API_KEY` | Llama API key |
| `MISTRAL_API_KEY` | Mistral API key |
| `LLM_PROVIDER` | Explicit provider selection (overrides auto-detection) |
| `LLM_MOCK` | Enable mock mode (`true`/`false`) |
| `TAXONOMY_RATE_LIMIT_PER_MINUTE` | Incoming rate limit for analysis endpoints |

See [Configuration Reference](CONFIGURATION_REFERENCE.md) for the complete list of environment variables.

---

## Related Documentation

- [Configuration Reference](CONFIGURATION_REFERENCE.md) — All environment variables and startup configuration
- [Preferences](PREFERENCES.md) — Runtime preference management
- [User Guide](USER_GUIDE.md) — AI Status Indicator and Admin Mode (§14)
- [Security](SECURITY.md) — Authentication and access control
- [API Reference](API_REFERENCE.md) — Full REST API documentation

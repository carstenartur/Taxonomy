# LLM & Gemini API Reference

> **Read this when**: You are working with LLM integration, the Gemini API, rate-limit related issues, or any endpoint that calls the language model.

---

## GEMINI_API_KEY — Availability and Rate-Limit Guidance

`GEMINI_API_KEY` is configured as a **repository secret** and is injected into the `Generate Documentation Screenshots` workflow automatically. It is also available to Copilot agents running in GitHub Actions via `secrets.GEMINI_API_KEY`.

### Free-tier limits (Gemini 2.0 Flash / Gemini 1.5 Flash)

The key uses the **Google AI Studio free tier**, which has hard rate limits:

| Limit | Value |
|---|---|
| Requests per minute (RPM) | 15 |
| Requests per day (RPD) | 1 500 |
| Tokens per minute (TPM) | 1 000 000 |

> These limits are shared across **all** uses of the key — the running application, CI workflows, and any agent-triggered calls all draw from the same quota.

### Rules for agents

- **Do not call the LLM unnecessarily.** Never invoke `/api/analyze`, `/api/analyze-stream`, `/api/analyze-node`, `/api/justify-leaf`, or any other LLM-backed endpoint in unit tests or exploratory scripts.
- **Prefer screenshots 1–14 for local iteration.** They require no API key and run in under 2 minutes. Only add LLM-dependent tests (15+) when the screenshot genuinely requires a scored/analysed UI state.
- **Do not trigger the `generate-screenshots.yml` workflow repeatedly** in quick succession. Each full run with the key uses up to ~12 LLM calls (one per analysis request). Wait for a run to finish before triggering another.
- **Do not hardcode or log the key.** The secret is masked in CI logs; keep it that way.
- If a task only needs to verify that the LLM integration compiles or that the API wiring is correct, use the existing `DiagnosticsWithApiKeyContainerIT` tests, which make at most 1–2 LLM calls per run.

## Gemini API Rate Limits in Tests

The Gemini Free Tier has a rate limit of approximately 15 requests per minute. This affects:
- `ScreenshotGeneratorIT.java`: Tests 15–26 make LLM calls and need `rateLimitDelay()` (10s pause) between them.
- The `generate-screenshots.yml` workflow: Must account for total runtime of ~3–4 minutes for LLM-dependent screenshots.
- Any new test that triggers LLM analysis must include a rate-limit delay.

The `LlmService` throws `LlmRateLimitException` on HTTP 429 or `RESOURCE_EXHAUSTED`. The screenshot tests do NOT have automatic retry — they rely on delays to stay within limits, plus `failsafe.rerunFailingTestsCount=1` in the workflow as a safety net.

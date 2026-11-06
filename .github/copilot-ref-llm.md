# LLM Test Reference

> **Read this when**: You are working with provider integration, diagnostics, rate limits or analysis tests.

## Documentation screenshots

Use the existing JUnit integration-test entry point:

```bash
./mvnw -B verify -Pscreenshots
```

The screenshot owners run with `LLM_MOCK=true` or deterministic product-demo
playback. Semantic search uses the pinned local ONNX model. No external provider
key or live LLM request is needed, including for scored analyses and leaf reasons.
The workflow delegates to this same command. Do not add provider secrets, rate-limit
sleeps or retries to hide failures in deterministic documentation captures.

See [capture owners and prerequisites](copilot-ref-screenshots.md) for output,
provenance and the distinction between application and component examples.

## Live provider verification

Keep live-provider calls in the explicitly selected real-LLM profiles/tests.
Never hardcode or log provider credentials. Do not infer secret availability or
current provider quotas from old workflow configuration. Mock diagnostics and
local ONNX readiness do not establish that a remote provider can be reached.

HTTP 429 and `RESOURCE_EXHAUSTED` are handled by `LlmRateLimitException` and the
provider/job retry policies. Preserve those errors and their evidence; do not add
arbitrary delays to unrelated UI tests.

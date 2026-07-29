# Task: Add a New LLM Provider

## Goal

Integrate a language-model API whose protocol or capabilities are not already covered by the runtime providers, so users can select it in the analysis panel.

> Start with the stable extension anchor in
> [`docs/dev/07-extension-points.md#llm-providers`](../07-extension-points.md#llm-providers).
> Use this page for the end-to-end file, test, and documentation checklist.

---

## First decision: is code required?

Do **not** add a provider-specific enum value merely to use another OpenAI-compatible server. The built-in `CUSTOM_OPENAI` provider already supports operator-controlled OpenAI Chat Completions endpoints:

```bash
LLM_PROVIDER=CUSTOM_OPENAI
CUSTOM_LLM_URL=https://llm.example.test/v1/chat/completions
CUSTOM_LLM_MODEL=served-model-name
CUSTOM_LLM_API_KEY=optional-bearer-token
```

The API key is optional. See `docs/en/CUSTOM_LLM.md` and `docs/de/CUSTOM_LLM.md`.

Add new Java code only when at least one of these applies:

- request or response JSON is not OpenAI Chat Completions compatible;
- authentication cannot be represented by an optional Bearer token;
- the provider needs provider-specific transport, retry, streaming, or structured-output behaviour;
- the provider exposes capabilities that must be represented separately in the extension metadata.

---

## Primary entry points

| File | What to do |
|---|---|
| `taxonomy-app/src/main/java/com/taxonomy/analysis/service/LlmProvider.java` | Add the runtime enum value |
| `taxonomy-app/src/main/java/com/taxonomy/analysis/service/LlmProviderConfig.java` | Add configuration, detection, availability, URL/model, and credential handling |
| `taxonomy-app/src/main/java/com/taxonomy/analysis/service/LlmGatewayRegistry.java` | Register the transport gateway |
| `taxonomy-app/src/main/java/com/taxonomy/analysis/service/*LlmProviderExtension.java` | Publish provider metadata through the extension SPI |
| `taxonomy-app/src/main/resources/application.properties` | Map environment variables to Spring properties |

`LlmService` should remain provider-agnostic. Provider-specific HTTP behaviour belongs in an `LlmGateway` implementation, while provider selection and mandatory configuration belong in `LlmProviderConfig`.

---

## Files usually touched

- `taxonomy-app/…/analysis/service/LlmProvider.java`
- `taxonomy-app/…/analysis/service/LlmProviderConfig.java`
- `taxonomy-app/…/analysis/service/LlmGatewayRegistry.java`
- `taxonomy-app/…/analysis/service/LlmResponseParser.java` only when the response schema differs
- a provider metadata component implementing `LlmProviderExtension`
- `taxonomy-app/src/main/resources/application.properties`
- `docs/en/CONFIGURATION_REFERENCE.md` and the corresponding German documentation
- `docs/en/AI_PROVIDERS.md` and `docs/de/AI_PROVIDERS.md`

---

## Files usually not touched

- `taxonomy-domain/` — no domain-model change is normally required
- `taxonomy-dsl/` — unrelated to provider transport
- `taxonomy-export/` — export formats are provider-independent
- `taxonomy-app/…/controller/` — controllers use provider-agnostic services
- `taxonomy-app/src/main/resources/prompts/` — existing prompts should remain portable
- `taxonomy-app/src/main/resources/templates/index.html` — the provider selector is populated dynamically from `/api/ai-status`

---

## Backend endpoints

| Endpoint | Purpose |
|---|---|
| `GET /api/ai-status` | Active provider, availability level, and selectable providers |
| `POST /api/analyze` and related analysis endpoints | Provider-agnostic analysis execution |
| `GET /api/diagnostics` | Administrative provider diagnostics |

The controller must not contain a provider-specific switch.

---

## Implementation checklist

1. Add the enum value.
2. Add a matching `LlmProviderExtension` descriptor. The descriptor ID must equal the enum name.
3. Add mandatory configuration and availability logic in `LlmProviderConfig`.
4. Reuse `OpenAiCompatibleGateway` when the request is `model` plus `messages` and the response text is `choices[0].message.content`.
5. Implement a dedicated gateway only for a genuinely different protocol.
6. Register exactly one independent gateway instance so provider throttles do not share state.
7. Add environment-property mappings without placing secrets in source control.
8. Ensure missing mandatory configuration produces an unavailable provider rather than an HTTP 500.
9. Ensure optional authentication really omits the header; do not send dummy credentials to the remote server.
10. Update English and German operations documentation.

---

## Tests to run

```bash
# Fast provider/unit tests
./mvnw -pl taxonomy-app test \
  -Dtest='LlmProviderConfigBranchCoverageTest,LlmGatewayRegistryTest,LlmProviderExtensionRegistryTest,*GatewayTest'

# Canonical repository verification
./mvnw verify -DexcludedGroups="real-llm"
```

Required test coverage:

- explicit provider selection and automatic detection priority;
- mandatory versus optional configuration;
- gateway registration and independent instances;
- exact request URL, model, message body, and authentication headers;
- response-text extraction;
- rate-limit and timeout behaviour where provider-specific;
- extension descriptor completeness for every runtime enum value;
- application-context startup with empty optional configuration.

Do not call a real external LLM in the normal test suite. Any deliberately real-provider test must be tagged `@Tag("real-llm")`.

---

## Common pitfalls

1. **Duplicating `CUSTOM_OPENAI`:** Prefer deployment configuration over a new enum value for a compatible endpoint.
2. **Treating an API key as universally mandatory:** Local or trusted internal servers may intentionally be unauthenticated.
3. **Sending a dummy Bearer token:** Omit `Authorization` when authentication is disabled.
4. **Registering metadata without a runtime gateway, or vice versa:** The extension registry tests require both models to stay aligned.
5. **Changing `LlmService` for provider details:** Keep orchestration separate from transport.
6. **Assuming every 2xx body is parseable:** Verify the provider's text location and add parser tests.
7. **Logging secrets or credential-bearing URLs:** Log validation failures without exposing configuration values.
8. **Forgetting Docker networking:** From the Taxonomy container, `localhost` is not the sibling model server.

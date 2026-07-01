# Task: Add a New LLM Provider

## Goal

Integrate a new language-model API (e.g., Anthropic Claude, Cohere, Mistral-hosted)
so that users can select it in the analysis panel alongside the existing providers.

---

## Primary entry points

| File | What to do |
|---|---|
| `taxonomy-app/src/main/java/com/taxonomy/analysis/service/LlmService.java` | Add provider detection and HTTP call |
| `taxonomy-app/src/main/java/com/taxonomy/analysis/service/LlmProvider.java` | Add the new enum value |
| `taxonomy-app/src/main/java/com/taxonomy/analysis/service/LlmProviderConfig.java` | Add the configuration record |
| `taxonomy-app/src/main/resources/application.properties` | Add the API key property |

---

## Files usually touched

- `taxonomy-app/…/analysis/service/LlmService.java` — provider selection switch, HTTP call implementation
- `taxonomy-app/…/analysis/service/LlmProvider.java` — new enum constant
- `taxonomy-app/…/analysis/service/LlmProviderConfig.java` — new provider config record
- `taxonomy-app/…/analysis/service/LlmResponseParser.java` — only if the response JSON schema differs from existing providers
- `taxonomy-app/src/main/resources/application.properties` — `newprovider.api.key=${NEW_PROVIDER_API_KEY:}`
- `docs/en/CONFIGURATION_REFERENCE.md` — document the new property

---

## Files usually not touched

- `taxonomy-domain/` — no domain type changes needed for a new provider
- `taxonomy-dsl/` — DSL is unrelated to LLM provider selection
- `taxonomy-export/` — export formats are independent of LLM providers
- `taxonomy-app/…/controller/` — the analysis controller is provider-agnostic
- `taxonomy-app/src/main/resources/prompts/` — existing prompts work with any provider
  (create a new prompt file only if the new provider requires a different prompt structure)
- `taxonomy-app/src/main/resources/templates/index.html` — the provider dropdown
  is populated dynamically from `LlmProvider` enum values; no template change needed

---

## Backend endpoint(s)

| Endpoint | Controller |
|---|---|
| `POST /api/analysis/run` | `AnalysisApiController` |
| `GET /api/analysis/ai-status` | `AnalysisApiController` |

The analysis controller delegates to `LlmService` without knowing which
provider is active — the provider is resolved from `application.properties`
at startup.

---

## Frontend module(s)

- `taxonomy-app/src/main/resources/static/js/analysis.js` — reads the provider
  list from `/api/analysis/ai-status` and populates the dropdown.
  No change needed unless the UI label for the new provider requires special handling.

---

## DTOs / domain types

- `com.taxonomy.dto.AiStatusResponse` — returned by `/api/analysis/ai-status`;
  includes the active provider name. Adjust only if you add new status fields.
- `com.taxonomy.dto.AnalysisResult` — provider-agnostic; no change needed.

---

## Tests to run

```bash
# Fast: unit tests for the app module only
mvn test -pl taxonomy-app

# If you changed application.properties (context-level change)
mvn verify -DexcludedGroups="real-llm"
```

Relevant test classes:
- `LlmServiceTest` — add a test case that mocks the new provider's HTTP response
- `AnalysisApiControllerTest` — existing tests cover provider-agnostic scenarios

> Do **not** add a test that calls the real API.
> Tag any tests that require a real API key with `@Tag("real-llm")`.

---

## Documentation / screenshot updates

- `docs/en/CONFIGURATION_REFERENCE.md` — add the new API key property
- `docs/en/AI_PROVIDERS.md` — add the new provider to the supported providers table
- Screenshots: regenerate the AI status screenshot if the provider name appears in it

---

## Common pitfalls

1. **OpenAI-compatible providers:** Many new providers use an OpenAI-compatible REST
   interface. Before writing a new HTTP client, check whether `OpenAiCompatibleGateway`
   already handles it — you may only need to configure the base URL.

2. **Rate limits differ by provider:** The default `TAXONOMY_RATE_LIMIT_PER_MINUTE`
   may be too high for some providers' free tiers.
   Document the recommended value in `CONFIGURATION_REFERENCE.md`.

3. **Missing API key → graceful degradation:** `LlmService` must handle a missing key
   gracefully (provider reported as unavailable, not a 500 error).
   Follow the existing pattern for `GeminiGateway`.

4. **Response format differences:** Some providers wrap the model output in an extra
   JSON layer. Update `LlmResponseParser` if the new provider's JSON schema differs.

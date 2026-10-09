# Task: Add a New LLM Provider

Use the framework-free executable SPI in `taxonomy-extension-api`. New provider IDs
are open `ProviderId` values; adding a provider does not require changing the built-in
`LlmProvider` enum or adding a switch to a controller.

## First check whether configuration is sufficient

The built-in `CUSTOM_OPENAI` provider already supports operator-controlled OpenAI
Chat Completions endpoints:

```bash
LLM_PROVIDER=CUSTOM_OPENAI
CUSTOM_LLM_URL=https://llm.example.test/v1/chat/completions
CUSTOM_LLM_MODEL=served-model-name
CUSTOM_LLM_API_KEY=optional-bearer-token
```

Authentication is optional. See [custom endpoints](../custom-llm.md). Implement a
transport when the protocol, authentication or response decoding needs different code.

## Public contracts and packaging

| Contract | Responsibility |
|---|---|
| `com.taxonomy.extension.api.llm.ProviderId` | Normalized stable identity, up to 128 characters |
| `LlmProviderDescriptor` in the same package | Display name, capabilities and declared configuration |
| `LlmTransportExtension` | Descriptor plus one executable `LlmTransport` |
| `LlmTransport` | One request attempt, response decoding and provider name |
| `com.taxonomy.extension.api.plugin.TaxonomyPlugin` | ServiceLoader entry point returning contributions |

Build an independent Java 21 JAR against the exact `taxonomy-extension-api` SDK
version, with SDK dependencies in `provided` scope. Do not bundle host SDK classes,
Spring, host registries or PF4J. The independent [Mermaid project](../../../plugins/taxonomy-mermaid-plugin/pom.xml)
demonstrates the POM and service entry layout; a provider uses **STARTUP** mode,
where Mermaid uses DYNAMIC mode.

1. Implement `LlmTransportExtension` with a unique descriptor ID and a non-null transport.
2. Implement `TaxonomyPlugin` and list its implementation in
   `META-INF/services/com.taxonomy.extension.api.plugin.TaxonomyPlugin`.
3. Declare `Plugin-Id`, `Plugin-Version`, compatible `Plugin-Requires` and
   `Taxonomy-Plugin-Mode: STARTUP` in the JAR manifest. The current SDK API range is
   `>=1.0.0 & <2.0.0`; check the target host before releasing an artifact.
4. Stop the application, install the verified JAR in the operator plugin directory,
   and restart with the analysis feature and its dependencies installed.
5. Select the stable provider ID through the existing provider configuration/UI.
   A descriptor alone is not an executable provider.

The host owns admission, rate limits, retries, usage accounting, cancellation and
snapshot authority. A transport performs one attempt; it must not retry internally,
log or retain credentials, or start unmanaged background work. The per-call API key
is supplied by the host. Provider-specific non-secret transport settings belong to
operator configuration; the host metadata settings below do not configure a plugin's
HTTP client automatically.

## Configuration and durable work

The host reads `taxonomy.llm.providers.<lowercase-id>.api-key`, `model`,
`endpoint-identity` and `configuration-revision`. Never put credentials in a model,
endpoint identity, descriptor or manifest. External providers require a non-blank,
non-secret `configuration-revision`; change it when relevant transport configuration
changes outside the model/endpoint identity. Keep these values consistent with the
transport's actual operator settings.

New work captures the plugin ID, version, artifact SHA-256 and configuration fingerprint
before admission. A restart or worker with a different artifact/configuration rejects
that work before a provider call or usage charge. Do not silently rebind existing jobs.
Deploy compatible artifacts and message readers on all cluster workers. Provider
plugins cannot be dynamically unloaded.

## Verification

Use a deterministic transport and a local HTTP fixture, never a paid/live LLM.
Cover a new ID absent from the built-in enum, normalized duplicate rejection, missing
configuration, exact request/authentication, response parsing, host retry and usage
ownership, frozen request scope, and restart with an absent or changed artifact.

```bash
./mvnw -B -pl taxonomy-analysis -am test \
  -Dtest=ExternalProviderExecutionTest,ProviderPluginBindingTest,LlmProviderFrozenScopeTest,LlmTransportMeterTest \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B verify -Pplugin-packaging-tests
./mvnw -B verify -Pci -DrunOnnxTests=true
```

An independent plugin also needs an actual packaged-host invocation; unit registration
alone is insufficient. Update English/German operator documentation for its concrete
configuration. See [ownership and lifecycle](../extension-module-boundaries.md#optional-startup-installation-and-runtime-lifecycle).

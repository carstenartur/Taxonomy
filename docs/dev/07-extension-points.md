# Extension Points

## ExportFormatExtension

Diagram export formats (Mermaid, ArchiMate, Visio, Structurizr, and any future
format) are pluggable via `ExportFormatExtension` implementations in
`taxonomy-app/com.taxonomy.export.service`:

- `descriptor()` returns a serializable `ExportFormatDescriptor`
  (format ID, display name, file extension, HTTP content type, binary flag)
- `export(ExportContext)` converts the projected `DiagramModel` to `ExportResult` bytes

`ExportContext` carries the `DiagramModel` (post-projection) and an optional
`Map<String, Object> options` map (e.g. `"locale"` → `"de"` for Mermaid labels).

The registry is `ExportFormatExtensionRegistry`, which supports:

- lookup by format ID (`getRequired("mermaid")`, `findByFormatId("archimate")`, etc.)
- listing all registered formats (`listDescriptors()`)

### Registered built-in adapters

| Format ID      | Class                          | Output                                      |
|----------------|--------------------------------|---------------------------------------------|
| `archimate`    | `ArchiMateExportExtension`     | ArchiMate XML (`.xml`)                      |
| `mermaid`      | `MermaidExportExtension`       | Mermaid flowchart text (`.mmd`)             |
| `structurizr`  | `StructurizrExportExtension`   | Structurizr DSL text (`.dsl`)               |
| `visio`        | `VisioExportExtension`         | Visio package (`.vsdx`, binary)             |

### Add a new export format

1. Create a Spring `@Component` implementing `ExportFormatExtension` in
   `taxonomy-app/com.taxonomy.export.service`.
2. Provide a unique `descriptor().id()` (lowercase, e.g. `"bpmn"`).
3. Implement `export(ExportContext context)` using `context.diagram()` and
   optionally `context.options()`.
4. The new format is automatically registered in `ExportFormatExtensionRegistry`
   without any changes to existing format implementations.
5. If the format also needs a dedicated REST endpoint, add it to
   `ExportApiController` via `ExportFormatExtensionRegistry`.

> **Note:** The exporter logic (the format-specific conversion) should remain in
> a framework-free class in `taxonomy-export` (no Spring annotations), which the
> `@Component` adapter in `taxonomy-app` then delegates to.  This matches the
> existing adapter pattern for Mermaid, ArchiMate, Visio, and Structurizr.

---

## ReportRendererExtension

Reports can be rendered via `ReportRendererExtension` implementations in
`taxonomy-app`:

- `descriptor()` returns a serializable `ReportFormatDescriptor`
- `render(ReportRenderContext)` returns `ReportRenderResult` bytes

The registry is `ReportRendererRegistry`, which supports:

- lookup by format ID (`getRequired("markdown")`, `getRequired("html")`, etc.)
- listing registered formats (`listDescriptors()`)

### Add a new report format

1. Create a Spring `@Component` implementing `ReportRendererExtension`.
2. Provide a unique `descriptor().id()` and the target content type/file extension.
3. Implement `render(...)` using the existing `ArchitectureReport` data in
   `ReportRenderContext`.
4. If the format needs a new endpoint, wire it in `ReportApiController` via
   `ReportRendererRegistry` without changing existing format implementations.

---

## ImportProfileExtension

Framework model imports (UAF, APQC, C4, etc.) are pluggable via
`ImportProfileExtension` implementations in `taxonomy-app`:

- `descriptor()` returns a serializable `ImportProfileDescriptor`  
  (profile ID, display name, supported element/relation types, accepted file format)
- `preview(ImportInput)` performs a dry-run (parse + map, no DB write) and returns `FrameworkImportResult`
- `importData(ImportInput)` runs the full pipeline (parse → map → DSL serialize → materialize) and returns `FrameworkImportResult`

`ImportInput` carries the `InputStream` from the uploaded file and an optional target `branch`.

The registry is `ImportProfileRegistry`, which supports:

- lookup by profile ID (`getRequired("uaf")`, `findById("apqc")`, etc.)
- listing registered profiles (`listDescriptors()`)

`FrameworkImportService` is the thin facade that translates between the REST
layer (`ImportApiController`) and `ImportProfileRegistry`.  The REST API shape
(`GET /api/import/profiles`, `POST /api/import/preview/{profileId}`,
`POST /api/import/{profileId}`) is unchanged.

> **Frontend note:** UI modules should use the import API client functions in
> `js/api/import-api.js` (`ImportApi.loadProfiles()`, `ImportApi.preview()`,
> `ImportApi.execute()`) rather than constructing `/api/import/…` paths
> directly.  This ensures API changes propagate to all callers consistently.

### Add a new import profile

1. Create a Spring `@Component` implementing `ImportProfileExtension`, or
   extend `AbstractFrameworkImportProfileExtension` if your profile uses
   a standard `MappingProfile` + `ExternalParser` pair.
2. Provide a unique `descriptor().profileId()` and the appropriate metadata.
3. Implement `preview(ImportInput)` and `importData(ImportInput)` (or let the
   base class handle the pipeline and only override `profile()` and `parser()`).
4. The new profile is automatically registered in `ImportProfileRegistry` and
   exposed by `GET /api/import/profiles` without any changes to existing
   profile implementations.

---

## LlmProviderExtension

LLM providers are described via `LlmProviderExtension` implementations in
`taxonomy-app`:

- `descriptor()` returns a serializable `LlmProviderDescriptor`
  (provider ID, display name, capability flags, required configuration properties)
- `provider()` returns the corresponding `LlmProvider` enum constant

The `LlmProviderDescriptor` captures:

| Field | Type | Description |
|---|---|---|
| `providerId` | `String` | Matches `LlmProvider.name()` (e.g. `"GEMINI"`) |
| `providerName` | `String` | Human-readable name (e.g. `"Gemini"`) |
| `requiresApiKey` | `boolean` | `true` for cloud providers; `false` for `LOCAL_ONNX` |
| `supportsStreaming` | `boolean` | `true` if streaming responses are supported |
| `supportsStructuredOutput` | `boolean` | `true` if JSON-schema structured output is supported |
| `supportsLocalExecution` | `boolean` | `true` for `LOCAL_ONNX`; `false` for all cloud providers |
| `configurationProperties` | `List<String>` | Spring property keys required (e.g. `["gemini.api.key"]`) |

The registry is `LlmProviderExtensionRegistry`, which supports:

- lookup by `LlmProvider` enum (`getRequired(LlmProvider.GEMINI)`)
- lookup by provider ID string (`findById("gemini")`, case-insensitive)
- listing all registered descriptors (`listDescriptors()`)

The actual HTTP communication continues to use `LlmGateway` and `LlmGatewayRegistry`.
`LlmProviderExtension` is the metadata/descriptor layer; it does not replace the gateway.

### Add a new LLM provider

1. Add a new constant to the `LlmProvider` enum.
2. Create a Spring `@Component` implementing `LlmProviderExtension`.
3. Return a `LlmProviderDescriptor` with a `providerId` matching the enum constant name.
4. Implement `provider()` returning the new enum constant.
5. Register a gateway in `LlmGatewayRegistry` for the new constant
   (typically a new `OpenAiCompatibleGateway` instance if the provider is OpenAI-compatible).
6. Add API key injection in `LlmProviderConfig` and wire it into `getApiKey(...)` and
   `getAvailableProviders()`.
7. Add a unit test asserting that `LlmProviderExtensionRegistry.getRequired(NEW_PROVIDER)`
   returns an extension whose `descriptor().providerId()` matches the enum name.

The new provider is automatically listed by `LlmProviderExtensionRegistry.listDescriptors()`
without any changes to existing provider implementations.

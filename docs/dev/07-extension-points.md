# Extension Points

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

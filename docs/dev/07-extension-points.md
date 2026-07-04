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

# Task: Add a New Export Format

## Goal

Add a new output format (e.g., CSV, JSON-LD, BPMN, OWL/RDF) that users can
download from the export panel.

---

## Preferred approach: ExportFormatExtension (recommended)

New export formats should implement the `ExportFormatExtension` SPI.  This is
the stable extension point that requires no changes to existing services or
endpoints.

### Steps

1. Create the format-specific exporter class in `taxonomy-export` (framework-free,
   no Spring annotations) following the existing pattern:
   ```
   taxonomy-export/src/main/java/com/taxonomy/export/<FormatName>ExportService.java
   ```
2. Register the exporter as a Spring bean in
   `taxonomy-app/src/main/java/com/taxonomy/shared/config/ExportConfig.java`.
3. Create a Spring `@Component` adapter in
   `taxonomy-app/src/main/java/com/taxonomy/export/service/<FormatName>ExportExtension.java`
   implementing `ExportFormatExtension`.
4. The new format is automatically registered in `ExportFormatExtensionRegistry`
   and can be retrieved via `getRequired("<format-id>")`.
5. If a dedicated REST endpoint is needed, add it to `ExportApiController` using
   `ExportFormatExtensionRegistry` — no changes to existing endpoint methods.

See [`docs/dev/07-extension-points.md`](../07-extension-points.md#exportformatextension)
for the full SPI contract.

---

## Legacy approach (without extension point)

The entries below document the manual wiring that was required before
`ExportFormatExtension` was introduced.  Prefer the extension-point approach
for all new formats.

### Primary entry points (legacy)

| File | What to do |
|---|---|
| `taxonomy-export/src/main/java/com/taxonomy/export/` | Create the new exporter class |
| `taxonomy-app/src/main/java/com/taxonomy/config/ExportConfig.java` | Register the exporter as a Spring bean |
| `taxonomy-app/src/main/java/com/taxonomy/export/controller/ExportApiController.java` | Add the new download endpoint |

---

## Files usually touched

- `taxonomy-export/src/main/java/com/taxonomy/export/<FormatName>ExportService.java` — new exporter (framework-free)
- `taxonomy-app/src/main/java/com/taxonomy/config/ExportConfig.java` — Spring `@Bean` registration
- `taxonomy-app/src/main/java/com/taxonomy/export/controller/ExportApiController.java` — new `GET /api/export/<format>` endpoint
- `taxonomy-app/src/main/java/com/taxonomy/export/service/ExportFacade.java` — facade method routing to the new exporter
- `taxonomy-export/src/test/java/com/taxonomy/export/<FormatName>ExportServiceTest.java` — unit tests for the exporter
- `docs/en/API_REFERENCE.md` — document the new endpoint

---

## Files usually not touched

- `taxonomy-domain/` — reuse existing DTOs (`AnalysisResult`, `TaxonomyNodeDto`)
  unless the new format needs new data fields
- `taxonomy-dsl/` — DSL is independent of export format; touch only if you
  need to export DSL-specific data (e.g., architecture elements)
- `taxonomy-app/…/service/` (non-export services) — export is a read-only
  operation on existing data; no service-layer changes needed outside `ExportFacade`
- `taxonomy-app/src/main/resources/templates/index.html` — the export panel
  must be updated to expose the new button; see [add-ui-panel](add-ui-panel.md)
  if you also want a GUI entry point

---

## Backend endpoint(s)

| Endpoint | Controller | Returns |
|---|---|---|
| `GET /api/export/archimate` | `ExportApiController` | ArchiMate XML (`application/xml`) |
| `GET /api/export/mermaid` | `ExportApiController` | Mermaid text (`text/plain`) |
| `GET /api/export/visio` | `ExportApiController` | Visio `.vsdx` (`application/zip`) |
| `GET /api/export/<new-format>` | `ExportApiController` | Your MIME type |

Follow the existing `produces` annotation patterns and content-disposition headers.

---

## Frontend module(s)

- `taxonomy-app/src/main/resources/static/js/export.js` — handles export button
  clicks and download triggers; add the new format button here
- `taxonomy-app/src/main/resources/templates/index.html` — add a button in the
  export panel section

Both the English (`messages.properties`) and German (`messages_de.properties`)
i18n files must receive the new button label.

---

## DTOs / domain types

- `com.taxonomy.dto.AnalysisResult` — primary data source for most exports
- `com.taxonomy.diagram.DiagramModel` — use as an intermediate representation
  when the new format is diagram-based
- `com.taxonomy.archimate.ArchiMateModel` — use if the new format is an
  ArchiMate extension or variant

Add a new DTO in `taxonomy-domain/…/dto/` only if the exporter needs to expose
metadata that does not already exist in the above types.

---

## Tests to run

```bash
# Export module unit tests (fast, no Spring context)
mvn test -pl taxonomy-export

# App module tests (controller + facade)
mvn test -pl taxonomy-app

# Full verify if you changed the UI
mvn verify -DexcludedGroups="real-llm"
```

Relevant test classes:
- `<FormatName>ExportServiceTest` — round-trip / schema correctness
- `ExportApiControllerTest` — endpoint status codes and content types
- `ArchiMateRoundtripTest`, `MermaidExportServiceTest` — model test patterns to follow

---

## Documentation / screenshot updates

- `docs/en/API_REFERENCE.md` — add the new endpoint
- `docs/en/DEVELOPER_GUIDE.md` — the "Adding a New Export Format" section
- Screenshots: regenerate the export panel screenshot if it lists available formats

---

## Common pitfalls

1. **`ExportConfig` wiring:** The exporter class must live in `taxonomy-export`
   (framework-free), but must be wired as a Spring bean in
   `taxonomy-app/…/config/ExportConfig.java`.
   Never add Spring annotations to classes in `taxonomy-export`.

2. **Streaming large outputs:** For formats that can be large (e.g., full taxonomy
   as RDF), use `StreamingResponseBody` rather than buffering the entire output in
   memory.

3. **Content-Disposition header:** Set `Content-Disposition: attachment; filename="…"`
   so the browser downloads rather than displays the file.

4. **DiagramModel as neutral IR:** If the new format is diagram-based, use
   `DiagramProjectionService` to obtain a `DiagramModel` — it already contains the
   relevant nodes and edges. Do not re-implement element selection logic.

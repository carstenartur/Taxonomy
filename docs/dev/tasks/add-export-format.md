# Task: Add a New Diagram Export Format

## Goal

Add a new diagram-oriented output format without changing existing exporters or adding another format switch to a controller.

## Stable contracts

The diagram export SPI is owned by `taxonomy-export`:

```text
com.taxonomy.export.spi.ExportFormatExtension
com.taxonomy.export.spi.ExportFormatDescriptor
com.taxonomy.export.spi.ExportContext
com.taxonomy.export.spi.ExportResult
```

Common extension metadata (`TaxonomyExtension`, `ExtensionKind`) is owned by `taxonomy-extension-api`. Spring discovery and HTTP routing remain in `taxonomy-app`.

## Implementation steps

1. Implement framework-free conversion logic in `taxonomy-export`, normally under:

   ```text
   taxonomy-export/src/main/java/com/taxonomy/export/<FormatName>ExportService.java
   ```

2. Add focused unit tests in `taxonomy-export/src/test/java` for:
   - valid output;
   - escaping/encoding;
   - empty or minimal diagrams;
   - schema or round-trip validation where a standard format exists.

3. Wire the framework-free service as a bean in:

   ```text
   taxonomy-app/src/main/java/com/taxonomy/shared/config/ExportConfig.java
   ```

4. Add a small Spring adapter in:

   ```text
   taxonomy-app/src/main/java/com/taxonomy/export/service/<FormatName>ExportExtension.java
   ```

   The adapter implements `com.taxonomy.export.spi.ExportFormatExtension` and delegates to the framework-free service.

5. Use a stable lowercase format ID and a complete descriptor:

   ```java
   private static final ExportFormatDescriptor DESCRIPTOR =
       new ExportFormatDescriptor(
           "bpmn",
           "BPMN 2.0 XML",
           "bpmn",
           "application/xml",
           false);
   ```

6. Add adapter/registry tests following:
   - `ExportFormatExtensionAdapterTest`
   - `ExportFormatExtensionRegistryTest`

7. The generic endpoint becomes available automatically:

   ```text
   POST /api/diagram/export/{formatId}
   ```

   Request body:

   ```json
   {
     "businessText": "Requirement to analyse",
     "locale": "en"
   }
   ```

8. Expose the format in the UI only when it benefits users. Add the button through `taxonomy-export.js`, i18n bundles and the export panel. Do not add a button only to demonstrate technical completeness.

## Files normally touched

| Layer | File or package |
|---|---|
| Framework-free renderer | `taxonomy-export/.../com/taxonomy/export/` |
| SPI contract | normally unchanged: `taxonomy-export/.../com/taxonomy/export/spi/` |
| Spring bean wiring | `taxonomy-app/.../shared/config/ExportConfig.java` |
| Spring adapter | `taxonomy-app/.../export/service/` |
| UI download helper | `taxonomy-app/.../static/js/shared/taxonomy-export.js` |
| i18n | `messages.properties`, `messages_de.properties` |
| API docs | `docs/en/API_REFERENCE.md` |
| Screenshot | export panel screenshot when the visible options change |

## Files normally not touched

- `taxonomy-extension-api` — diagram-specific contracts do not belong here.
- `taxonomy-domain` — unless the format requires genuinely reusable domain data.
- `taxonomy-dsl` — unless the output is explicitly a TaxDSL transformation.
- existing format adapters and services.
- the generic endpoint implementation.

## Design rules

- `taxonomy-export` must remain Spring-free.
- A Java package must be owned by one Maven module; do not recreate `com.taxonomy.export.spi` in `taxonomy-app`.
- Use `DiagramModel` as the neutral diagram representation.
- Do not repeat anchor selection, propagation or diagram curation inside an exporter.
- Treat format-specific options as validated input, not arbitrary casts from `Map` deep inside the renderer.
- For large output, stream instead of buffering the complete document.
- Use correct media type, extension and `Content-Disposition` filename.
- Avoid proprietary formats as the only representation; provide an open alternative.

## Tests to run

```bash
mvn test -pl taxonomy-export -am
mvn test -pl taxonomy-app -Dtest=ExportFormatExtensionRegistryTest,ExportFormatExtensionAdapterTest
mvn verify -DexcludedGroups="real-llm"
```

For user-visible changes also run the screenshot generator and authenticated accessibility workflow.

## Definition of done

- [ ] Renderer is framework-free and independently tested.
- [ ] Adapter is discovered without changing a central format switch.
- [ ] Duplicate IDs fail fast.
- [ ] Generic endpoint returns correct bytes and headers.
- [ ] Existing formats produce unchanged output.
- [ ] UI label, help and documentation are available in German and English where exposed.
- [ ] Link, screenshot and accessibility checks pass.
- [ ] Maintainability matrix is updated in the same pull request.

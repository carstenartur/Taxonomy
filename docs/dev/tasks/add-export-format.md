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

## Independent plugin packaging

For a separately installable format, follow
[`plugins/taxonomy-mermaid-plugin`](../../../plugins/taxonomy-mermaid-plugin/pom.xml):
use Java 21, provided SDK dependencies and a `TaxonomyPlugin` ServiceLoader entry
returning the `ExportFormatExtension`. Keep all implementation packages unique to
that plugin. Do not package copies of host SDK classes or depend on app registries.
Declare the stable plugin identity/version and SDK range in the manifest. DYNAMIC
mode is supported only for stateless exports/renderers that can release resources
after all call leases finish; other contributions are STARTUP-only.

Install the operator-owned JAR in `plugins/`. The host uses the same generic route
and capabilities for built-ins and plugins. Follow the
[lifecycle and installation contract](../extension-module-boundaries.md#optional-startup-installation-and-runtime-lifecycle).
Prove an independent build, real HTTP bytes, missing-format behavior and safe drain
against the packaged host, whose own JAR must remain unchanged.

## Built-in implementation steps

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

8. Verify that `/api/capabilities` exposes the installed descriptor in the existing contextual format selector. Preserve cancellation, snapshot provenance, locale and keyboard behavior. Do not add a format-specific button or executable plugin UI.

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
./mvnw test -pl taxonomy-export -am
./mvnw test -pl taxonomy-app -am -Dtest=ExportFormatExtensionRegistryTest,ExportFormatExtensionAdapterTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B verify -Pplugin-packaging-tests
./mvnw -B verify -Pci -DrunOnnxTests=true
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

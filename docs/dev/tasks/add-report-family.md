# Task: Add a Report Family or Report Format

Taxonomy report rendering is an explicit classpath extension point. Renderers are
identified by the pair:

```text
reportTypeId / formatId
```

The original report family is `architecture`; the hierarchical decision-evidence
family is `decision-rationale`.

## Add another format to an existing family

1. Implement `ReportRendererExtension` as a Spring `@Component`.
2. Return the existing family ID from `reportTypeId()`.
3. Return the accepted payload class from `reportModelType()`.
4. Advertise the exact extension, content type, and binary flag through
   `ReportFormatDescriptor`.
5. In `render(...)`, obtain the payload with
   `context.payloadAs(ExpectedModel.class)`.
6. Add renderer, registry, endpoint, and binary/content tests.

No controller change is required when the report-family controller already delegates
format selection to `ReportRendererRegistry`.

## Add a new report family

1. Create one immutable, format-neutral report model.
2. Create a service that builds the model from trusted server-side context and a bounded
   request or authoritative persisted snapshot.
3. Add a report-family controller that contains validation and provenance resolution but
   no format-specific rendering code.
4. Register at least one renderer extension with a stable `reportTypeId()`.
5. Use `ReportRendererRegistry.getRequired(reportTypeId, formatId)` and
   `ReportRenderContext.ofPayload(model)`.
6. Expose the registered descriptors so the UI can discover supported formats.
7. Document whether the report is final, incomplete, stale, or based on provisional data.

## Compatibility rules

- Existing architecture renderers may omit `reportTypeId()` and `reportModelType()`;
  their defaults remain `architecture` and `ArchitectureReport`.
- A format ID must be unique only inside one report family. `architecture/docx` and
  `decision-rationale/docx` may coexist.
- The global extension ID is the bare format ID for the legacy architecture family and
  `reportTypeId:formatId` for additional families.
- Renderers must reject a payload of the wrong model type.
- External JAR hot-loading is not currently supported; extension beans must be on the
  application classpath at startup.

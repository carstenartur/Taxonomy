# Task: Add a Document Import Mapping

## Goal

Map a new section of an imported document (e.g., a new heading pattern, a new
structured field, or a new file format such as DOCX or Markdown) to taxonomy nodes
or architecture requirements in the provenance pipeline.

---

## Primary entry points

| File | What to do |
|---|---|
| `taxonomy-app/src/main/java/com/taxonomy/provenance/service/DocumentAnalysisService.java` | Add the mapping logic for the new section/field |
| `taxonomy-app/src/main/java/com/taxonomy/provenance/service/DocumentParserService.java` | Add parsing support for the new file format or section pattern |

---

## Files usually touched

- `taxonomy-app/…/provenance/service/DocumentAnalysisService.java` — chunk-to-requirement mapping
- `taxonomy-app/…/provenance/service/DocumentParserService.java` — text extraction from the source document
- `taxonomy-app/…/provenance/service/StructuredDocumentParser.java` — structured field extraction (if the new format is structured)
- `taxonomy-app/…/provenance/service/ChunkingStrategySelector.java` — if the new document type needs a different chunking strategy
- `taxonomy-app/…/provenance/service/HierarchicalChunkingService.java` or `SemanticChunkingService.java` — if the chunking strategy changes
- `taxonomy-domain/…/dto/DocumentParseResult.java` — add new fields only if the parser produces new metadata
- `taxonomy-domain/…/dto/DocumentSection.java` — extends the section model if needed

---

## Files usually not touched

- `taxonomy-dsl/` — provenance data is stored as DSL blocks; the DSL parser
  already handles `source`, `sourceVersion`, `sourceFragment`, and
  `requirementSourceLink` block types; extend DSL only if you add a new provenance block type
- `taxonomy-export/` — export is unrelated to document import
- `taxonomy-app/…/analysis/service/` — LLM analysis is separate from document parsing;
  the document import pipeline feeds requirements, which are then analysed independently
- `taxonomy-app/…/relations/service/` — relation management is unrelated to document import

---

## Backend endpoint(s)

| Endpoint | Controller |
|---|---|
| `POST /api/import/document` | `DocumentImportController` |
| `GET /api/import/document/status` | `DocumentImportController` |

The import controller accepts a multipart file upload and delegates to
`DocumentParserService` + `DocumentAnalysisService`.

---

## Frontend module(s)

- `taxonomy-app/src/main/resources/static/js/import.js` — handles the upload
  dialog and progress indicator
- `taxonomy-app/src/main/resources/templates/index.html` — the import panel

If the new mapping introduces new output fields visible in the import result
dialog, update both the JS module and the template.

---

## DTOs / domain types

| DTO | Usage |
|---|---|
| `DocumentParseResult` | Returned by the parser; contains sections and metadata |
| `DocumentSection` | A single extracted section (heading, content, level) |
| `HierarchicalChunk` | A chunk produced by hierarchical chunking |
| `SourceArtifactDto` | The imported document as a provenance source |
| `SourceFragmentDto` | A fragment (chunk) linked to a source version |
| `RequirementSourceLinkDto` | Links a requirement to a source fragment |

---

## Tests to run

```bash
# App module unit tests
mvn test -pl taxonomy-app
```

Relevant test classes:
- `DocumentImportControllerTest` — upload endpoint, format detection
- `DocumentAnalysisServiceTest` — chunk-to-requirement mapping
- `StructuredDocumentParserTest` (if it exists) — structured field extraction

---

## Documentation / screenshot updates

- `docs/en/DOCUMENT_IMPORT.md` — add the new format or mapping to the supported
  document types table
- Screenshots: regenerate the import panel screenshot if it shows the list of
  accepted file types

---

## Common pitfalls

1. **Chunking strategy selection:** The `ChunkingStrategySelector` chooses between
   hierarchical and semantic chunking based on document structure.
   Ensure the new document type returns a sensible strategy; do not hard-code a strategy.

2. **Large documents:** Documents can be large.
   The parser must not hold the entire parsed content in memory simultaneously.
   Use streaming or incremental parsing where possible.

3. **Empty sections:** The mapping must handle sections with no extractable
   content gracefully (no null pointers, no empty fragments stored).

4. **Provenance DSL round-trip:** After import, the provenance data is persisted
   as DSL blocks. Verify the round-trip:
   parse the imported data → store as DSL → re-read DSL → check the data is intact.
   The `TaxDslParser` and `TaxDslSerializer` handle this, but new block types
   or properties must be registered (see [add-dsl-property](add-dsl-property.md)).

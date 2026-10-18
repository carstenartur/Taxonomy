# Task: Add a Document Import Mapping

## Goal

Extend document parsing, requirement-candidate extraction, or regulation-to-node
mapping in the application-local provenance context. PDF and DOCX are the current
upload formats; a new format also needs bounded parsing, validation, API, and UI
changes. Framework catalogue imports use the separate knowledge-owned
[import-profile extension point](../07-extension-points.md#import-profiles-and-document-mappings).

---

## Primary entry points

| File | What to do |
|---|---|
| `taxonomy-app/src/main/java/com/taxonomy/provenance/service/DocumentParserService.java` | Bounded file parsing, rule-based candidates, and content hashing |
| `taxonomy-app/src/main/java/com/taxonomy/provenance/service/DocumentAnalysisService.java` | Optional model-backed candidate extraction and regulation-to-taxonomy mapping |
| `taxonomy-app/src/main/java/com/taxonomy/provenance/service/DocumentProvenanceCommandService.java` | Atomic document registration and candidate confirmation |
| `taxonomy-app/src/main/java/com/taxonomy/provenance/config/DocumentImportLimits.java` | Upload, expanded content, extracted text, candidate, and model-input limits |

---

## Files usually touched

- The parser, analysis, and command services above, according to the behavior
  being changed. Registration and confirmation persist the provenance graph
  through application-owned JPA repositories.
- `taxonomy-app/src/main/java/com/taxonomy/provenance/controller/DocumentImportController.java`
  — request validation and response contracts.
- `taxonomy-analysis/src/main/resources/prompts/` — extraction or regulation
  mapping prompts when the model-backed workflow changes.
- `taxonomy-domain/src/main/java/com/taxonomy/dto/DocumentParseResult.java` and
  `RequirementCandidate.java` — only if returned candidate/metadata contracts change.

Related structured/chunking helpers live in
`taxonomy-app/src/main/java/com/taxonomy/provenance/service/`:
`StructuredDocumentParser`, `ChunkingStrategySelector`, `HierarchicalChunkingService`,
and `SemanticChunkingService`. The current upload controller calls
`DocumentParserService.parse(...)`; do not assume it automatically invokes those
helpers. Trace and test any new integration explicitly.

---

## Files usually not touched

- `taxonomy-dsl/` — upload and confirmation use the provenance command service.
  Change DSL syntax/mapping only when the provenance representation exchanged
  through DSL also changes
- `taxonomy-export/` — export is unrelated to document import
- `taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/` — existing
  provider and prompt services support optional model-backed import; a parser
  change alone usually needs no gateway change
- `taxonomy-knowledge/src/main/java/com/taxonomy/relations/service/` — relation
  review and commands have separate contracts

---

## Backend endpoint(s)

| Endpoint | Controller |
|---|---|
| `POST /api/documents/upload` | `DocumentImportController`: parse PDF/DOCX and register source/version |
| `POST /api/documents/extract-ai` | `DocumentImportController`: optional model-backed extraction |
| `POST /api/documents/map-regulation` | `DocumentImportController`: optional regulation-to-node mapping |
| `POST /api/documents/confirm-candidates` | `DocumentImportController`: confirm selected candidates and their source links |
| `GET /api/provenance/sources` | `DocumentImportController`: source metadata |
| `GET /api/provenance/links/{requirementId}` | `DocumentImportController`: retained requirement/source links |

The upload, extraction, and mapping routes accept multipart files. Confirmation
accepts JSON. Plain parsing does not require a remote model; extraction and mapping
use `DocumentAnalysisService` and the configured generative provider. Do not call
model-backed endpoints during routine documentation or parser verification.

---

## Frontend module(s)

- `taxonomy-app/src/main/resources/static/js/shared/taxonomy-document-import.js`
  — upload modes, candidate review, provenance, and analysis handoff
- `taxonomy-app/src/main/resources/templates/index.html` — the import panel

If the new mapping introduces new output fields visible in the import result
dialog, update both the JS module and the template.

---

## DTOs / domain types

| DTO | Usage |
|---|---|
| `DocumentParseResult` | File metadata, bounded text preview, candidates, warnings, source/version IDs |
| `RequirementCandidate` | Rule-based candidate text and section context |
| `AiExtractedCandidate` / `RegulationArchitectureMatch` | Optional model-backed extraction/mapping outputs |
| `DocumentSection` / `HierarchicalChunk` | Related structured parsing/chunking helper contracts |
| `SourceArtifactDto` | The imported document as a provenance source |
| `SourceFragmentDto` | A fragment (chunk) linked to a source version |
| `RequirementSourceLinkDto` | Links a requirement to a source fragment |

---

## Tests to run

```bash
# Existing bounded parser/controller/limits profile, including needed siblings
./mvnw -pl taxonomy-app -am test -Pdocument-import-tests

# Broader provenance and composition checks when mapping or persistence changes
./mvnw -pl taxonomy-app -am test
```

Relevant existing tests are application-owned:

- `taxonomy-app/src/test/java/com/taxonomy/provenance/DocumentImportControllerTest.java`,
  `DocumentParserServiceTest.java`, and `DocumentImportLimitsTest.java` — the
  named profile's selection
- `taxonomy-app/src/test/java/com/taxonomy/provenance/service/DocumentAnalysisServiceTest.java`
  — deterministic mocked extraction/mapping behavior
- `taxonomy-app/src/test/java/com/taxonomy/provenance/DocumentProvenanceCommandServiceTest.java`
  and `DocumentImportControllerContractTest.java` — transactional registration,
  confirmation, and API contracts
- `taxonomy-app/src/test/java/com/taxonomy/provenance/StructuredDocumentParserTest.java`,
  `ChunkingStrategySelectorTest.java`, `HierarchicalChunkingServiceTest.java`, and
  `SemanticChunkingServiceTest.java` — when the related helpers change

The named profile does not select every provenance test. Finish with
`./mvnw -B verify -Pci -DrunOnnxTests=true` and applicable companion gates in
[Maven Verification](../MAVEN_VERIFICATION.md). Retain explicit model opt-in and
report missing prerequisites rather than claiming a reduced run is full CI.

---

## Documentation / screenshot updates

- `docs/en/DOCUMENT_IMPORT.md` — add the new format or mapping to the supported
  document types table
- Screenshots: regenerate the import panel screenshot if it shows the list of
  accepted file types; follow [capture ownership and provenance rules](../../../.github/copilot-ref-screenshots.md)

---

## Common pitfalls

1. **Extraction paths differ:** Rule-based upload, optional AI extraction, and
   regulation mapping return different output contracts. Check the intended
   request path and preserve its limits and provenance behavior.

2. **Large documents:** Documents can be large.
   Preserve temporary-file cleanup, upload and expansion limits, PDF page limits,
   bounded extracted text/candidates, and explicit truncation warnings. Do not
   replace the current bounded workflow with unbounded in-memory file reads.

3. **Empty sections:** The mapping must handle sections with no extractable
   content gracefully (no null pointers, no empty fragments stored).

4. **Atomic provenance:** Registration retains an artifact/version together;
   confirmation retains the selected fragments and requirement links atomically.
   Preserve retry behavior and source identity. When DSL interchange also changes,
   verify its mapper/parser/serializer round trip separately (see
   [add-dsl-property](add-dsl-property.md)).

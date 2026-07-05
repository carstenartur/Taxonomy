# Extension Points

Related developer notes:

- [Change Map](01-change-map.md)
- [Task: Add a New LLM Provider](tasks/add-llm-provider.md)
- [Task: Add a New Export Format](tasks/add-export-format.md)
- [Task: Add a New Relation Type](tasks/add-relation-type.md)
- [Task: Add a DSL Property](tasks/add-dsl-property.md)
- [Relation and DSL Extension Boundaries](relation-and-dsl-extension-boundaries.md)

Use this page when you need the **stable starting point** for a common feature
addition. It documents the extension anchors that already exist in the codebase.
It does **not** introduce a plugin framework or runtime loading of external JARs.

## Extension map

| Area | Status | Stable starting point |
|---|---|---|
| [LLM providers](#llm-providers) | Explicit SPI | `LlmProviderExtension` + `LlmProviderExtensionRegistry` |
| [Prompt templates](#prompt-templates) | Implicit, documented anchor | `PromptTemplateService` + `src/main/resources/prompts/` |
| [Export formats](#export-formats) | Explicit SPI | `ExportFormatExtension` + `ExportFormatExtensionRegistry` |
| [Import profiles and document mappings](#import-profiles-and-document-mappings) | Mixed | `ImportProfileExtension` / `ImportProfileRegistry` and `DocumentAnalysisService` |
| [Relation types and compatibility rules](#relation-types-and-compatibility-rules) | Implicit, documented anchor | `RelationType` + `RelationCompatibilityMatrix` |
| [Architecture view pipeline steps](#architecture-view-pipeline-steps) | Explicit SPI | `ArchitecturePipelineStep` + `ArchitecturePipelineStepRegistry` |
| [Report formats](#report-formats) | Explicit SPI | `ReportRendererExtension` + `ReportRendererRegistry` |
| [UI panels](#ui-panels) | Implicit, documented anchor | `index.html` + `src/main/resources/static/js/` |
| [Workspace and versioning operations](#workspace-and-versioning-operations) | Implicit, documented anchor | `WorkspaceManager` / `VersioningFacade` / versioning services |
| [DSL grammar and property additions](#dsl-grammar-and-property-additions) | Implicit, documented anchor | `TaxDslParser` + serializer + mappers + validator |

## Shared internal SPI

The explicit extension points share a small internal SPI in
`taxonomy-extension-api/src/main/java/com/taxonomy/shared/extension`:

- `TaxonomyExtension` defines stable metadata methods: `id()`,
  `displayName()`, `description()`, and `kind()`
- `ExtensionKind` classifies extensions as `EXPORT_FORMAT`,
  `REPORT_RENDERER`, `IMPORT_PROFILE`, `LLM_PROVIDER`, or
  `ARCHITECTURE_PIPELINE_STEP`
- `ExtensionDescriptor` is the framework-free metadata view safe for REST/UI

`taxonomy-app/src/main/java/com/taxonomy/shared/extension/ExtensionRegistry`
collects all Spring extension beans, validates duplicate IDs per kind, and
exposes descriptor lookups (`listAll()`, `listByKind(...)`,
`findDescriptor(...)`).

For explicit extension kinds you can inspect registered descriptors through the
read-only REST API:

- `GET /api/extensions`
- `GET /api/extensions/{kind}`

implemented by
`taxonomy-app/src/main/java/com/taxonomy/shared/controller/ExtensionApiController.java`.

---

## LLM providers

**Status:** Explicit SPI

**Interface / registry / configuration point**

- `taxonomy-extension-api/src/main/java/com/taxonomy/analysis/service/LlmProviderExtension.java`
- `taxonomy-app/src/main/java/com/taxonomy/analysis/service/LlmProviderExtensionRegistry.java`
- `taxonomy-app/src/main/java/com/taxonomy/analysis/service/LlmGatewayRegistry.java`
- `taxonomy-app/src/main/java/com/taxonomy/analysis/service/LlmProviderConfig.java`

`LlmProviderExtension` describes provider metadata; `LlmGatewayRegistry`
provides the HTTP gateway used by `LlmService`.

**Required files**

- `taxonomy-app/src/main/java/com/taxonomy/analysis/service/LlmProvider.java`
- new Spring `@Component` implementing `LlmProviderExtension`
- gateway registration in `LlmGatewayRegistry`
- API-key or model wiring in `LlmProviderConfig`
- configuration property entry in
  `taxonomy-app/src/main/resources/application.properties`

**Optional files**

- provider-specific gateway class if `OpenAiCompatibleGateway` is not enough
- `LlmResponseParser` only if the response JSON shape differs
- prompt files under `taxonomy-app/src/main/resources/prompts/` only if the
  provider needs different prompt structure

**Required tests**

- `taxonomy-app/src/test/java/com/taxonomy/analysis/service/LlmProviderExtensionRegistryTest.java`
- `taxonomy-app/src/test/java/com/taxonomy/analysis/service/LlmGatewayRegistryTest.java`
- provider-specific gateway test if custom HTTP behavior is added

**Documentation updates**

- `docs/en/CONFIGURATION_REFERENCE.md`
- `docs/en/AI_PROVIDERS.md`
- `docs/dev/tasks/add-llm-provider.md` if the workflow changes

**Common failure modes**

- enum name, `descriptor().providerId()`, and gateway registration do not match
- API key added to config but not surfaced by `LlmProviderConfig.getAvailableProviders()`
- new provider implemented directly in `LlmService` instead of via the extension
  metadata + gateway split
- real API calls added to tests instead of using existing mocks/fakes

---

## Prompt templates

**Status:** Implicit extension point, anchored by `PromptTemplateService`

**Interface / registry / configuration point**

- `taxonomy-app/src/main/java/com/taxonomy/shared/service/PromptTemplateService.java`
- `taxonomy-app/src/main/resources/prompts/*.txt`

There is no prompt-template registry. The stable anchor is the combination of
`PromptTemplateService` and filename-based lookup.

**Required files**

- new or updated `.txt` file in `taxonomy-app/src/main/resources/prompts/`

**Optional files**

- `PromptTemplateService.TAXONOMY_NAMES` if a new template code needs a friendly name
- `PromptTemplateService` render helpers if a new prompt family introduces new
  placeholders or categorization prefixes

**Required tests**

- `taxonomy-app/src/test/java/com/taxonomy/PromptTemplateTests.java`

**Documentation updates**

- this page if a new template family/prefix is introduced
- user-facing docs only if prompt editing or prompt categories become visible in the UI

**Common failure modes**

- adding a taxonomy-specific template without preserving `default.txt` fallback
- introducing placeholders that `PromptTemplateService` never substitutes
- using an ad-hoc filename prefix without updating prompt categorization logic
- placing provider-specific behavior in prompt files when it belongs in the LLM
  provider/gateway layer

---

## Export formats

**Status:** Explicit SPI

**Interface / registry / configuration point**

- `taxonomy-extension-api/src/main/java/com/taxonomy/export/service/ExportFormatExtension.java`
- `taxonomy-app/src/main/java/com/taxonomy/export/service/ExportFormatExtensionRegistry.java`
- `taxonomy-app/src/main/java/com/taxonomy/shared/config/ExportConfig.java`
- `taxonomy-app/src/main/java/com/taxonomy/export/controller/ExportApiController.java`

The stable path is:

1. framework-free exporter in `taxonomy-export`
2. Spring bean wiring in `ExportConfig`
3. Spring `@Component` adapter implementing `ExportFormatExtension`
4. generic export endpoint `POST /api/diagram/export/{formatId}`

**Required files**

- exporter class in `taxonomy-export/src/main/java/com/taxonomy/export/`
- bean registration in `taxonomy-app/src/main/java/com/taxonomy/shared/config/ExportConfig.java`
- adapter in `taxonomy-app/src/main/java/com/taxonomy/export/service/*ExportExtension.java`

**Optional files**

- `ExportApiController` only if a format-specific endpoint with a custom URL is needed
- `taxonomy-app/src/main/resources/templates/index.html`
- `taxonomy-app/src/main/resources/static/js/shared/taxonomy-export.js`
- i18n entries if the format is exposed by a new button or menu label

**Required tests**

- exporter unit test in `taxonomy-export/src/test/java/com/taxonomy/export/`
- `taxonomy-app/src/test/java/com/taxonomy/export/service/ExportFormatExtensionRegistryTest.java`
- `taxonomy-app/src/test/java/com/taxonomy/export/service/ExportFormatExtensionAdapterTest.java`

**Documentation updates**

- `docs/dev/tasks/add-export-format.md`
- `docs/en/API_REFERENCE.md` only if a custom endpoint is added
- user-facing docs only if the format is exposed in the UI

**Common failure modes**

- exporter logic placed in `taxonomy-app` instead of `taxonomy-export`
- adapter added but exporter bean not wired in `ExportConfig`
- duplicate format IDs or inconsistent `descriptor()` metadata
- adding a one-off controller endpoint when the generic registry-backed endpoint
  is sufficient

---

## Import profiles and document mappings

**Status:** Mixed — explicit SPI for framework imports, implicit anchor for
document/provenance mappings

### Framework import profiles

**Interface / registry / configuration point**

- `taxonomy-extension-api/src/main/java/com/taxonomy/catalog/service/importer/ImportProfileExtension.java`
- `taxonomy-app/src/main/java/com/taxonomy/catalog/service/importer/ImportProfileRegistry.java`
- `taxonomy-app/src/main/java/com/taxonomy/catalog/service/importer/FrameworkImportService.java`
- `taxonomy-app/src/main/resources/static/js/api/import-api.js`

**Required files**

- new Spring `@Component` implementing `ImportProfileExtension`, usually by
  extending `AbstractFrameworkImportProfileExtension`
- parser and/or mapping profile used by the extension

**Optional files**

- custom controller logic only for non-standard endpoints; standard profile
  additions should flow through `ImportApiController`
- UI changes only if the import profile needs extra inputs beyond profile ID,
  file, and branch

**Required tests**

- `taxonomy-app/src/test/java/com/taxonomy/catalog/service/importer/ImportProfileRegistryTest.java`
- `taxonomy-app/src/test/java/com/taxonomy/catalog/service/importer/FrameworkImportServiceTest.java`
- parser tests such as `ApqcCsvParserTest`, `ApqcExcelParserRichTextTest`,
  `StructurizrDslParserTest`, or `UafXmlParserTest`
- mapping profile tests in `taxonomy-dsl/src/test/java/com/taxonomy/dsl/mapping/profiles/`

**Documentation updates**

- `docs/en/FRAMEWORK_IMPORT.md`
- `docs/dev/tasks/add-document-import-mapping.md` if the workflow changes

**Common failure modes**

- `descriptor().profileId()` does not match the UI/API expectation
- bypassing `ImportApi.loadProfiles()` / `ImportApi.preview()` / `ImportApi.execute()`
  and hardcoding URLs in the frontend
- mixing parser logic, mapping logic, and persistence logic in one class instead
  of using the existing parse → map → DSL → materialize pipeline

### Document mappings

**Interface / registry / configuration point**

- `taxonomy-app/src/main/java/com/taxonomy/provenance/service/DocumentAnalysisService.java`
- `taxonomy-app/src/main/java/com/taxonomy/provenance/service/DocumentParserService.java`
- `taxonomy-app/src/main/java/com/taxonomy/provenance/service/StructuredDocumentParser.java`
- `taxonomy-app/src/main/java/com/taxonomy/provenance/service/ChunkingStrategySelector.java`

There is no registry. The documented anchor is the provenance pipeline above.

**Required files**

- the parser or chunking service that understands the new document structure
- `DocumentAnalysisService` mapping logic if new sections/fields must become
  requirements or provenance links

**Optional files**

- DSL/provenance block changes only if the mapping introduces genuinely new
  persisted structure
- UI/import result rendering if new fields are shown to users

**Required tests**

- `taxonomy-app/src/test/java/com/taxonomy/provenance/DocumentImportControllerTest.java`
- `taxonomy-app/src/test/java/com/taxonomy/provenance/DocumentParserServiceTest.java`
- `taxonomy-app/src/test/java/com/taxonomy/provenance/StructuredDocumentParserTest.java`
- `taxonomy-app/src/test/java/com/taxonomy/provenance/service/DocumentAnalysisServiceTest.java`
- `taxonomy-app/src/test/java/com/taxonomy/provenance/ChunkingStrategySelectorTest.java`

**Documentation updates**

- `docs/en/USER_GUIDE.md` if supported document behavior changes
- any provenance-specific user/admin guide that lists supported formats

**Common failure modes**

- parsing a new structure but never mapping it into provenance/requirements
- adding new persisted provenance shape without updating DSL round-trip handling
- bypassing chunking strategy selection and hardcoding one parser path

---

## Relation types and compatibility rules

**Status:** Implicit extension point, anchored by enum + compatibility + UI touchpoints

**Interface / registry / configuration point**

- `taxonomy-domain/src/main/java/com/taxonomy/model/RelationType.java`
- `taxonomy-app/src/main/java/com/taxonomy/relations/service/RelationCompatibilityMatrix.java`
- `taxonomy-app/src/main/java/com/taxonomy/relations/service/RelationValidationService.java`
- `taxonomy-app/src/main/java/com/taxonomy/relations/service/RelationProposalService.java`
- `taxonomy-dsl/src/main/java/com/taxonomy/dsl/validation/DslValidator.java`

There is currently **no dedicated relation-type registry or `/api/relations/types`
metadata endpoint**. The UI still duplicates the allowed values in
`taxonomy-app/src/main/resources/templates/index.html`.

**Required files**

- `RelationType.java`
- `RelationCompatibilityMatrix.java`
- both relation-type `<select>` lists in `index.html`

**Optional files**

- `RelationValidationService` if the new type needs special validation
- `RelationProposalService` / `RelationCandidateService` if proposal heuristics
  depend on the new type
- `taxonomy-app/src/main/resources/data/relation_seeds.csv`
- `taxonomy-dsl/src/main/java/com/taxonomy/dsl/validation/DslValidator.java`
- i18n or help text if the new type needs a user-facing label/description

**Required tests**

- `taxonomy-domain/src/test/java/com/taxonomy/model/RelationTypeTest.java`
- `taxonomy-app/src/test/java/com/taxonomy/RelationProposalTests.java`
- `taxonomy-app/src/test/java/com/taxonomy/ArchitectureAnalysisTests.java`
- `taxonomy-app/src/test/java/com/taxonomy/CsvRelationsIntegrationTest.java` if seed data is changed
- `taxonomy-dsl/src/test/java/com/taxonomy/dsl/DslValidatorTest.java` if DSL validation changes

**Documentation updates**

- `docs/en/RELATION_SEEDS.md` if seed data or guidance changes
- this page if a future dedicated relation-type endpoint or registry is introduced

**Common failure modes**

- new enum constant added but omitted from one or both relation-type selects in `index.html`
- compatibility matrix not updated, causing the validator to reject all uses of the new type
- proposal/analysis logic still assuming the old closed set of relation types
- seed data added for combinations the compatibility matrix forbids

---

## Architecture view pipeline steps

**Status:** Explicit SPI

**Interface / registry / configuration point**

- `taxonomy-app/src/main/java/com/taxonomy/architecture/pipeline/ArchitecturePipelineStep.java`
- `taxonomy-app/src/main/java/com/taxonomy/architecture/pipeline/ArchitecturePipelineStepRegistry.java`
- `taxonomy-app/src/main/java/com/taxonomy/architecture/pipeline/ArchitectureViewPipeline.java`

Implementations are Spring `@Service` beans discovered automatically. Stable
step IDs live on the built-ins as `STEP_ID` constants.

**Required files**

- new `@Service` implementing `ArchitecturePipelineStep`

**Optional files**

- `descriptor()` override if the default metadata is not enough
- DTO/controller/UI updates only if the step produces new user-visible output
- architecture docs if the step changes the conceptual pipeline

**Required tests**

- `taxonomy-app/src/test/java/com/taxonomy/architecture/pipeline/ArchitecturePipelineStepRegistryTest.java`
- `taxonomy-app/src/test/java/com/taxonomy/architecture/pipeline/ArchitectureViewPipelineTest.java`
- step-specific test such as `AnchorSelectionStepTest`,
  `NodeLimitStepTest`, or `ProvisionalRelationStepTest`
- `taxonomy-app/src/test/java/com/taxonomy/architecture/pipeline/ArchitecturePipelineStepContextWriteTest.java`
  when the step writes new context fields

**Documentation updates**

- `docs/en/ARCHITECTURE.md` if the default pipeline sequence changes
- `docs/dev/tasks/add-architecture-view-step.md` if the recommended workflow changes

**Common failure modes**

- duplicate `id()` or `order()` values
- inserting a step in the wrong order so later steps see inconsistent context
- modifying a core-invariant step without preserving its guarantees
- using non-deterministic collection ordering inside a step

---

## Report formats

**Status:** Explicit SPI

**Interface / registry / configuration point**

- `taxonomy-extension-api/src/main/java/com/taxonomy/architecture/report/ReportRendererExtension.java`
- `taxonomy-app/src/main/java/com/taxonomy/architecture/report/ReportRendererRegistry.java`
- `taxonomy-app/src/main/java/com/taxonomy/architecture/controller/ReportApiController.java`

**Required files**

- new Spring `@Component` implementing `ReportRendererExtension`

**Optional files**

- controller changes only if a new endpoint shape is required
- UI affordances only if the format is directly user-selectable

**Required tests**

- `taxonomy-app/src/test/java/com/taxonomy/ArchitectureReportTests.java`
- renderer-specific test if the format has custom serialization rules

**Documentation updates**

- user/API docs if the new format is exposed publicly
- this page if a new renderer family changes the extension workflow

**Common failure modes**

- duplicate format IDs or inconsistent descriptor metadata
- renderer bypasses `ReportRendererRegistry` and wires directly into a controller
- wrong content type or file extension advertised for downloads

---

## UI panels

**Status:** Implicit extension point, anchored by template + JS module + i18n

**Interface / registry / configuration point**

- `taxonomy-app/src/main/resources/templates/index.html`
- `taxonomy-app/src/main/resources/static/js/`
- `taxonomy-app/src/main/resources/messages.properties`
- `taxonomy-app/src/main/resources/messages_de.properties`

There is no panel registry. A panel is currently defined by its HTML anchor(s),
its JavaScript module(s), and any backing controller/DTO needed for data.

**Required files**

- `index.html`
- one or more JS modules under `src/main/resources/static/js/`
- English and German i18n entries for every new visible label/message

**Optional files**

- controller/DTO additions if the panel needs new server data
- screenshot assets/docs if the panel becomes part of user documentation

**Required tests**

- controller test for any new endpoint backing the panel
- `taxonomy-app/src/test/java/com/taxonomy/shared/controller/TemplateI18nLintTest.java`
- `ScreenshotGeneratorIT` only when you intentionally refresh documented screenshots

**Documentation updates**

- `docs/en/USER_GUIDE.md` for user-visible workflows
- feature matrix or screenshot references if the new panel becomes documented UI

**Common failure modes**

- missing `messages.properties` / `messages_de.properties` keys
- adding HTML but forgetting to register or initialize the matching JS module
- reusing DOM IDs already owned by another panel
- adding POST/DELETE UI actions without following the existing CSRF pattern

---

## Workspace and versioning operations

**Status:** Implicit extension point, anchored by named facades/services

**Interface / registry / configuration point**

- `taxonomy-app/src/main/java/com/taxonomy/workspace/service/WorkspaceManager.java`
- `taxonomy-app/src/main/java/com/taxonomy/versioning/service/VersioningFacade.java`
- `taxonomy-app/src/main/java/com/taxonomy/versioning/service/RepositoryStateService.java`
- `taxonomy-app/src/main/java/com/taxonomy/versioning/service/ContextNavigationService.java`
- `taxonomy-app/src/main/java/com/taxonomy/versioning/service/SelectiveTransferService.java`

Use controllers only as request boundaries:

- `taxonomy-app/src/main/java/com/taxonomy/workspace/controller/WorkspaceController.java`
- `taxonomy-app/src/main/java/com/taxonomy/versioning/controller/ContextNavigationController.java`
- `taxonomy-app/src/main/java/com/taxonomy/versioning/controller/GitStateController.java`

**Required files**

- one service/facade method that owns the operation
- one controller endpoint if the operation is externally triggered

**Optional files**

- JS modules under `static/js/workspace/` or `static/js/versioning/`
- DTO additions if the operation returns new metadata
- compare/transfer/conflict helpers depending on the operation type

**Required tests**

- `taxonomy-app/src/test/java/com/taxonomy/workspace/controller/WorkspaceControllerTest.java`
- one or more focused service tests such as:
  - `WorkspaceManagerTest`
  - `RepositoryStateServiceTest`
  - `ContextNavigationServiceTest`
  - `SelectiveTransferServiceTest`
  - `ContextCompareServiceTest`
  - `ConflictDetectionServiceTest`

**Documentation updates**

- `docs/en/WORKSPACE_VERSIONING.md`
- `docs/en/USER_GUIDE.md` if the operation is end-user visible

**Common failure modes**

- resolving workspace context deep inside service code instead of at the request boundary
- splitting one operation across multiple services without a clear orchestration owner
- forgetting repository-state/conflict guards for operations that mutate Git-backed content
- adding the backend operation without updating the workspace/versioning JS module that exposes it

---

## DSL grammar and property additions

**Status:** Implicit extension point, anchored by the round-trip pipeline

**Interface / registry / configuration point**

- `taxonomy-dsl/src/main/java/com/taxonomy/dsl/parser/TaxDslParser.java`
- `taxonomy-dsl/src/main/java/com/taxonomy/dsl/serializer/TaxDslSerializer.java`
- `taxonomy-dsl/src/main/java/com/taxonomy/dsl/mapper/AstToModelMapper.java`
- `taxonomy-dsl/src/main/java/com/taxonomy/dsl/mapper/ModelToAstMapper.java`
- `taxonomy-dsl/src/main/java/com/taxonomy/dsl/validation/DslValidator.java`
- `taxonomy-dsl/src/main/java/com/taxonomy/dsl/diff/ModelDiffer.java`
- `taxonomy-app/src/main/java/com/taxonomy/dsl/DslMaterializeService.java`

There is no DSL extension registry. The stable anchor is the parse → model →
serialize → diff → materialize pipeline.

**Required files**

- for a new property: model, serializer order, both mappers, and any validator/materializer logic that depends on it
- for a new block type: parser block registration, model, serializer, both mappers,
  validator, and often tokenizer/materialization support

**Optional files**

- `taxonomy-dsl/src/main/java/com/taxonomy/dsl/parser/DslTokenizer.java` for new block keywords
- DTO/controller/export/indexing changes only if the new DSL shape leaves the DSL layer

**Required tests**

- `taxonomy-dsl/src/test/java/com/taxonomy/dsl/TaxDslParserTest.java`
- `taxonomy-dsl/src/test/java/com/taxonomy/dsl/TaxDslSerializerTest.java`
- `taxonomy-dsl/src/test/java/com/taxonomy/dsl/TaxDslRoundtripTest.java`
- `taxonomy-dsl/src/test/java/com/taxonomy/dsl/AstToModelMapperTest.java`
- `taxonomy-dsl/src/test/java/com/taxonomy/dsl/ModelToAstMapperTest.java`
- `taxonomy-dsl/src/test/java/com/taxonomy/dsl/DslValidatorTest.java`
- `taxonomy-dsl/src/test/java/com/taxonomy/dsl/diff/ModelDifferTest.java`
- `taxonomy-app/src/test/java/com/taxonomy/dsl/DslMaterializeServiceTest.java`
- `taxonomy-app/src/test/java/com/taxonomy/dsl/storage/DslGitRepositoryTest.java`
  when persisted DSL shape changes

**Documentation updates**

- `docs/dev/tasks/add-dsl-property.md`
- `docs/en/ARCHITECTURE.md` if the language surface or persisted model changes materially

**Common failure modes**

- parser accepts a new property but serializer/mappers silently drop it
- round-trip idempotence breaks, creating noisy Git diffs
- diff/materialization logic still assumes the old model
- new block keyword added to the parser but omitted from tokenizer or validation

---

## Safe-add checklist

Before you add a new extension or extend an existing one:

1. Identify whether the area has an **explicit SPI** or an **implicit documented anchor**.
2. Start from the file listed in the **Stable starting point** table above.
3. Update the owning registry/facade/anchor first, not the call sites.
4. Add or extend the focused tests listed for that area.
5. Update user/admin/developer docs in the same change when behavior or workflow changes.
6. For explicit SPIs, verify the new descriptor is discoverable through
   `ExtensionRegistry` / `/api/extensions` where applicable.
7. For UI-facing changes, verify HTML, JS, and i18n all move together.
8. For DSL and persisted-model changes, verify round-trip and materialization behavior.

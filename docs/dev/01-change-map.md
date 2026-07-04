# Change Map

Quick-reference table mapping common task types to the packages, entry points,
tests, and documentation you need.

For step-by-step instructions, follow the link in the **Task page** column.
For the stable extension anchor behind each task, follow the link in the
**Extension guide** column.

---

## Common change types

| Task | Primary module(s) | Entry point(s) | Test command | Task page | Extension guide |
|---|---|---|---|---|---|
| Add a new LLM provider | `taxonomy-extension-api`, `taxonomy-app` | `LlmProviderExtension.java` + `LlmGatewayRegistry.java` | `mvn test -pl taxonomy-app` | [add-llm-provider](tasks/add-llm-provider.md) | [LLM providers](07-extension-points.md#llm-providers) |
| Add a new export format | `taxonomy-extension-api`, `taxonomy-export`, `taxonomy-app` | `ExportFormatExtension.java` + `ExportFormatExtensionRegistry.java` | `mvn test -pl taxonomy-export` | [add-export-format](tasks/add-export-format.md) | [Export formats](07-extension-points.md#export-formats) |
| Add a new relation type | `taxonomy-domain`, `taxonomy-app`, `taxonomy-dsl` | `RelationType.java` + `RelationCompatibilityMatrix.java` | `mvn test -pl taxonomy-domain` + `mvn test -pl taxonomy-app` | [add-relation-type](tasks/add-relation-type.md) | [Relation types](07-extension-points.md#relation-types-and-compatibility-rules) |
| Add an architecture view step | `taxonomy-app` | `ArchitecturePipelineStep.java` + `ArchitecturePipelineStepRegistry.java` | `mvn test -pl taxonomy-app` | [add-architecture-view-step](tasks/add-architecture-view-step.md) | [Pipeline steps](07-extension-points.md#architecture-view-pipeline-steps) |
| Add a document import mapping | `taxonomy-app` | `DocumentAnalysisService.java` + `DocumentParserService.java` | `mvn test -pl taxonomy-app` | [add-document-import-mapping](tasks/add-document-import-mapping.md) | [Import profiles / mappings](07-extension-points.md#import-profiles-and-document-mappings) |
| Add a workspace operation | `taxonomy-app` | `WorkspaceManager.java` + `VersioningFacade.java` | `mvn test -pl taxonomy-app` | [add-workspace-operation](tasks/add-workspace-operation.md) | [Workspace/versioning](07-extension-points.md#workspace-and-versioning-operations) |
| Add a UI panel | `taxonomy-app` | `resources/templates/index.html` + `static/js/` | `mvn verify -DexcludedGroups=real-llm` | [add-ui-panel](tasks/add-ui-panel.md) | [UI panels](07-extension-points.md#ui-panels) |
| Add a DSL property | `taxonomy-dsl`, `taxonomy-app` | `TaxDslParser.java` + `TaxDslSerializer.java` | `mvn test -pl taxonomy-dsl` | [add-dsl-property](tasks/add-dsl-property.md) | [DSL grammar / properties](07-extension-points.md#dsl-grammar-and-property-additions) |

---

## Package-to-task index

Use this index if you have already located a file and want to find the relevant task page.

| Package / path | Likely task pages |
|---|---|
| `taxonomy-domain/…/dto/` | [add-export-format](tasks/add-export-format.md), [add-relation-type](tasks/add-relation-type.md), [add-workspace-operation](tasks/add-workspace-operation.md) |
| `taxonomy-domain/…/model/` | [add-relation-type](tasks/add-relation-type.md) |
| `taxonomy-dsl/…/parser/` | [add-dsl-property](tasks/add-dsl-property.md) |
| `taxonomy-dsl/…/serializer/` | [add-dsl-property](tasks/add-dsl-property.md) |
| `taxonomy-dsl/…/validation/` | [add-dsl-property](tasks/add-dsl-property.md), [add-relation-type](tasks/add-relation-type.md) |
| `taxonomy-export/…/export/` | [add-export-format](tasks/add-export-format.md) |
| `taxonomy-app/…/analysis/service/` | [add-llm-provider](tasks/add-llm-provider.md) |
| `taxonomy-app/…/architecture/service/` | [add-architecture-view-step](tasks/add-architecture-view-step.md) |
| `taxonomy-app/…/export/controller/` | [add-export-format](tasks/add-export-format.md) |
| `taxonomy-app/…/provenance/service/` | [add-document-import-mapping](tasks/add-document-import-mapping.md) |
| `taxonomy-app/…/relations/service/` | [add-relation-type](tasks/add-relation-type.md) |
| `taxonomy-app/…/workspace/service/` | [add-workspace-operation](tasks/add-workspace-operation.md) |
| `resources/templates/index.html` | [add-ui-panel](tasks/add-ui-panel.md) |
| `resources/static/js/` | [add-ui-panel](tasks/add-ui-panel.md) |
| `resources/prompts/` | [add-llm-provider](tasks/add-llm-provider.md) |

---

## Cross-cutting concerns

Some tasks always require changes in multiple layers.
The table below lists the layers you must update for each concern.

| Concern | Layers always required |
|---|---|
| New REST endpoint | Controller → Service → DTO → Test → `API_REFERENCE.md` |
| New GUI feature | Controller → Service → `index.html` → JS module → i18n strings → Screenshot |
| New export format | Exporter class → `ExportConfig` → Controller endpoint → Test |
| New LLM provider | `LlmService` → `application.properties` → `CONFIGURATION_REFERENCE.md` |
| New DSL block/property | Parser → Serializer → Mapper → Validator → Tokenizer → Tests |
| New relation type | `RelationType` enum → Compatibility matrix → Validator → Seed data |

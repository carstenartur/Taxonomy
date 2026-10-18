# Change Map

Quick-reference table mapping common task types to their current owners, entry
points, focused checks, and documentation. See the
[module inventory](../en/MODULE_BOUNDARIES.md) for the complete reactor and
cross-context ownership contracts.

For step-by-step instructions, follow the link in the **Task page** column.
For the stable extension anchor behind each task, follow the link in the
**Extension guide** column.

---

## Common change types

| Task | Primary module(s) | Entry point(s) | Test command | Task page | Extension guide |
|---|---|---|---|---|---|
| Add a new LLM provider | Independent plugin using `taxonomy-extension-api`; host policy in `taxonomy-analysis` | `LlmTransportExtension.java` + `ProviderId.java` + `TaxonomyPlugin.java` | `./mvnw -pl taxonomy-analysis -am test`, plus the task's independent plugin checks | [add-llm-provider](tasks/add-llm-provider.md) | [LLM providers](07-extension-points.md#llm-providers) |
| Add a new export format | `taxonomy-export`; independent plugin when external; host adapter in `taxonomy-app` | `ExportFormatExtension.java` + `TaxonomyPlugin.java` + `ExportFormatExtensionRegistry.java` | `./mvnw -pl taxonomy-export,taxonomy-app -am test`, plus external packaging checks when applicable | [add-export-format](tasks/add-export-format.md) | [Export formats](07-extension-points.md#export-formats) |
| Add a new relation type | `taxonomy-domain`, `taxonomy-knowledge`, `taxonomy-dsl` | `RelationType.java` + `RelationCompatibilityMatrix.java` | `./mvnw -pl taxonomy-domain,taxonomy-knowledge,taxonomy-dsl -am test` | [add-relation-type](tasks/add-relation-type.md) | [Relation types](07-extension-points.md#relation-types-and-compatibility-rules) |
| Add an architecture view step | `taxonomy-architecture` | `ArchitecturePipelineStep.java` + `ArchitecturePipelineStepRegistry.java` | `./mvnw -pl taxonomy-architecture -am test` | [add-architecture-view-step](tasks/add-architecture-view-step.md) | [Pipeline steps](07-extension-points.md#architecture-view-pipeline-steps) |
| Add a document import mapping | `taxonomy-app` for provenance; `taxonomy-knowledge` for framework import profiles | `DocumentAnalysisService.java` + `DocumentParserService.java`; `ImportProfileRegistry.java` for framework profiles | `./mvnw -pl taxonomy-app -am test` | [add-document-import-mapping](tasks/add-document-import-mapping.md) | [Import profiles / mappings](07-extension-points.md#import-profiles-and-document-mappings) |
| Add a workspace operation | `taxonomy-workspace`; `taxonomy-app` for cross-context adapters | `WorkspaceManager.java` + `VersioningFacade.java` | `./mvnw -pl taxonomy-workspace -am test` | [add-workspace-operation](tasks/add-workspace-operation.md) | [Workspace/versioning](07-extension-points.md#workspace-and-versioning-operations) |
| Add a UI panel | `taxonomy-app`, plus the feature that supplies its behavior | `taxonomy-app/src/main/resources/templates/index.html` + `taxonomy-app/src/main/resources/static/js/` | Canonical verification below, including UI evidence | [add-ui-panel](tasks/add-ui-panel.md) | [UI panels](07-extension-points.md#ui-panels) |
| Add a DSL property | `taxonomy-dsl`; owning feature/application adapters as needed | `TaxDslParser.java` + `TaxDslSerializer.java` | `./mvnw -pl taxonomy-dsl -am test` | [add-dsl-property](tasks/add-dsl-property.md) | [DSL grammar / properties](07-extension-points.md#dsl-grammar-and-property-additions) |

These are iteration commands, not full acceptance. Include application tests when
composition, security, persistence, or cross-context behavior changes. Use `-am`
with module selections on a fresh reactor. The canonical verification entry point is
`./mvnw -B verify -Pci -DrunOnnxTests=true`; prerequisites, companion CI lanes,
browser evidence, and remaining local-equivalence gaps are documented in
[Maven Verification](MAVEN_VERIFICATION.md).

For a provider, first check whether the existing `CUSTOM_OPENAI` configuration is
sufficient. A new transport uses the open `ProviderId` contract and a
`TaxonomyPlugin` ServiceLoader entry; adding a built-in enum value or changing
`LlmService` is not the general extension path.

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
| `taxonomy-extension-api/src/main/java/com/taxonomy/extension/api/llm/` | [add-llm-provider](tasks/add-llm-provider.md) |
| `taxonomy-extension-api/src/main/java/com/taxonomy/extension/api/plugin/` | [add-llm-provider](tasks/add-llm-provider.md), [add-export-format](tasks/add-export-format.md) |
| `taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/` | [add-llm-provider](tasks/add-llm-provider.md) |
| `taxonomy-architecture/src/main/java/com/taxonomy/architecture/pipeline/` | [add-architecture-view-step](tasks/add-architecture-view-step.md) |
| `taxonomy-app/src/main/java/com/taxonomy/export/controller/` | [add-export-format](tasks/add-export-format.md) |
| `taxonomy-app/src/main/java/com/taxonomy/export/service/` | [add-export-format](tasks/add-export-format.md) |
| `taxonomy-app/src/main/java/com/taxonomy/provenance/service/` | [add-document-import-mapping](tasks/add-document-import-mapping.md) |
| `taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/importer/` | [add-document-import-mapping](tasks/add-document-import-mapping.md) |
| `taxonomy-knowledge/src/main/java/com/taxonomy/relations/service/` | [add-relation-type](tasks/add-relation-type.md) |
| `taxonomy-workspace/src/main/java/com/taxonomy/workspace/service/` | [add-workspace-operation](tasks/add-workspace-operation.md) |
| `taxonomy-workspace/src/main/java/com/taxonomy/versioning/service/` | [add-workspace-operation](tasks/add-workspace-operation.md) |
| `taxonomy-app/src/main/resources/templates/index.html` | [add-ui-panel](tasks/add-ui-panel.md) |
| `taxonomy-app/src/main/resources/static/js/` | [add-ui-panel](tasks/add-ui-panel.md) |
| `taxonomy-analysis/src/main/resources/prompts/` | [add-llm-provider](tasks/add-llm-provider.md) |

---

## Cross-cutting concerns

Check the affected layers for each concern. Reuse existing generic routes and
extension contracts; a contribution does not automatically require editing every
host layer.

| Concern | Layers and contracts to check |
|---|---|
| New REST endpoint | Owning controller/service → API contract/DTO → tests → `API_REFERENCE.md` |
| New GUI feature | Owning feature/API → application template/JS → i18n → current screenshots and UI checks |
| New export format | `ExportFormatExtension` → plugin entry when external → generic route/capability discovery → invocation and packaging tests |
| New LLM provider | `LlmTransportExtension` / `ProviderId` → plugin entry and operator configuration → host execution-policy checks → `CONFIGURATION_REFERENCE.md` |
| New DSL block/property | Parser/AST → serializer → mappers → validator → tokenizer when syntax changes → tests |
| New relation type | `RelationType` → knowledge compatibility/validation → DSL mappings and relevant seed/fixture data → tests |

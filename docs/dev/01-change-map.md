# Change Map

Quick-reference table mapping common task types to the packages, entry points,
tests, and documentation you need.

For step-by-step instructions, follow the link in the **Task page** column.

---

## Common change types

| Task | Primary module(s) | Entry point(s) | Test command | Task page |
|---|---|---|---|---|
| Add a new LLM provider | `taxonomy-app` | `analysis/service/LlmService.java` | `mvn test -pl taxonomy-app` | [add-llm-provider](tasks/add-llm-provider.md) |
| Add a new export format | `taxonomy-export`, `taxonomy-app` | `export/` + `ExportApiController.java` | `mvn test -pl taxonomy-export` | [add-export-format](tasks/add-export-format.md) |
| Add a new relation type | `taxonomy-domain`, `taxonomy-app` | `model/RelationType.java` | `mvn test -pl taxonomy-domain` + `mvn test -pl taxonomy-app` | [add-relation-type](tasks/add-relation-type.md) |
| Add an architecture view step | `taxonomy-app` | `architecture/service/RequirementArchitectureViewService.java` | `mvn test -pl taxonomy-app` | [add-architecture-view-step](tasks/add-architecture-view-step.md) |
| Add a document import mapping | `taxonomy-app` | `provenance/service/DocumentAnalysisService.java` | `mvn test -pl taxonomy-app` | [add-document-import-mapping](tasks/add-document-import-mapping.md) |
| Add a workspace operation | `taxonomy-app` | `workspace/service/WorkspaceManager.java` | `mvn test -pl taxonomy-app` | [add-workspace-operation](tasks/add-workspace-operation.md) |
| Add a UI panel | `taxonomy-app` | `resources/templates/index.html` + `static/js/` | `mvn verify -DexcludedGroups=real-llm` | [add-ui-panel](tasks/add-ui-panel.md) |
| Add a DSL property | `taxonomy-dsl` | `dsl/parser/TaxDslParser.java` | `mvn test -pl taxonomy-dsl` | [add-dsl-property](tasks/add-dsl-property.md) |

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

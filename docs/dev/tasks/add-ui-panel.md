# Task: Add a UI Panel

## Goal

Add a panel, tab, accordion section, or modal dialog to the relevant Thymeleaf
page. The main taxonomy page is `index.html`; the application also has dedicated
editor, workbench, portfolio, and administration pages. Start from the page that
owns the intended workflow.

---

## Primary entry points

| File | What to do |
|---|---|
| `taxonomy-app/src/main/resources/templates/` | Choose the owning page or fragment and add the panel structure |
| `taxonomy-app/src/main/resources/static/js/` | Create or extend a JS module for the panel's behaviour |

---

## Files usually touched

- The owning template, such as `taxonomy-app/src/main/resources/templates/index.html`,
  `architecture-editor.html`, or `architecture-workbench.html` — panel HTML
- A module in the relevant area of `taxonomy-app/src/main/resources/static/js/`
  — use the current directory structure rather than assuming a flat file layout
- The matching English/German bundle pair under `taxonomy-app/src/main/resources/i18n/`
  — labels, tooltips, and help text. Use the owning feature bundle when one exists;
  the general bundles are `messages.properties` and `messages_de.properties`
- `taxonomy-app/src/main/java/com/taxonomy/shared/config/I18nConfig.java` — only
  when a new feature bundle needs registration
- The controller/service in the owning feature module — when new data is needed.
  `taxonomy-app` owns cross-context adapters and application-local capabilities;
  see [current module ownership](../../en/MODULE_BOUNDARIES.md)

---

## Files usually not touched

- `taxonomy-dsl/` — UI panels do not interact with the DSL parser directly
- `taxonomy-export/` — export panels call existing export endpoints; no exporter changes needed
- `taxonomy-domain/` — reuse existing shared DTOs when their contract fits
- `taxonomy-app/src/main/resources/static/css/` — the application uses Bootstrap 5 utility classes;
  avoid adding custom CSS unless absolutely necessary

---

## Backend endpoint(s)

Before creating a new endpoint, check whether an existing endpoint already provides
the data the panel needs:

| Area | Existing endpoint base path |
|---|---|
| Taxonomy catalog | `/api/taxonomy` |
| Search | `/api/search` |
| Analysis | `/api/analyze`, `/api/analyze-stream`, `/api/analysis-runs` |
| Relations | `/api/relations` |
| Architecture, impact, gaps, recommendations | `/api/architecture`, `/api/graph`, `/api/gap`, `/api/recommend` |
| Export | `/api/diagram`, `/api/scores/export` |
| Workspace | `/api/workspace` |
| DSL / Git | `/api/dsl`, `/api/git` |
| Document import / provenance | `/api/documents`, `/api/provenance` |
| Operational tools | `/api/diagnostics`, `/api/prompts` with their administrative authorization |

These are existing route families; check the controller for the precise method,
payload, feature availability, authorization, and workspace pin. Administrative
diagnostics are an operational tool, not a generic readiness check. For a new
endpoint, follow the [REST endpoint guidance](../../en/DEVELOPER_GUIDE.md#adding-a-new-rest-endpoint).

---

## Frontend module(s)

The current JS directories contain shared behavior, API adapters, and workflow
modules. Paths below are relative to
`taxonomy-app/src/main/resources/static/js/`:

| Module | Panel |
|---|---|
| `core/taxonomy-analysis.js`, `core/taxonomy-scoring.js` | Analysis input, results, and requirement architecture rendering |
| `core/taxonomy-browse.js`, `core/taxonomy-views.js` | Catalogue browsing and tree visualizations |
| `relations/taxonomy-relations.js` and the relation/proposal/hypothesis command adapters beside it | Projected reads and Git-authoritative changes/reviews |
| `shared/taxonomy-export.js`, `shared/decision-export-dialog.js` | Contextual exports and shared report choices |
| `workspace/taxonomy-workspace-provisioning.js`, `workspace/taxonomy-workspace-sync.js` | Workspace provisioning and synchronization |
| `versioning/taxonomy-context-bar.js`, `versioning/taxonomy-versions.js` | Context navigation and version history |
| `shared/taxonomy-dsl-editor.js` | Main-page DSL editor; the dedicated editor has its own `architecture-editor.js` |
| `architecture-workbench.js` | Dedicated architecture workbench |
| `shared/taxonomy-search.js` | Full-text and semantic search |
| `shared/taxonomy-document-import.js`, `shared/taxonomy-import.js` | Document/provenance and framework import workflows |
| `api/` | Existing workflow-specific HTTP clients |

Follow the nearby module's IIFE or ES-module pattern and initialization order.
Load a new script from its owning template with the existing Thymeleaf `th:src`
URL convention. Reuse the applicable API client, CSRF handling, role surface, and
operation/workspace context rather than creating a second routing layer.

---

## DTOs / domain types

Reuse the existing shared DTOs in `taxonomy-domain/src/main/java/com/taxonomy/dto/`.
Add a new DTO only if the panel displays data that has no existing DTO representation.
Put shared contracts in the appropriate framework-free API/domain module;
feature-local request/response records can remain with their owning feature.
UI-specific convenience does not justify moving feature entities into the domain
foundation.

---

## Tests to run

```bash
# Existing Maven-owned browser and accessibility checks
./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true

# Canonical broader verification
./mvnw -B verify -Pci -DrunOnnxTests=true
```

Run focused controller/service tests in the owning module with `-pl <module> -am`
when its behavior changes. Existing application checks include
`TaxonomyApplicationTests`, `UiSemanticsBridgeContractTest`, and workflow-specific
contracts such as `RelationGitCommandUiContractTest`. Select the relevant browser
scenario for the changed role, page, and state; a context-load test alone does not
verify a panel.

The UI profile has browser/runtime prerequisites and produces evidence under
`target/ui-verification/`. Its skip flags suppress unrelated Java suites while
the profile owns the UI checks. See [testing by change type](../06-testing-by-change-type.md#browser-and-accessibility-reproduction)
and [Maven Verification](../MAVEN_VERIFICATION.md) for companion lanes and known
local-equivalence gaps. A default or reduced run is not full CI evidence.

---

## Documentation / screenshot updates

- `docs/en/USER_GUIDE.md` — add a walkthrough of the new panel
- `docs/en/FEATURE_MATRIX.md` — add a row for the new feature
- Screenshots: capture the panel's actual role/state through its existing owner;
  follow [capture ownership and provenance rules](../../../.github/copilot-ref-screenshots.md).
  Preserve source/version and input identity, and distinguish deterministic
  illustrations from persisted-snapshot, browser, or live-provider evidence

---

## Common pitfalls

1. **Bilingual requirement:** Every user-facing string (button label, tooltip,
   heading, help text, error message) needs matching keys in the English and German
   versions of its registered message bundle.
   Verify both locales render usable labels and help text rather than raw keys
   or unintended fallback text.

2. **Bootstrap 5 grid:** The UI uses Bootstrap 5. Use `col-*`, `d-none`,
   `d-flex`, and standard Bootstrap components. Do not add custom CSS for
   layout concerns that Bootstrap already handles.

3. **Panel activation / deactivation:** The UI hides and shows panels by toggling
   CSS classes. Follow the same pattern as existing panels to avoid panels
   interfering with each other's state.

4. **Streaming panels:** Follow the existing analysis-session API/transport
   adapters, which use SSE for operation events. Preserve cancellation, stale
   generation guards, and the operation's original workspace pin.

5. **CSRF:** State-modifying POST/PUT/PATCH/DELETE calls from the JS modules must
   include the CSRF token. The existing modules read it from the `<meta>` tag
   added by Thymeleaf; follow the same pattern.

6. **Authorization:** Match template/role-surface visibility to the backend's
   authorization and resource-ownership checks. Reuse the existing security
   configuration and verify allowed and denied roles in the browser/API checks.
   Hiding a panel alone does not authorize its endpoint.

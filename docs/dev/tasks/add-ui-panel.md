# Task: Add a UI Panel

## Goal

Add a new panel (tab, accordion section, or modal dialog) to the single-page
Thymeleaf UI — for example, a new analysis result section, an admin tool, or
a new visualisation widget.

---

## Primary entry points

| File | What to do |
|---|---|
| `taxonomy-app/src/main/resources/templates/index.html` | Add the HTML structure for the new panel |
| `taxonomy-app/src/main/resources/static/js/` | Create or extend a JS module for the panel's behaviour |

---

## Files usually touched

- `taxonomy-app/src/main/resources/templates/index.html` — panel HTML (Bootstrap 5 structure)
- `taxonomy-app/src/main/resources/static/js/<panel-name>.js` — new JS module (preferred) or extension to an existing module
- `taxonomy-app/src/main/resources/messages.properties` — English i18n strings for labels, tooltips, and help text
- `taxonomy-app/src/main/resources/messages_de.properties` — German translations (required for all user-facing text)
- `taxonomy-app/src/main/java/com/taxonomy/<area>/controller/<AreaController>.java` — the backing REST endpoint (if new data is needed)

---

## Files usually not touched

- `taxonomy-dsl/` — UI panels do not interact with the DSL parser directly
- `taxonomy-export/` — export panels call existing export endpoints; no exporter changes needed
- `taxonomy-domain/` — reuse existing DTOs; add a new DTO only if the panel displays genuinely new data
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
| Analysis | `/api/analysis` |
| Relations | `/api/relations` |
| Architecture views | `/api/architecture` |
| Export | `/api/export` |
| Workspace | `/api/workspace` |
| DSL / Git | `/api/dsl`, `/api/git` |
| Admin | `/api/admin` |

If you must add a new endpoint, follow the [add-rest-endpoint pattern](../../en/DEVELOPER_GUIDE.md#adding-a-new-rest-endpoint).

---

## Frontend module(s)

The UI is structured as a set of independent JS modules, each responsible for one panel:

| Module | Panel |
|---|---|
| `analysis.js` | Analysis input and result display |
| `tree.js` | Taxonomy tree with match overlays |
| `relations.js` | Relation CRUD and proposal review |
| `export.js` | Export format buttons and downloads |
| `workspace.js` | Workspace switching and context navigation |
| `dsl.js` | DSL editor and commit history |
| `architecture.js` | Architecture views and gap analysis |
| `search.js` | Full-text and KNN search |
| `admin.js` | Admin tools panel |
| `import.js` | Document import and framework import |

For a new panel, create a new module (`<panel-name>.js`) following the
IIFE / module pattern used in existing modules.
Register the module in `index.html` via a `<script src="…">` tag.

---

## DTOs / domain types

Reuse the existing DTOs in `taxonomy-domain/…/dto/`.
Add a new DTO only if the panel displays data that has no existing DTO representation.
New DTOs belong in `taxonomy-domain`, not in `taxonomy-app`.

---

## Tests to run

```bash
# Full verify required for any UI change
mvn verify -DexcludedGroups="real-llm"
```

Relevant test classes:
- `<AreaController>Test` — the REST controller backing the panel
- `TaxonomyApplicationTests` — Spring context loads successfully

---

## Documentation / screenshot updates

- `docs/en/USER_GUIDE.md` — add a walkthrough of the new panel
- `docs/en/FEATURE_MATRIX.md` — add a row for the new feature
- Screenshots: add a screenshot showing the panel in a healthy state;
  follow the naming convention `NN-descriptive-name.png` in `docs/images/`.
  Regenerate via `ScreenshotGeneratorIT` if the panel shows existing data.

---

## Common pitfalls

1. **Bilingual requirement:** Every user-facing string (button label, tooltip,
   heading, help text, error message) must exist in both `messages.properties`
   (English) and `messages_de.properties` (German).
   Missing translations cause Thymeleaf rendering errors.

2. **Bootstrap 5 grid:** The UI uses Bootstrap 5. Use `col-*`, `d-none`,
   `d-flex`, and standard Bootstrap components. Do not add custom CSS for
   layout concerns that Bootstrap already handles.

3. **Panel activation / deactivation:** The UI hides and shows panels by toggling
   CSS classes. Follow the same pattern as existing panels to avoid panels
   interfering with each other's state.

4. **SSE panels:** Panels that show streaming LLM responses use `EventSource`
   (Server-Sent Events). If your panel streams data, follow the pattern in
   `analysis.js` — do not use WebSockets or long-polling.

5. **CSRF:** All state-modifying POST/DELETE calls from the JS modules must
   include the CSRF token. The existing modules read it from the `<meta>` tag
   added by Thymeleaf; follow the same pattern.

6. **Admin-only panels:** If the panel should only be visible to admins, gate
   it with `sec:authorize="hasRole('ADMIN')"` in the Thymeleaf template and
   `@PreAuthorize("hasRole('ADMIN')")` on the backing controller method.

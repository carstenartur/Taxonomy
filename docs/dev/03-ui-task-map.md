# 03 – UI API-Client Convention

## Rule

**New UI code must not call `fetch('/api/…')` directly.**
All HTTP calls to backend REST endpoints must go through a named function in
one of the `static/js/api/` modules.

---

## Rationale

* Centralised error handling and status checking.
* CSRF header injection in one place.
* Endpoint URLs are declared once; feature modules only call named functions.
* Easier to find, review, and update API surface when endpoint paths change.

---

## File layout

```
static/js/api/
  taxonomy-api-client.js   ← base HTTP helpers (load first)
  import-api.js            ← /api/import/*
  analysis-api.js          ← /api/gap/analyze, /api/patterns/detect,
                               /api/recommend, /api/graph/node/*,
                               /api/graph/apqc-hierarchy
  workspace-api.js         ← /api/workspace/* (add when migrating)
  versioning-api.js        ← /api/versions/*, /api/context/* (add when migrating)
  relations-api.js         ← /api/relations/* (add when migrating)
```

`taxonomy-api-client.js` **must be loaded before** all other `api/*.js` files,
which in turn must be loaded before the feature modules that use them.
This ordering is enforced in `index.html`.

---

## `TaxonomyApiClient` reference

| Function | Description |
|---|---|
| `getJson(url)` | HTTP GET → parsed JSON |
| `sendJson(url, body[, method])` | HTTP POST/PUT/DELETE with JSON body → parsed JSON |
| `sendFormData(url, formData[, method])` | HTTP POST with `FormData` body → parsed JSON |
| `deleteJson(url)` | HTTP DELETE → parsed JSON |

All helpers:
* throw an `Error` if the HTTP status is not OK (`!res.ok`)
* throw an `Error` if the response body is not valid JSON
* inject the Spring Security CSRF header automatically when
  `<meta name="_csrf">` and `<meta name="_csrf_header">` are present

---

## Adding a new API call

1. Identify which `api/*.js` module owns the endpoint family (or create a new
   file following the naming pattern above).
2. Add a named function that calls the appropriate `TaxonomyApiClient` helper.
3. Document the HTTP method and path in the JSDoc comment.
4. Call the new named function from the feature module.
5. Load the `api/*.js` file in `index.html` **before** the first feature module
   that uses it.

### Example

```js
// api/workspace-api.js
window.WorkspaceApi = (function () {
    'use strict';
    var client = window.TaxonomyApiClient;

    /** POST /api/workspace/sync  @returns {Promise<any>} */
    function sync(payload) {
        return client.sendJson('/api/workspace/sync', payload);
    }

    return { sync: sync };
})();
```

Then in the feature module:

```js
// Before: direct fetch
fetch('/api/workspace/sync', { method: 'POST', headers: {...}, body: JSON.stringify(payload) })

// After: named API call
WorkspaceApi.sync(payload)
```

---

## Migrated modules

| Feature module | API module used |
|---|---|
| `shared/taxonomy-import.js` | `api/import-api.js` |
| `shared/taxonomy-graph.js` | `api/analysis-api.js` |

Modules not yet migrated still call `fetch(…)` directly.  Migrate them
incrementally, one feature module at a time, following the pattern above.

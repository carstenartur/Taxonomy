# Taxonomy Architecture Analyzer — API Reference

All endpoints require **HTTP Basic authentication** (`-u admin:admin`) unless listed as public.
CSRF is disabled for `/api/**` — REST clients do not need CSRF tokens.

Interactive docs: [`/swagger-ui.html`](http://localhost:8080/swagger-ui.html) (when the app is running).

> For end-to-end workflow examples using curl, see [Curl Workflow Examples](CURL_EXAMPLES.md).

---

## Table of Contents

- [Authentication](#authentication)
- [Analysis](#analysis)
- [Search](#search)
- [Graph Exploration](#graph-exploration)
- [Relations](#relations)
- [Relation Proposals](#relation-proposals)
- [Export](#export)
- [Reports](#reports)
- [Gap Analysis & Recommendations](#gap-analysis--recommendations)
- [Architecture DSL](#architecture-dsl)
- [Context Navigation](#context-navigation)
- [Git Repository State](#git-repository-state)
- [Administration](#administration)
- [Preferences (Admin-only)](#preferences-admin-only)
- [User Management (Admin-only)](#user-management-admin-only)
- [Help / Documentation](#help--documentation)
- [Error Responses](#error-responses)

---

## Authentication

All `/api/**` endpoints require authentication via **HTTP Basic**:

```bash
curl -u admin:admin http://localhost:8080/api/taxonomy
```

Default credentials: `admin` / `admin` (configurable via `TAXONOMY_ADMIN_PASSWORD`).

For role-based access details, see [SECURITY.md](SECURITY.md).

---

## Analysis

### Score taxonomy nodes against a requirement

```
POST /api/analyze
```

**Request:**

```bash
curl -u admin:admin -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" \
  -d '{"businessText": "Provide integrated communication services for hospital staff", "includeArchitectureView": true}'
```

**Response (200):**

```json
{
  "scores": { "CP-1023": 92, "CO-1011": 88, "CR-1047": 81 },
  "reasons": { "CP-1023": "Directly relevant to communications capability" },
  "architectureView": {
    "layers": [ ... ],
    "relations": [ ... ]
  }
}
```

| Parameter | Type | Required | Description |
|---|---|---|---|
| `businessText` | string | ✅ | Free-text requirement to analyze |
| `includeArchitectureView` | boolean | — | Generate architecture view (default: false) |
| `maxArchitectureNodes` | integer | — | Max nodes in architecture view (default: 20) |
| `provider` | string | — | LLM provider override for this request (e.g. `GEMINI`, `OPENAI`, `DEEPSEEK`, `QWEN`, `LLAMA`, `MISTRAL`, `LOCAL_ONNX`). If omitted, uses the globally configured provider. |

### Streaming analysis (Server-Sent Events)

```
GET /api/analyze-stream?businessText=voice+communications
```

Returns real-time progress events as SSE. Each event contains partial scores.

### Leaf justification

```
POST /api/justify-leaf
```

Generates a natural-language explanation for why a specific leaf node scored as it did.

**Request:**

```bash
curl -u admin:admin -X POST http://localhost:8080/api/justify-leaf \
  -H "Content-Type: application/json" \
  -d '{"nodeCode": "CR-1047", "businessText": "Secure voice communications", "scores": {"CR-1047": 87}}'
```

---

## Search

### Full-text search

```bash
curl -u admin:admin "http://localhost:8080/api/search?q=voice+communications&maxResults=20"
```

### Semantic search (requires embeddings enabled)

```bash
curl -u admin:admin "http://localhost:8080/api/search/semantic?q=secure+communications&maxResults=20"
```

### Hybrid search (Reciprocal Rank Fusion)

```bash
curl -u admin:admin "http://localhost:8080/api/search/hybrid?q=voice+communications&maxResults=20"
```

### Graph-semantic search

```bash
curl -u admin:admin "http://localhost:8080/api/search/graph?q=communications&maxResults=20"
```

### Find similar nodes

```bash
curl -u admin:admin "http://localhost:8080/api/search/similar/CR-1047?topK=5"
```

---

## Graph Exploration

### Upstream neighbours

```bash
curl -u admin:admin "http://localhost:8080/api/graph/node/CR-1047/upstream?maxHops=2"
```

**Response (200):**

```json
{
  "sourceNode": "CR-1047",
  "sourceTitle": "Infrastructure Services",
  "nodes": [
    { "code": "CO-1011", "title": "Communications Access Services", "distance": 1 }
  ]
}
```

### Downstream neighbours

```bash
curl -u admin:admin "http://localhost:8080/api/graph/node/CR-1047/downstream?maxHops=2"
```

### Failure impact analysis

```bash
curl -u admin:admin "http://localhost:8080/api/graph/node/CR-1047/failure-impact?maxHops=3"
```

### Requirement impact analysis

```bash
curl -u admin:admin -X POST http://localhost:8080/api/graph/impact \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CR-1047": 87, "CP-1023": 92}, "businessText": "Secure voice", "maxHops": 2}'
```

---

## Relations

### List all relations

```bash
curl -u admin:admin "http://localhost:8080/api/relations"
curl -u admin:admin "http://localhost:8080/api/relations?type=REALIZES"
```

### Get relations for a node

```bash
curl -u admin:admin "http://localhost:8080/api/node/CP-1023/relations"
```

### Create a relation (requires ARCHITECT or ADMIN role)

```bash
curl -u admin:admin -X POST http://localhost:8080/api/relations \
  -H "Content-Type: application/json" \
  -d '{"sourceCode": "CP-1023", "targetCode": "CR-1047", "relationType": "REALIZES"}'
```

### Delete a relation (requires ARCHITECT or ADMIN role)

```bash
curl -u admin:admin -X DELETE http://localhost:8080/api/relations/42
```

---

## Relation Proposals

### Generate AI proposals

```bash
curl -u admin:admin -X POST http://localhost:8080/api/proposals/propose \
  -H "Content-Type: application/json" \
  -d '{"sourceCode": "CR-1047", "relationType": "SUPPORTS"}'
```

### List pending proposals

```bash
curl -u admin:admin "http://localhost:8080/api/proposals/pending"
```

### Accept / reject / revert

```bash
curl -u admin:admin -X POST http://localhost:8080/api/proposals/42/accept
curl -u admin:admin -X POST http://localhost:8080/api/proposals/42/reject
curl -u admin:admin -X POST http://localhost:8080/api/proposals/42/revert
```

### Bulk action

```bash
curl -u admin:admin -X POST http://localhost:8080/api/proposals/bulk \
  -H "Content-Type: application/json" \
  -d '{"ids": [42, 43, 44], "action": "ACCEPT"}'
```

---

## Export

### ArchiMate XML

```bash
curl -u admin:admin -X POST http://localhost:8080/api/diagram/archimate \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92, "CO-1011": 88}}' \
  -o architecture.xml
```

### Visio (.vsdx)

```bash
curl -u admin:admin -X POST http://localhost:8080/api/diagram/visio \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92, "CO-1011": 88}}' \
  -o architecture.vsdx
```

### Mermaid

```bash
curl -u admin:admin -X POST http://localhost:8080/api/diagram/mermaid \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92, "CO-1011": 88}}'
```

### JSON (save/load analysis)

```bash
# Export
curl -u admin:admin -X POST http://localhost:8080/api/scores/export \
  -H "Content-Type: application/json" \
  -d '{"requirement": "Secure voice comms", "scores": {"CP-1023": 92}}'

# Import
curl -u admin:admin -X POST http://localhost:8080/api/scores/import \
  -H "Content-Type: application/json" \
  -d @saved-analysis.json
```

---

## Reports

Export analysis results as formatted reports:

```bash
# Markdown
curl -u admin:admin -X POST http://localhost:8080/api/report/markdown \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92}, "businessText": "Secure voice comms", "minScore": 50}'

# HTML
curl -u admin:admin -X POST http://localhost:8080/api/report/html ...

# DOCX
curl -u admin:admin -X POST http://localhost:8080/api/report/docx ... -o report.docx

# JSON
curl -u admin:admin -X POST http://localhost:8080/api/report/json ...
```

---

## Gap Analysis & Recommendations

### Gap analysis

```bash
curl -u admin:admin -X POST http://localhost:8080/api/gap/analyze \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92, "CO-1011": 88}, "businessText": "Secure voice"}'
```

**Response:** Identifies missing relations and incomplete architecture patterns.

### Recommendations

```bash
curl -u admin:admin -X POST http://localhost:8080/api/recommend \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CO-1056": 88, "CR-1047": 81}, "businessText": "Satellite communications"}'
```

**Response:** Suggests additional nodes and relations.

### Pattern detection

```bash
# For a single node
curl -u admin:admin "http://localhost:8080/api/patterns/detect?nodeCode=CP-1023"

# For scored nodes
curl -u admin:admin -X POST http://localhost:8080/api/patterns/detect \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92, "CO-1011": 88}}'
```

---

## Architecture DSL

### Export current architecture as DSL text

```bash
curl -u admin:admin "http://localhost:8080/api/dsl/export"
```

### Commit DSL text (requires ARCHITECT or ADMIN role)

```bash
curl -u admin:admin -X POST "http://localhost:8080/api/dsl/commit?branch=draft&message=initial+architecture" \
  -H "Content-Type: text/plain" \
  -d 'meta { language: "taxdsl"; version: "2.0"; namespace: "example"; }
element CP-1023 type Capability { title: "CIS Capabilities"; }
relation CR-1011 SUPPORTS BP-1327 { status: proposed; }'
```

### View commit history

```bash
curl -u admin:admin "http://localhost:8080/api/dsl/history?branch=draft"
```

### Diff between commits

```bash
curl -u admin:admin "http://localhost:8080/api/dsl/diff/semantic/{beforeId}/{afterId}"
```

### Branch and merge

```bash
# Create branch
curl -u admin:admin -X POST "http://localhost:8080/api/dsl/branches?name=review&fromBranch=draft"

# Merge
curl -u admin:admin -X POST "http://localhost:8080/api/dsl/merge?fromBranch=review&intoBranch=accepted"
```

### Format DSL text

```
POST /api/dsl/format
```

Parses the input DSL, maps it through the canonical model, and re-serializes it into deterministic, Git-diff-friendly output.

```bash
curl -u admin:admin -X POST http://localhost:8080/api/dsl/format \
  -H "Content-Type: text/plain" \
  -d 'element CP-1023 type Capability { title: "CIS"; }'
```

**Response (200):** Formatted DSL text (Content-Type: text/plain).

### Revert a commit

```
POST /api/dsl/revert?commitId={sha}&branch=draft
```

Creates a new commit that reverses the changes of the specified commit using three-way merge.

```bash
curl -u admin:admin -X POST "http://localhost:8080/api/dsl/revert?commitId=abc123&branch=draft"
```

**Response (200):**

```json
{ "commitId": "def456", "branch": "draft", "revertedCommit": "abc123" }
```

### Undo the last commit

```
POST /api/dsl/undo?branch=draft
```

Resets the branch to its parent commit, removing the last commit from history. Cannot undo the initial commit.

```bash
curl -u admin:admin -X POST "http://localhost:8080/api/dsl/undo?branch=draft"
```

**Response (200):**

```json
{ "commitId": "parent-sha", "branch": "draft" }
```

### Restore a version

```
POST /api/dsl/restore?commitId={sha}&branch=draft
```

Creates a new commit with the DSL content from an older commit. This is a forward-moving "restore to version" operation.

```bash
curl -u admin:admin -X POST "http://localhost:8080/api/dsl/restore?commitId=abc123&branch=draft"
```

**Response (200):**

```json
{ "commitId": "new-sha", "branch": "draft", "restoredFrom": "abc123" }
```

### Versioned search

```
GET /api/dsl/history/search-versioned?query={text}&currentBranch=draft&maxResults=50
```

Full-text search across commit history, enriched with version-context metadata.

```bash
curl -u admin:admin "http://localhost:8080/api/dsl/history/search-versioned?query=communication&currentBranch=draft"
```

**Response (200):**

```json
[
  {
    "commitId": "abc123",
    "branch": "draft",
    "timestamp": "2026-01-15T10:30:00Z",
    "matchedElementId": "CP-1023",
    "matchedText": "Added communication capability",
    "relevanceScore": 0.0,
    "onCurrentLineage": true,
    "latestOnCurrentBranch": true,
    "latestOverall": true,
    "contextOpenActions": ["OPEN_READ_ONLY", "CREATE_VARIANT", "COMPARE"]
  }
]
```

---

## Context Navigation

Context Navigation provides version-aware browsing of architecture snapshots. GET endpoints are accessible to any authenticated user; POST endpoints require ARCHITECT or ADMIN role.

### Get current context

```bash
curl -u admin:admin http://localhost:8080/api/context/current
```

**Response (200):**

```json
{
  "contextId": "ctx-001",
  "branch": "draft",
  "commitId": "abc123",
  "timestamp": "2026-01-15T10:30:00Z",
  "mode": "EDITABLE",
  "originContextId": null,
  "originBranch": null,
  "originCommitId": null,
  "openedFromSearch": null,
  "matchedElementId": null,
  "dirty": false
}
```

### Open a context

```bash
curl -u admin:admin -X POST "http://localhost:8080/api/context/open?branch=review&readOnly=true"
```

| Parameter | Type | Required | Description |
|---|---|---|---|
| `branch` | string | — | Branch to open (default: `draft`) |
| `commitId` | string | — | Specific commit SHA (defaults to HEAD) |
| `readOnly` | boolean | — | If `true`, write operations are blocked (default: `true`) |
| `searchQuery` | string | — | Original search query (for breadcrumb display) |
| `elementId` | string | — | Element that triggered the navigation |

### Navigate back

```bash
curl -u admin:admin -X POST http://localhost:8080/api/context/back
```

Returns to the previous context in navigation history (like the browser back button).

### Return to origin

```bash
curl -u admin:admin -X POST http://localhost:8080/api/context/return-to-origin
```

Jumps directly back to the context from which the current context was originally opened.

### Get navigation history

```bash
curl -u admin:admin http://localhost:8080/api/context/history
```

**Response (200):**

```json
[
  {
    "fromContextId": "ctx-001",
    "toContextId": "ctx-002",
    "reason": "SEARCH_OPEN",
    "createdAt": "2026-01-15T10:31:00Z"
  }
]
```

Navigation reasons: `SEARCH_OPEN`, `COMPARE`, `VARIANT_CREATED`, `RETURN`, `MANUAL_SWITCH`.

### Create a variant

```bash
curl -u admin:admin -X POST "http://localhost:8080/api/context/variant?name=experiment-1"
```

Creates a new Git branch from the current context and switches to it.

### Compare contexts

```bash
curl -u admin:admin "http://localhost:8080/api/context/compare?leftBranch=draft&rightBranch=review"
```

| Parameter | Type | Required | Description |
|---|---|---|---|
| `leftBranch` | string | ✅ | Left side branch |
| `leftCommit` | string | — | Specific commit SHA (defaults to HEAD) |
| `rightBranch` | string | ✅ | Right side branch |
| `rightCommit` | string | — | Specific commit SHA (defaults to HEAD) |

**Response (200):**

```json
{
  "left": { "contextId": "...", "branch": "draft", ... },
  "right": { "contextId": "...", "branch": "review", ... },
  "summary": {
    "elementsAdded": 2, "elementsChanged": 1, "elementsRemoved": 0,
    "relationsAdded": 3, "relationsChanged": 0, "relationsRemoved": 1
  },
  "changes": [
    { "changeType": "ADD", "category": "ELEMENT", "id": "CP-1050", "description": "Added element", "beforeValue": null, "afterValue": "..." }
  ],
  "rawDslDiff": "--- left\n+++ right\n@@ ... @@\n..."
}
```

### Preview selective transfer

```bash
curl -u admin:admin -X POST http://localhost:8080/api/context/copy-back/preview \
  -H "Content-Type: application/json" \
  -d '{"sourceContextId": "ctx-002", "targetContextId": "ctx-001", "selectedElementIds": ["CP-1050"], "selectedRelationIds": [], "mode": "COPY"}'
```

**Response (200):** Preview including conflicts, affected views, and the resulting changes.

### Apply selective transfer

```bash
curl -u admin:admin -X POST http://localhost:8080/api/context/copy-back/apply \
  -H "Content-Type: application/json" \
  -d '{"sourceContextId": "ctx-002", "targetContextId": "ctx-001", "selectedElementIds": ["CP-1050"], "selectedRelationIds": [], "mode": "COPY"}'
```

Transfer modes: `COPY` (overwrite conflicts), `CHERRY_PICK` (apply commit), `MERGE_SELECTED` (merge only selected items).

---

## Git Repository State

Endpoints for querying the Git repository state, projection/index freshness, and branch information. Used by the UI to detect stale projections and display repository status.

### Full repository state

```
GET /api/git/state?branch=draft
```

Returns the complete repository state snapshot for a branch, including HEAD commit info, all branches, projection/index freshness, and any in-progress operations.

```bash
curl -u admin:admin "http://localhost:8080/api/git/state?branch=draft"
```

**Response (200):**

```json
{
  "branch": "draft",
  "headCommit": "abc1234",
  "headTimestamp": "2026-03-15T10:30:00Z",
  "headAuthor": "admin",
  "headMessage": "Add CP-1023 capability",
  "branches": ["draft", "main", "feature/comms"],
  "projectionCommit": "abc1234",
  "projectionStale": false,
  "indexCommit": "abc1234",
  "indexStale": false
}
```

| Parameter | Type | Required | Description |
|---|---|---|---|
| `branch` | string | — | Branch to query (default: `draft`) |

### Projection and index freshness

```
GET /api/git/projection?branch=draft
```

Returns which commit the DB projection and search index are built from, and whether they are stale relative to HEAD.

```bash
curl -u admin:admin "http://localhost:8080/api/git/projection?branch=draft"
```

**Response (200):**

```json
{
  "projectionCommit": "abc1234",
  "projectionStale": false,
  "indexCommit": "abc1234",
  "indexStale": false
}
```

### List all branches

```
GET /api/git/branches?branch=draft
```

Returns all Git branches in the repository with their HEAD commit SHA and basic metadata.

```bash
curl -u admin:admin "http://localhost:8080/api/git/branches"
```

### Quick staleness check

```
GET /api/git/stale?branch=draft
```

Lightweight endpoint returning only staleness flags. Used for periodic polling from the UI (every 10 seconds).

```bash
curl -u admin:admin "http://localhost:8080/api/git/stale?branch=draft"
```

**Response (200):**

```json
{
  "projectionStale": false,
  "indexStale": false
}
```

---

## Administration

### System status

```bash
curl -u admin:admin "http://localhost:8080/api/ai-status"
```

**Response (200):**

```json
{
  "level": "FULL",
  "available": true,
  "limited": false,
  "provider": "Google Gemini",
  "availableProviders": ["Google Gemini", "LOCAL_ONNX"]
}
```

| Field | Type | Description |
|---|---|---|
| `level` | string | AI availability level: `FULL`, `LIMITED`, or `UNAVAILABLE` |
| `available` | boolean | `true` for FULL and LIMITED, `false` for UNAVAILABLE (backward-compatible) |
| `limited` | boolean | `true` only when level is LIMITED (local ONNX only) |
| `provider` | string | Active provider name (null when UNAVAILABLE) |
| `availableProviders` | array | List of all providers with configured API keys (always includes LOCAL_ONNX) |

```bash
curl -u admin:admin "http://localhost:8080/api/status/startup"
curl -u admin:admin "http://localhost:8080/api/embedding/status"
```

### LLM diagnostics (ADMIN only)

```bash
curl -u admin:admin "http://localhost:8080/api/diagnostics"
```

---

## Preferences (Admin-only)

Runtime-configurable preferences with a JGit-backed audit trail. Every `PUT` creates a Git commit recording who changed what and when. All endpoints require `ROLE_ADMIN`.

> For details on all available preference keys, see [Preferences](PREFERENCES.md).

### Get all preferences

```
GET /api/preferences
```

Returns all current preference values. Sensitive values (e.g., remote Git tokens) are masked in the response.

```bash
curl -u admin:admin http://localhost:8080/api/preferences
```

**Response (200):**

```json
{
  "llm.rpm": "5",
  "llm.timeout.seconds": "30",
  "rate-limit.per-minute": "10",
  "analysis.min-relevance-score": "70",
  "dsl.default-branch": "draft",
  "dsl.project-name": "Taxonomy Architecture",
  "limits.max-business-text": "5000",
  "limits.max-architecture-nodes": "50",
  "limits.max-export-nodes": "200"
}
```

### Update preferences

```
PUT /api/preferences
```

Updates one or more preferences. Creates a Git commit for audit trail.

```bash
curl -u admin:admin -X PUT http://localhost:8080/api/preferences \
  -H "Content-Type: application/json" \
  -d '{"llm.rpm": "10", "llm.timeout.seconds": "45"}'
```

### Reset to defaults

```
POST /api/preferences/reset
```

Resets all preferences to their built-in defaults. Creates a Git commit recording the reset.

```bash
curl -u admin:admin -X POST http://localhost:8080/api/preferences/reset
```

### Preference change history

```
GET /api/preferences/history
```

Returns the Git log of all preference changes, including timestamps, authors, and what changed.

```bash
curl -u admin:admin http://localhost:8080/api/preferences/history
```

---

## Help / Documentation

The Help system serves rendered Markdown documentation from the `docs/` directory. These endpoints are accessible to any authenticated user.

### Table of contents

```
GET /help
```

Returns a JSON array of all available documentation pages with metadata.

```bash
curl -u admin:admin http://localhost:8080/help
```

**Response (200):**

```json
[
  { "filename": "USER_GUIDE", "title": "User Guide", "icon": "📖", "audience": "Everyone" },
  { "filename": "CONCEPTS", "title": "Concepts", "icon": "💡", "audience": "Everyone" },
  { "filename": "API_REFERENCE", "title": "API Reference", "icon": "🔌", "audience": "Integrators" }
]
```

### Render a documentation page

```
GET /help/{docName}
```

Renders the specified Markdown document as HTML. The response is cached after first render.

```bash
curl -u admin:admin http://localhost:8080/help/USER_GUIDE
```

**Response (200):** HTML string of the rendered Markdown.

| Parameter | Type | Description |
|---|---|---|
| `docName` | string | Document filename without extension. Must match a registered document (e.g., `USER_GUIDE`, `API_REFERENCE`, `SECURITY`). |

Returns `400` if the document name contains invalid characters, `404` if not found.

### Serve documentation image

```
GET /help/images/{imageName}
```

Serves images referenced in documentation files (PNG, JPEG, etc.).

```bash
curl -u admin:admin http://localhost:8080/help/images/15-scored-taxonomy-tree.png --output screenshot.png
```

Returns `400` for invalid filenames, `404` for missing images.

---

## Error Responses

| HTTP Code | Meaning | Common Cause |
|---|---|---|
| `200` | Success | — |
| `400` | Bad Request | Missing or blank `businessText`, invalid node code |
| `401` | Unauthorized | Missing or incorrect credentials |
| `403` | Forbidden | Insufficient role (e.g., USER trying to delete a relation) |
| `404` | Not Found | Invalid node code or proposal ID |
| `423` | Locked | Too many failed login attempts — IP is temporarily locked out |
| `429` | Too Many Requests | Rate limit exceeded (configurable via `TAXONOMY_RATE_LIMIT_PER_MINUTE`) |
| `503` | Service Unavailable | Taxonomy still loading — poll `/api/status/startup` |
| `500` | Server Error | LLM timeout, export I/O error |

---

## User Management (Admin-only)

All user management endpoints require `ROLE_ADMIN` and use HTTP Basic authentication.

### List Users

```bash
curl -u admin:password http://localhost:8080/api/admin/users
```

**Response:**
```json
[
  {
    "id": 1,
    "username": "admin",
    "displayName": "Administrator",
    "email": null,
    "enabled": true,
    "roles": ["ROLE_ADMIN", "ROLE_ARCHITECT", "ROLE_USER"]
  }
]
```

### Create User

```bash
curl -u admin:password -X POST http://localhost:8080/api/admin/users \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"securepass123","roles":["USER","ARCHITECT"],"displayName":"Alice","email":"alice@example.com"}'
```

### Update User

```bash
curl -u admin:password -X PUT http://localhost:8080/api/admin/users/2 \
  -H "Content-Type: application/json" \
  -d '{"displayName":"Alice Updated","roles":["USER","ARCHITECT","ADMIN"]}'
```

### Change User Password

```bash
curl -u admin:password -X PUT http://localhost:8080/api/admin/users/2/password \
  -H "Content-Type: application/json" \
  -d '{"password":"newpass123"}'
```

### Disable User (Soft Delete)

```bash
curl -u admin:password -X DELETE http://localhost:8080/api/admin/users/2
```

> **Safety:** The last remaining admin user cannot be disabled. Attempting to do so returns HTTP 400.

---

## Additional References

| Document | Contents |
|---|---|
| [Curl Examples](CURL_EXAMPLES.md) | Copy-paste curl commands for every endpoint |
| [Security](SECURITY.md) | Authentication, roles, deployment hardening |
| [Configuration](CONFIGURATION_REFERENCE.md) | Environment variables and settings |
| [Swagger UI](http://localhost:8080/swagger-ui.html) | Interactive API explorer (when running) |

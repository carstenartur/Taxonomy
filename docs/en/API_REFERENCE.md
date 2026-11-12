# Taxonomy Architecture Analyzer — API Reference

> **📌 This API reference documents the REST interface for automation and integration.**
> For end-user workflows, use the GUI. Features are not complete when only available via REST.

> **Audience:** This reference is for developers building **automations, CI/CD integrations,
> and administrative scripts**. If you are an end user, start with the
> [User Guide](USER_GUIDE.md) — all features described there are available through the
> web-based GUI.
>
> **Swagger/OpenAPI** (`/swagger-ui.html`) is the authoritative, always-up-to-date
> API documentation. This static reference provides an overview and usage examples.

Authenticated API operations use the authentication configured for the deployment.
Only requests authenticated through supported HTTP Basic or Keycloak Bearer
credentials qualify as stateless protocol calls. A restored browser session retains
CSRF protection for writes, even when an ignored `Authorization` header is present;
`/api/**` is not a blanket CSRF exemption.
Use configured credentials; no reusable default administrator password exists.
See [Security](SECURITY.md) for profiles and permissions.

Interactive docs: [`/swagger-ui.html`](http://localhost:8080/swagger-ui.html) (when the app is running).

> For end-to-end workflow examples using curl, see [Curl Examples for Automation & Integration](CURL_EXAMPLES.md).

---

## Table of Contents

- [Authentication](#authentication)
- [Architecture lifecycle](#architecture-lifecycle-api)
- [Analysis](#analysis)
- [Search](#search)
- [Graph Exploration](#graph-exploration)
- [Relations](#relations)
- [Relation Proposals](#relation-proposals)
- [Export](#export)
- [Reports](#reports)
- [Gap Analysis & Recommendations](#gap-analysis--recommendations)
- [Architecture DSL](#architecture-dsl)
- [Document Import & Provenance](#document-import--provenance)
- [Administration](#administration)
- [Error Responses](#error-responses)

---

## Authentication

For a protected endpoint in a deployment with HTTP Basic enabled:

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" http://localhost:8080/api/taxonomy
```

Use the password configured through `TAXONOMY_ADMIN_PASSWORD`. When it is intentionally empty for a non-production local run, use the one-time credential from the owner-only file whose path is written to the startup log. No reusable default password exists.

`READER`, `USER` (Analyst), `ARCHITECT` and `ADMIN` are independent role assignments.
A Bearer token must satisfy the operation's required role groups **and** all listed
OAuth scopes. See [Security](SECURITY.md) and [Swagger UI](http://localhost:8080/swagger-ui.html)
for permissions and endpoint requirements.

---

<a id="architecture-lifecycle-api"></a>
## Architecture lifecycle

An architecture keeps its repository identity across project completion and later
project contributions. Current requirement text, the selected architecture stand
and the reviewed realization version remain distinct.

| Method | Route | Purpose |
|---|---|---|
| GET | `/api/architecture/lifecycle` | Selected current workspace, historical semantic revision or Git checkpoint, with realization state. |
| GET | `/api/architecture/lifecycle/history` | Bounded, authorized search of actual reachable Git ancestry. |
| POST | `/api/architecture/lifecycle/intake-review` | Read-only candidate assessment before an explicit requirement choice. |
| POST | `/api/architecture/editor/requirements` | `NEW`, `CHANGE` or `LINK` with exact input/version preconditions and publication state. |
| POST | `/api/architecture/lifecycle/realizations/preview` | Preview the exact text-version/dependency review. |
| POST | `/api/architecture/lifecycle/realizations` | Accept the exact review and checkpoint it. |

Overview accepts `commit` or semantic `revision`, never both. Earlier revisions and
Git checkpoints are read-only and exclude today's portfolio overlay. Historical
model text does not replace the current requirement header or frozen analysis source.
Mutation requests carry the exact context, stable command identity and
`If-Match: "workspace-revision-N"`. Inspect publication state: a retained contribution
with `CHECKPOINT_PENDING` or `RECONCILIATION_REQUIRED` is not a completed Git checkpoint.

Explicit editor reconciliation uses
`/api/architecture/editor/versions/reconciliation/preview` and `/accept`, both POST,
with whole-block `LOCAL`/`INCOMING` decisions and a freshly reviewed result. Managed
portfolio invariants still apply; Git omission cannot delete immutable business
history. The full [lifecycle/intake/history contract](PROJECT_PORTFOLIO_API.md#architecture-lifecycle-api)
and [reconciliation contract](PROJECT_PORTFOLIO_API.md#editor-version-reconciliation-api)
describe DTOs, authorization, retries, coverage and recovery. See the
[user workflow](PROJECT_REQUIREMENT_PORTFOLIO.md#architecture-lifecycle).

## Analysis

### Score taxonomy nodes against a requirement

```
POST /api/analyze
```

**Request:**

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/analyze \
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
| `maxArchitectureNodes` | integer | current `limits.max-architecture-nodes` preference | Max nodes in the architecture view; an explicit request value overrides the live runtime preference |

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
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/justify-leaf \
  -H "Content-Type: application/json" \
  -d '{"nodeCode": "CR-1047", "businessText": "Secure voice communications", "scores": {"CR-1047": 87}}'
```

---

## Search

### Full-text search

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/search?q=voice+communications&maxResults=20"
```

### Semantic search (requires embeddings enabled)

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/search/semantic?q=secure+communications&maxResults=20"
```

### Hybrid search (Reciprocal Rank Fusion)

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/search/hybrid?q=voice+communications&maxResults=20"
```

### Graph-semantic search

Graph mode checks the selected repository, scope and exact branch head on each request. Relation similarity is available only for the migration-compatible central default branch while the canonical relation reader permits its legacy fallback. A proven empty branch projection returns global node matches with empty relation statistics. Nonempty branch projections and unready selected scopes return HTTP 503; they do not return partial graph results. The `graphReady` status reports global model/index prerequisites, so a visible Graph option does not guarantee that the selected scope can be searched. Branch semantic indexing remains follow-up work (#741).

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/search/graph?q=communications&maxResults=20"
```

### Find similar nodes

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/search/similar/CR-1047?topK=5"
```

---

## Graph Exploration

Every `/api/graph/*` request requires an authorized `repositoryId`, `branch`, full 40-character `commitId`, and explicit `workspaceId`. An empty workspace selects the central scope. Set `TAXONOMY_REPOSITORY_ID` and `TAXONOMY_MODEL_COMMIT` from the saved model selected in the editor/lifecycle API; the examples use its `main` branch.

Responses identify the exact revision, source authority, traversal policy, completeness and effective query bounds in `evidence`. A missing path is bounded to that selection. Stale or unready projections fail instead of producing a successful empty answer. See [graph query evidence](../architecture/graph-query-evidence.md).

### Upstream neighbours

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/graph/node/CR-1047/upstream?repositoryId=${TAXONOMY_REPOSITORY_ID}&workspaceId=&branch=main&commitId=${TAXONOMY_MODEL_COMMIT}&maxHops=2"
```

**Response (200):**

The response contains `originNodeCode`, `direction`, `maxHops`, `neighbors`, `traversedRelationships`, counts, notes and `evidence`. Each returned node/edge retains its source evidence. APQC hierarchy returns `{nodes, evidence}` using the same selection. Enriched failure impact can return a null `riskScore` when requirement-correlation evidence is unavailable; this does not mean zero risk.

### Downstream neighbours

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/graph/node/CR-1047/downstream?repositoryId=${TAXONOMY_REPOSITORY_ID}&workspaceId=&branch=main&commitId=${TAXONOMY_MODEL_COMMIT}&maxHops=2"
```

### Failure impact analysis

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/graph/node/CR-1047/failure-impact?repositoryId=${TAXONOMY_REPOSITORY_ID}&workspaceId=&branch=main&commitId=${TAXONOMY_MODEL_COMMIT}&maxHops=3"
```

### Requirement impact analysis

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST "http://localhost:8080/api/graph/impact?repositoryId=${TAXONOMY_REPOSITORY_ID}&workspaceId=&branch=main&commitId=${TAXONOMY_MODEL_COMMIT}" \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CR-1047": 87, "CP-1023": 92}, "businessText": "Secure voice", "maxHops": 2}'
```

---

## Relations

### List all relations

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/relations"
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/relations?type=REALIZES"
```

### Get relations for a node

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/node/CP-1023/relations"
```

### Create a relation (requires ARCHITECT role)

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/relations \
  -H "Content-Type: application/json" \
  -d '{"sourceCode": "CP-1023", "targetCode": "CR-1047", "relationType": "REALIZES"}'
```

### Delete a relation (requires ARCHITECT role)

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X DELETE http://localhost:8080/api/relations/42
```

---

## Relation Proposals

### Generate AI proposals

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/proposals/propose \
  -H "Content-Type: application/json" \
  -d '{"sourceCode": "CR-1047", "relationType": "SUPPORTS"}'
```

### List pending proposals

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/proposals/pending"
```

### Accept / reject / revert

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/proposals/42/accept
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/proposals/42/reject
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/proposals/42/revert
```

### Bulk action

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/proposals/bulk \
  -H "Content-Type: application/json" \
  -d '{"ids": [42, 43, 44], "action": "ACCEPT"}'
```

---

## Export

### ArchiMate XML

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/diagram/archimate \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92, "CO-1011": 88}}' \
  -o architecture.xml
```

### Visio (.vsdx)

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/diagram/visio \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92, "CO-1011": 88}}' \
  -o architecture.vsdx
```

### Mermaid

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/diagram/mermaid \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92, "CO-1011": 88}}'
```

### JSON (save/load analysis)

```bash
# Export
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/scores/export \
  -H "Content-Type: application/json" \
  -d '{"requirement": "Secure voice comms", "scores": {"CP-1023": 92}}'

# Import
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/scores/import \
  -H "Content-Type: application/json" \
  -d @saved-analysis.json
```

#### Version 2 Format (with provenance)

Starting with version 2, the export JSON can optionally include source
provenance data:

```json
{
  "version": 2,
  "requirement": "Digital application processing",
  "timestamp": "2026-03-19T10:00:00Z",
  "provider": "GEMINI",
  "scores": { "CP-1023": 92 },
  "reasons": { "CP-1023": "Direct capability match" },
  "sources": [
    {
      "sourceType": "REGULATION",
      "title": "VV Digitale Anträge"
    }
  ],
  "requirementSourceLinks": [
    {
      "requirementId": "REQ-001",
      "linkType": "EXTRACTED_FROM",
      "confidence": 0.91
    }
  ]
}
```

Both version 1 (without provenance) and version 2 JSON files are accepted by
the import endpoint.

---

## Reports

> 💡 **GUI:** Click the export buttons (**📄 Markdown**, **🌐 HTML**, **📋 DOCX**) in the Architecture View.

Export analysis results as formatted reports:

```bash
# Markdown
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/report/markdown \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92}, "businessText": "Secure voice comms", "minScore": 50}'

# HTML
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/report/html ...

# DOCX
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/report/docx ... -o report.docx

# JSON
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/report/json ...
```

---

## Gap Analysis & Recommendations

### Gap analysis

Discover profiles with `GET /api/gap/profiles` and send the chosen ID and exact version. `compatibility` imposes no business obligations; `service-support` is an opt-in authored business assumption. Keep `evidenceCompleteness` as `INCOMPLETE` unless all accepted evidence required by the chosen profile is present in the selected model.

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST "http://localhost:8080/api/gap/analyze?repositoryId=${TAXONOMY_REPOSITORY_ID}&workspaceId=&branch=main&commitId=${TAXONOMY_MODEL_COMMIT}" \
  -H "Content-Type: application/json" \
  -d '{"profileId":"service-support","profileVersion":"1.0.0","evidenceCompleteness":"INCOMPLETE","businessText":"Secure voice"}'
```

**Response:** Profile/model provenance and `judgments` with `SATISFIED`, `VIOLATED` or `INCONCLUSIVE`. `totalGaps` counts only violated obligations. Zero violations or an empty profile do not establish architectural completeness. See [obligation profiles](../architecture/obligation-profiles.md).

### Recommendations

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST "http://localhost:8080/api/recommend?repositoryId=${TAXONOMY_REPOSITORY_ID}&workspaceId=&branch=main&commitId=${TAXONOMY_MODEL_COMMIT}" \
  -H "Content-Type: application/json" \
  -d '{"profileId":"service-support","profileVersion":"1.0.0","evidenceCompleteness":"INCOMPLETE","scores":{"CO-1056":88,"CR-1047":81},"businessText":"Satellite communications"}'
```

**Response:** Separates `matchedElements` (ranked matches), `acceptedElements` (selected canonical members), `proposedElements` and `suggestedRelations`. Nullable `meanRelevance` is a ranking statistic, not architectural confidence. Suggestions from violated obligations prefer eligible existing model elements.

### Pattern detection

Select the exact saved model as for gap analysis. Detection uses its declared
element types and accepted relationships; scores only select starting nodes.

```bash
# For a single node
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/patterns/detect?nodeCode=CP-1023&repositoryId=${TAXONOMY_REPOSITORY_ID}&workspaceId=&branch=main&commitId=${TAXONOMY_MODEL_COMMIT}"

# For scored nodes
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST "http://localhost:8080/api/patterns/detect?repositoryId=${TAXONOMY_REPOSITORY_ID}&workspaceId=&branch=main&commitId=${TAXONOMY_MODEL_COMMIT}" \
  -H "Content-Type: application/json" \
  -d '{"scores": {"CP-1023": 92, "CO-1011": 88}, "minScore": 50}'
```

**Response:** `evidence` binds the exact model, template version, score threshold
and bounded completeness. `patternCoverage` is null when no applicable template
was evaluated. A missing pattern is not a business obligation. See
[selected-model evidence](../architecture/graph-query-evidence.md#pattern-detection-uses-the-same-source-boundary).

---

## Architecture DSL

### Export current architecture as DSL text

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/dsl/export"
```

### Commit DSL text (requires ARCHITECT role)

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST "http://localhost:8080/api/dsl/commit?branch=draft&message=initial+architecture" \
  -H "Content-Type: text/plain" \
  -d 'meta { language: "taxdsl"; version: "2.0"; namespace: "example"; }
element CP-1023 type Capability { title: "CIS Capabilities"; }
relation CR-1011 SUPPORTS BP-1327 { status: proposed; }'
```

### View commit history

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/dsl/history?branch=draft"
```

### Diff between commits

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/dsl/diff/semantic/{beforeId}/{afterId}"
```

### Branch and merge

```bash
# Create branch
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST "http://localhost:8080/api/dsl/branches?name=review&fromBranch=draft"

# Merge
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST "http://localhost:8080/api/dsl/merge?fromBranch=review&intoBranch=accepted"
```

---

## Document Import & Provenance

Upload PDF/DOCX documents and extract requirements with source provenance tracking.
For the full feature description, see [Document Import](DOCUMENT_IMPORT.md).

### Upload and parse a document

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/documents/upload \
  -F "file=@regulation.pdf"
```

**Response (200):**

```json
{
  "title": "regulation.pdf",
  "format": "PDF",
  "pageCount": 12,
  "sections": [
    { "heading": "§1 Scope", "content": "This regulation applies to..." },
    { "heading": "§2 Requirements", "content": "The system shall..." }
  ],
  "candidates": [
    { "text": "The system shall provide secure authentication", "section": "§2 Requirements" }
  ]
}
```

Maximum upload size: 50 MB. Supported formats: PDF, DOCX.

### AI-assisted requirement extraction

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/documents/extract-ai \
  -H "Content-Type: application/json" \
  -d '{"documentText": "The system shall provide...", "title": "regulation.pdf"}'
```

Uses the configured LLM to identify and structure requirements from document text.

### Direct regulation-to-architecture mapping

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/documents/map-regulation \
  -H "Content-Type: application/json" \
  -d '{"documentText": "The system shall provide...", "title": "VV Digitale Anträge"}'
```

Maps regulation content directly to taxonomy nodes via AI analysis.

### Confirm extracted candidates

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" -X POST http://localhost:8080/api/documents/confirm-candidates \
  -H "Content-Type: application/json" \
  -d '{"sourceTitle": "regulation.pdf", "sourceType": "REGULATION", "candidates": [{"text": "Secure authentication", "section": "§2"}]}'
```

Links selected requirement candidates to the source provenance model.

### List source artifacts

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/provenance/sources"
```

Returns all registered source artifacts (uploaded documents, regulations, etc.).

### Get provenance links for a requirement

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/provenance/links/REQ-001"
```

Returns all provenance links connecting a requirement to its source artifacts.

---

## Administration

### System status

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/ai-status"
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/status/startup"
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/embedding/status"
```

### LLM diagnostics (ADMIN only)

```bash
curl -u "admin:${TAXONOMY_ADMIN_PASSWORD}" "http://localhost:8080/api/diagnostics"
```

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
| `429` | Too Many Requests | Incoming LLM quota for the stable authenticated identity exceeded; retry after the `Retry-After` interval |
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

## Workspace Management

All workspace endpoints require authentication. An automatic default workspace is created on first access. For API calls from a tab selecting one of several workspaces, send `X-Taxonomy-Workspace-Id: {workspaceId}` on each request; an unpinned request follows the user's active/default workspace and can be affected by another session. SSE transports that cannot set headers may use `?workspaceId={workspaceId}`. An explicitly empty workspace header selects a read-only central context; workspace-bound writes are rejected, and `GET /api/workspace/current` returns 204 in that context.

### Get Current Workspace

```bash
curl -u alice:password http://localhost:8080/api/workspace/current
```

**Response:**
```json
{
  "workspaceId": "a1b2c3d4-...",
  "username": "alice",
  "displayName": "alice's workspace",
  "currentBranch": "draft",
  "baseBranch": "draft",
  "shared": false,
  "currentContext": { "branch": "draft", "mode": "EDITABLE" },
  "createdAt": "2026-03-15T10:00:00Z",
  "lastAccessedAt": "2026-03-15T12:00:00Z"
}
```

### List Active Workspaces (Admin)

```bash
curl -u admin:password http://localhost:8080/api/workspace/active
```

### Workspace Statistics

```bash
curl -u admin:password http://localhost:8080/api/workspace/stats
```

**Response:** `{ "activeWorkspaces": 3 }`

### Evict Workspace (Admin)

```bash
curl -u admin:password -X POST "http://localhost:8080/api/workspace/evict?username=alice"
```

### Compare Branches

```bash
curl -u alice:password -X POST "http://localhost:8080/api/workspace/compare?leftBranch=draft&rightBranch=feature-x"
```

**Response:**
```json
{
  "left": { "branch": "draft", "mode": "EDITABLE" },
  "right": { "branch": "feature-x", "mode": "READ_ONLY" },
  "summary": {
    "elementsAdded": 2,
    "elementsChanged": 1,
    "elementsRemoved": 0,
    "relationsAdded": 1,
    "relationsChanged": 0,
    "relationsRemoved": 0
  },
  "changes": [
    {
      "changeType": "ADD",
      "objectType": "ELEMENT",
      "objectId": "CP-1023",
      "description": "Element CP-1023 added (Capability)",
      "before": null,
      "after": "Secure Voice Service"
    }
  ],
  "rawDslDiff": "..."
}
```

### Sync from Shared Repository

```bash
curl -u alice:password -X POST "http://localhost:8080/api/workspace/sync-from-shared?userBranch=feature-x"
```

**Response:** `{ "success": true, "branch": "feature-x", "mergeCommit": "abc1234...", "syncedAt": "2026-03-15T10:00:00Z" }`

### Publish to Shared Repository

```bash
curl -u alice:password -X POST "http://localhost:8080/api/workspace/publish?userBranch=feature-x"
```

**Response:** `{ "success": true, "branch": "feature-x", "mergeCommit": "def5678...", "publishedAt": "2026-03-15T10:05:00Z" }`

### Get Sync State

```bash
curl -u alice:password http://localhost:8080/api/workspace/sync-state
```

While the selected working copy is being prepared, the same read returns HTTP 200 with its repository, workspace and branch plus the current lifecycle in `syncStatus` (`NOT_PROVISIONED`, `PROVISIONING` or `FAILED`). Git observations and their timestamps stay empty. Opening the status view does not start provisioning.

**Response:**
```json
{
  "repositoryId": "architecture",
  "workspaceId": "alice-workspace",
  "branch": "feature-x",
  "observedAt": "2026-03-15T10:03:00Z",
  "syncStatus": "AHEAD",
  "unpublishedCommitCount": 3,
  "lastSyncTimestamp": "2026-03-15T10:00:00Z",
  "lastPublishTimestamp": null
}
```

The response identifies the selected repository, working copy and branch for the authenticated actor. A central branch has a null `workspaceId`. `observedAt` records the most recent stored observation. `lastSyncTimestamp` and `lastPublishTimestamp` record the corresponding operations. Older rows without a recorded branch remain available as historical database evidence.

### Get Navigation History

```bash
curl -u alice:password http://localhost:8080/api/workspace/history
```

### Get Local Changes

```bash
curl -u alice:password "http://localhost:8080/api/workspace/local-changes?branch=feature-x"
```

**Response:** `{ "branch": "feature-x", "changeCount": 3, "hasUnpublishedChanges": true }`

### Check Dirty State

```bash
curl -u alice:password http://localhost:8080/api/workspace/dirty
```

**Response:** `{ "username": "alice", "dirty": true, "syncStatus": "AHEAD" }`

### Get Projection State

```bash
curl -u alice:password http://localhost:8080/api/workspace/projection
```

**Response:**
```json
{
  "username": "alice",
  "lastProjectionCommit": "abc1234",
  "lastProjectionBranch": "draft",
  "lastProjectionTimestamp": "2026-03-15T10:00:00Z",
  "lastIndexCommit": "abc1234",
  "lastIndexTimestamp": "2026-03-15T10:00:00Z",
  "persistedProjectionCommit": "abc1234",
  "persistedProjectionBranch": "draft",
  "persistedProjectionTimestamp": "2026-03-15T10:00:00Z",
  "persistedIndexCommit": "abc1234",
  "persistedIndexTimestamp": "2026-03-15T10:00:00Z",
  "stale": false
}
```

### Workspace Provisioning & Topology

**Get provisioning status:**
```bash
curl -u alice:password http://localhost:8080/api/workspace/provisioning-status
```

**Response:**
```json
{
  "status": "READY",
  "topologyMode": "INTERNAL_SHARED",
  "sourceRepository": "uuid-of-system-repo",
  "error": null
}
```

**Provision default workspace (seed its isolated repository):**
```bash
curl -u alice:password -X POST http://localhost:8080/api/workspace/provision
```

**Response:**
```json
{
  "status": "READY",
  "branch": "draft",
  "baseBranch": "draft"
}
```

The automatic default workspace starts on `draft` in its own `ws-{workspaceId}` repository. A separately created workspace normally starts on `main`. The `alice/workspace/{workspaceId}` branch form applies only to legacy shared-repository storage.

**Get repository topology:**
```bash
curl -u alice:password http://localhost:8080/api/workspace/topology
```

**Response:**
```json
{
  "mode": "INTERNAL_SHARED",
  "sharedBranch": "draft",
  "systemRepositoryId": "uuid-of-system-repo",
  "displayName": "Shared Architecture Repository"
}
```

---

## Additional References

| Document | Contents |
|---|---|
| [Curl Examples for Automation](CURL_EXAMPLES.md) | Copy-paste curl commands for every endpoint |
| [Security](SECURITY.md) | Authentication, roles, deployment hardening |
| [Configuration](CONFIGURATION_REFERENCE.md) | Environment variables and settings |
| [Swagger UI](http://localhost:8080/swagger-ui.html) | Interactive API explorer (when running) |

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

All endpoints require **HTTP Basic authentication** unless listed as public.
CSRF is disabled for `/api/**` — REST clients do not need CSRF tokens.

> **⚠️ Security:** The examples below use `-u admin:admin` (the default development password).
> For any non-local deployment, set `TAXONOMY_ADMIN_PASSWORD` before exposing the application.
> See [Security](SECURITY.md) for details.

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
- [Document Import & Provenance](#document-import--provenance)
- [Administration](#administration)
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
| `maxArchitectureNodes` | integer | — | Max nodes in architecture view |

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

---

## Document Import & Provenance

Upload PDF/DOCX documents and extract requirements with source provenance tracking.
For the full feature description, see [Document Import](DOCUMENT_IMPORT.md).

### Upload and parse a document

```bash
curl -u admin:admin -X POST http://localhost:8080/api/documents/upload \
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
curl -u admin:admin -X POST http://localhost:8080/api/documents/extract-ai \
  -H "Content-Type: application/json" \
  -d '{"documentText": "The system shall provide...", "title": "regulation.pdf"}'
```

Uses the configured LLM to identify and structure requirements from document text.

### Direct regulation-to-architecture mapping

```bash
curl -u admin:admin -X POST http://localhost:8080/api/documents/map-regulation \
  -H "Content-Type: application/json" \
  -d '{"documentText": "The system shall provide...", "title": "VV Digitale Anträge"}'
```

Maps regulation content directly to taxonomy nodes via AI analysis.

### Confirm extracted candidates

```bash
curl -u admin:admin -X POST http://localhost:8080/api/documents/confirm-candidates \
  -H "Content-Type: application/json" \
  -d '{"sourceTitle": "regulation.pdf", "sourceType": "REGULATION", "candidates": [{"text": "Secure authentication", "section": "§2"}]}'
```

Links selected requirement candidates to the source provenance model.

### List source artifacts

```bash
curl -u admin:admin "http://localhost:8080/api/provenance/sources"
```

Returns all registered source artifacts (uploaded documents, regulations, etc.).

### Get provenance links for a requirement

```bash
curl -u admin:admin "http://localhost:8080/api/provenance/links/REQ-001"
```

Returns all provenance links connecting a requirement to its source artifacts.

---

## Administration

### System status

```bash
curl -u admin:admin "http://localhost:8080/api/ai-status"
curl -u admin:admin "http://localhost:8080/api/status/startup"
curl -u admin:admin "http://localhost:8080/api/embedding/status"
```

### LLM diagnostics (ADMIN only)

```bash
curl -u admin:admin "http://localhost:8080/api/diagnostics"
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

## Workspace Management

All workspace endpoints require authentication (HTTP Basic). Each user automatically gets a personal workspace on first access.

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

**Response:**
```json
{
  "syncStatus": "AHEAD",
  "unpublishedCommitCount": 3,
  "lastSyncTimestamp": "2026-03-15T10:00:00Z",
  "lastPublishTimestamp": null
}
```

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

**Provision workspace (create personal branch):**
```bash
curl -u alice:password -X POST http://localhost:8080/api/workspace/provision
```

**Response:**
```json
{
  "status": "READY",
  "branch": "alice/workspace",
  "baseBranch": "draft"
}
```

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
| [Curl Examples](CURL_EXAMPLES.md) | Copy-paste curl commands for every endpoint |
| [Security](SECURITY.md) | Authentication, roles, deployment hardening |
| [Configuration](CONFIGURATION_REFERENCE.md) | Environment variables and settings |
| [Swagger UI](http://localhost:8080/swagger-ui.html) | Interactive API explorer (when running) |

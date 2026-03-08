> **Note:** This document is the REST API reference. For the end-user guide, see [User Guide](USER_GUIDE.md).

---

# NATO Taxonomy Analyser — API Reference

## Table of Contents

1. [Overview](#1-overview)
2. [System Architecture](#2-system-architecture)
3. [Getting Started](#3-getting-started)
4. [Requirement Analysis](#4-requirement-analysis)
5. [Leaf Justification](#5-leaf-justification)
6. [Architecture View](#6-architecture-view)
7. [Search](#7-search)
8. [Graph Explorer](#8-graph-explorer)
9. [Relation Proposals](#9-relation-proposals)
10. [Architecture Knowledge Base](#10-architecture-knowledge-base)
11. [Relation Quality Dashboard](#11-relation-quality-dashboard)
12. [Diagram Export](#12-diagram-export)
13. [Administration](#13-administration)
14. [Embedding Configuration](#14-embedding-configuration)
15. [API Reference](#15-api-reference)
16. [Error Response Schema](#16-error-response-schema)
17. [OpenAPI / Swagger UI](#17-openapi--swagger-ui)
18. [Best Practices](#18-best-practices)
19. [Glossary](#19-glossary)

---

## 1. Overview

The **NATO Taxonomy Analyser** is a Spring Boot web application that maps free-text business or mission requirements to a structured C3 Taxonomy catalogue.  It is aimed at:

- **Architects and capability planners** who need to align requirements to NATO/TOGAF reference architecture elements.
- **System engineers** looking for existing services, capabilities, or information products that satisfy a requirement.
- **Documentation teams** maintaining an architecture knowledge base of confirmed element relationships.

Key capabilities:

| Capability | Summary |
|---|---|
| Requirement analysis | Score every taxonomy node against free-text using an LLM |
| Streaming analysis | Real-time Server-Sent Event (SSE) feed of scoring progress |
| Interactive / node-level analysis | Expand the taxonomy level by level |
| Leaf justification | Obtain a natural-language explanation for a high-scoring leaf node |
| Full-text search | Lucene-powered keyword search across the taxonomy |
| Semantic search | Embedding (KNN) similarity search |
| Hybrid search | Reciprocal Rank Fusion of full-text + semantic results |
| Graph search | Graph-semantic traversal combining embeddings with relation edges |
| Graph Explorer | Upstream / downstream / failure-impact neighbourhood queries |
| Relation Proposals | AI-assisted proposal pipeline with human review |
| Quality Dashboard | Acceptance-rate metrics by relation type and provenance |
| Diagram Export | Visio (.vsdx) and ArchiMate 3.x XML |
| Analysis scores JSON | Export and import analysis results as JSON for reproducibility and sharing |
| Admin Panel | Password protection, LLM diagnostics, prompt template editor |

---

## 2. System Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     Browser / REST client                    │
└─────────────────────────────┬────────────────────────────────┘
                              │ HTTP / SSE
                              ▼
┌──────────────────────────────────────────────────────────────┐
│              Spring Boot Application (port 8080)             │
│                                                              │
│  ┌─────────────┐  ┌──────────────────┐  ┌────────────────┐  │
│  │ ApiController│  │GraphQueryApi     │  │ProposalApi     │  │
│  │             │  │Controller        │  │Controller      │  │
│  └──────┬──────┘  └───────┬──────────┘  └───────┬────────┘  │
│         │                 │                      │           │
│  ┌──────▼─────────────────▼──────────────────────▼────────┐  │
│  │            Service Layer (32 services)                  │  │
│  │  AnalysisService · RequirementArchitectureViewService   │  │
│  │  HybridSearchService · GraphSearchService               │  │
│  │  ArchitectureGraphQueryService                          │  │
│  │  RelationProposalService · RelationQualityService       │  │
│  │  VisioDiagramService · ArchiMateExportService           │  │
│  └──────────────┬──────────────────────────────────────────┘  │
│                 │                                              │
│  ┌──────────────▼──────────────────────────────────────────┐  │
│  │         Persistence (H2/Postgres, Hibernate Search)     │  │
│  │  TaxonomyNode · TaxonomyRelation · RelationProposal     │  │
│  └──────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
         │                          │
         ▼                          ▼
  LLM Provider              Local ONNX embedding
  (Gemini / OpenAI /        (DJL all-MiniLM-L6-v2)
   DeepSeek / Qwen /
   Llama / Mistral)
```

---

## 3. Getting Started

### Prerequisites

| Requirement | Notes |
|---|---|
| Java 21+ | Runtime |
| Maven 3.9+ | Build only |
| LLM API key **or** `LLM_PROVIDER=LOCAL_ONNX` | Required for analysis; optional for browse/search |
| Embedding enabled (optional) | Enables semantic and hybrid search |

### Running Locally

```bash
# With Gemini (default)
GEMINI_API_KEY=your_key mvn spring-boot:run

# With OpenAI
LLM_PROVIDER=OPENAI OPENAI_API_KEY=your_key mvn spring-boot:run

# Fully local (no API key)
LLM_PROVIDER=LOCAL_ONNX mvn spring-boot:run
```

The application starts on `http://localhost:8080`.

### Docker

```bash
docker build -t taxonomy-analyser .
docker run -p 8080:8080 -e GEMINI_API_KEY=your_key taxonomy-analyser
```

### Render.com

A `render.yaml` is included in the repository root for one-click deployment on Render.com.

---

## 4. Requirement Analysis

### 4.1 Standard Analysis

**Endpoint:** `POST /api/analyze`

Scores every taxonomy node against the submitted business text and optionally builds an architecture view.

**Request parameters (form or JSON):**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `businessText` | string | Yes | Free-text requirement |
| `includeArchitectureView` | boolean | No | When `true`, also returns an architecture view (see §6) |

**Response:** JSON object containing a `scores` map (`nodeCode → score 0–100`) and an optional `architectureView`.

**Example:**

```http
POST /api/analyze
Content-Type: application/x-www-form-urlencoded

businessText=Provide+secure+voice+communications+between+HQ+and+deployed+forces&includeArchitectureView=true
```

### 4.2 Streaming Analysis (SSE)

**Endpoint:** `GET /api/analyze-stream?businessText=...`

Returns a stream of Server-Sent Events so the UI can show live progress.

**SSE event types:**

| Event | Payload | Description |
|---|---|---|
| `phase` | `{ phase: "..." }` | Current scoring phase name |
| `scores` | `{ nodeCode: score, ... }` | Partial or final score batch |
| `expanding` | `{ nodeCode: "..." }` | Node being expanded |
| `complete` | `{ summary: "..." }` | Analysis finished |
| `error` | `{ message: "..." }` | LLM or system error |

**Example (curl):**

```bash
curl -N "http://localhost:8080/api/analyze-stream?businessText=voice+comms+for+deployed+forces"
```

### 4.3 Interactive Mode / Analyze Node

**Endpoint:** `GET /api/analyze-node?parentCode=...&businessText=...`

Scores the immediate children of `parentCode` against `businessText`.  Allows progressive, level-by-level taxonomy exploration without scoring the whole tree at once.

| Parameter | Description |
|---|---|
| `parentCode` | Code of the parent taxonomy node to expand |
| `businessText` | Requirement text |

**Example:**

```bash
curl "http://localhost:8080/api/analyze-node?parentCode=C3_ROOT&businessText=voice+comms"
```

---

## 5. Leaf Justification

**Endpoint:** `POST /api/justify-leaf`

After analysis, request a natural-language explanation for why a specific leaf node scored highly.

**Request body (JSON):**

```json
{
  "nodeCode":    "SVC_VOICE_001",
  "businessText": "Secure voice communications between HQ and deployed forces",
  "scores":      { "SVC_VOICE_001": 87 },
  "reasons":     { "SVC_VOICE_001": "Matched on voice, communications, deployed" }
}
```

**Response:** Plain text or JSON string containing the LLM-generated justification.

---

## 6. Architecture View

When `POST /api/analyze` is called with `includeArchitectureView=true`, the response includes an `architectureView` object built by `RequirementArchitectureViewService`.

### Contents

| Field | Description |
|---|---|
| `anchors` | High-scoring leaf nodes that directly satisfy the requirement |
| `elements` | All taxonomy nodes reachable from anchors via confirmed relations |
| `relationships` | Directed edges (TaxonomyRelation) connecting elements |

### How It Is Built

1. **Anchor selection** — nodes whose score exceeds the configured threshold become anchors.
2. **Relevance propagation** — `RelevancePropagationService` propagates scores upward through `TaxonomyRelation` edges.
3. **Relation traversal** — `RelationTraversalService` collects all reachable nodes within a configurable hop limit.

The architecture view is the input for diagram export (§12).

---

## 7. Search

All search endpoints accept a `maxResults` (or `topK`) query parameter.

### 7.1 Full-Text Search (Lucene)

```
GET /api/search?q=voice+communications&maxResults=50
```

Uses Hibernate Search / Lucene to match against node names, codes, and descriptions.

### 7.2 Semantic Search (Embeddings)

```
GET /api/search/semantic?q=voice+communications&maxResults=20
```

Converts the query to a vector embedding and performs a KNN similarity search across indexed nodes.  Requires embedding to be enabled (§14).

### 7.3 Hybrid Search (RRF)

```
GET /api/search/hybrid?q=voice+communications&maxResults=20
```

Combines full-text and semantic result lists using **Reciprocal Rank Fusion (RRF)**, which balances keyword precision with semantic recall.

### 7.4 Find Similar Nodes

```
GET /api/search/similar/{code}?topK=10
```

Returns the `topK` taxonomy nodes most similar to the node identified by `{code}`, based on embedding cosine similarity.

**Example:**

```
GET /api/search/similar/SVC_VOICE_001?topK=5
```

### 7.5 Graph-Semantic Search

```
GET /api/search/graph?q=voice+communications&maxResults=20
```

Searches both `TaxonomyNode` and `TaxonomyRelation` Hibernate Search indexes via KNN, then aggregates results by taxonomy root and relation type.

**Response fields:**

| Field | Description |
|---|---|
| `matchedNodes` | List of matching nodes with scores |
| `relationCountByRoot` | Count of matched relations per taxonomy root |
| `topRelationTypes` | Most common relation types in the result set |
| `summary` | Human-readable summary string |

---

## 8. Graph Explorer

The Graph Explorer exposes neighbourhood queries on the confirmed `TaxonomyRelation` graph.  All endpoints are under `/api/graph`.

### 8.1 Requirement Impact Analysis

```
POST /api/graph/impact
Content-Type: application/json

{
  "scores":       { "SVC_VOICE_001": 87, "CAP_C2_003": 72 },
  "businessText": "Secure voice communications between HQ and deployed forces",
  "maxHops":      2
}
```

Returns which architecture elements are transitively affected by the requirement, ranked by impact score.

### 8.2 Upstream Neighbourhood

```
GET /api/graph/node/{code}/upstream?maxHops=2
```

Returns the nodes that **feed into** the given element (i.e., the nodes that the element depends on or is realised by).

**Example:**

```
GET /api/graph/node/SVC_VOICE_001/upstream?maxHops=2
```

### 8.3 Downstream Neighbourhood

```
GET /api/graph/node/{code}/downstream?maxHops=2
```

Returns the nodes that **depend on** the given element.

### 8.4 Failure Impact

```
GET /api/graph/node/{code}/failure-impact?maxHops=3
```

Returns the nodes that would be disrupted if the given element failed or was changed.  Useful for change-impact and risk analysis.

---

## 9. Relation Proposals

The Relation Proposal pipeline suggests new edges to add to the architecture knowledge base and routes them through human review.

### 9.1 Triggering a Proposal

```
POST /api/proposals/propose
Content-Type: application/x-www-form-urlencoded

sourceCode=CAP_C2_003&relationType=REALIZES
```

The pipeline executes:
1. **`RelationCandidateService`** — finds candidate target nodes.
2. **`RelationValidationService`** — checks structural and semantic validity.
3. **`RelationCompatibilityMatrix`** — verifies the relation type is architecturally valid between the source and candidate categories.
4. **`RelationProposalService`** — persists `RelationProposal` records with status `PENDING`.

### 9.2 Proposal Status

| Status | Meaning |
|---|---|
| `PENDING` | Awaiting human review |
| `ACCEPTED` | Approved; a `TaxonomyRelation` has been created |
| `REJECTED` | Declined by the reviewer |

> **Note:** The valid status values are `PENDING`, `ACCEPTED`, and `REJECTED`.

### 9.3 Listing and Reviewing Proposals

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/proposals` | All proposals |
| `GET` | `/api/proposals/pending` | Pending proposals (review queue) |
| `GET` | `/api/node/{code}/proposals` | Proposals for a specific node |
| `POST` | `/api/proposals/{id}/accept` | Accept a proposal |
| `POST` | `/api/proposals/{id}/reject` | Reject a proposal |

### 9.4 Relation Types

The system defines **10 relation types**, each tied to a specific NAF or TOGAF architectural viewpoint:

| Relation Type | Direction | Standard |
|---|---|---|
| `REALIZES` | Capability → Service | NAF NCV-2, TOGAF SBB |
| `SUPPORTS` | Service → Business Process | TOGAF Business Architecture |
| `CONSUMES` | Business Process → Information Product | TOGAF Data Architecture |
| `USES` | User Application → Core Service | NAF NSV-1 |
| `FULFILLS` | COI Service → Capability | NAF NCV-5 |
| `ASSIGNED_TO` | Business Role → Business Process | TOGAF Org mapping |
| `DEPENDS_ON` | Service → Service | Technical dependency |
| `PRODUCES` | Business Process → Information Product | Data flow |
| `COMMUNICATES_WITH` | Communications Service → Core Service | NAF NSOV |
| `RELATED_TO` | Any → Any | Generic fallback |

---

## 10. Architecture Knowledge Base

Confirmed architecture relationships are stored as `TaxonomyRelation` entities.

### TaxonomyRelation Fields

| Field | Type | Description |
|---|---|---|
| `id` | Long | Primary key |
| `sourceCode` | String | Code of the source taxonomy node |
| `targetCode` | String | Code of the target taxonomy node |
| `relationType` | RelationType | One of the 10 types listed in §9.4 |
| `provenance` | String | Origin: `MANUAL`, `ACCEPTED_PROPOSAL`, etc. |
| `confidence` | Double | Confidence score 0.0–1.0 (from proposal pipeline) |

### REST Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/relations` | All relations (optional `?type=REALIZES` filter) |
| `GET` | `/api/node/{code}/relations` | Relations for a specific node |
| `POST` | `/api/relations` | Create a relation manually |
| `DELETE` | `/api/relations/{id}` | Delete a relation |
| `GET` | `/api/relations/count` | Total relation count |

---

## 11. Relation Quality Dashboard

`RelationQualityService` exposes metrics about the health of the proposal pipeline.  Accessed via `QualityApiController`.

### Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/relations/metrics` | Full quality dashboard |
| `GET` | `/api/relations/metrics/by-type` | Breakdown by relation type |
| `GET` | `/api/relations/metrics/by-provenance` | Breakdown by provenance |
| `GET` | `/api/relations/metrics/top-rejected?limit=10` | Top rejected proposals |

### Overall Metrics (`GET /api/relations/metrics`)

| Field | Description |
|---|---|
| `total` | Total proposals created |
| `accepted` | Number accepted |
| `rejected` | Number rejected |
| `pending` | Number awaiting review |
| `acceptanceRate` | `accepted / total` as a fraction |
| `avgConfidence` | Average confidence score of all proposals |

### By-Type Metrics

Returns a list where each entry contains:
- `relationType` — e.g., `REALIZES`
- `proposed`, `accepted`, `rejected` counts
- `acceptanceRate`

### Top Rejected

Returns the top `limit` rejected proposals ordered by confidence descending — these are the most confident predictions that were still rejected, useful for tuning the pipeline.

| Field | Description |
|---|---|
| `sourceCode` / `sourceName` | Source node |
| `targetCode` / `targetName` | Target node |
| `relationType` | Proposed relation type |
| `confidence` | Confidence score |
| `rationale` | LLM-generated rationale |

---

## 12. Diagram Export

The system supports two export formats.  Both endpoints expect the `architectureView` JSON produced by `POST /api/analyze` (with `includeArchitectureView=true`).

### 12.1 Visio Export

**Endpoint:** `POST /api/diagram/visio`

Generates a `.vsdx` file (Office Open XML package) containing a structured architecture diagram.

**Pipeline:** `RequirementArchitectureView` → `DiagramProjectionService` → `VisioDiagramService` → `VisioPackageBuilder` → `.vsdx`

**Response:** Binary `.vsdx` file download.

### 12.2 ArchiMate 3.x XML Export

**Endpoint:** `POST /api/diagram/archimate`

Generates an ArchiMate 3.x compliant XML file suitable for import into tools such as Archi or Sparx EA.

**Response:** XML file download.

### 12.3 Analysis Scores JSON Export

**Endpoint:** `POST /api/scores/export`

Serialises the current analysis result as a `SavedAnalysis` JSON object, adding a timestamp and format version. The frontend downloads this as a `.json` file.

**Request body:**

```json
{
  "requirement": "Provide secure voice communications between HQ and deployed forces",
  "scores": { "CO": 90, "CR": 70, "BP": 25, "BR": 0 },
  "reasons": { "CO": "Voice comms are directly in scope", "CR": "Core transport services required" },
  "provider": "GEMINI"
}
```

**Response:** `SavedAnalysis` JSON:

```json
{
  "version": 1,
  "requirement": "Provide secure voice communications between HQ and deployed forces",
  "timestamp": "2026-03-08T14:30:00Z",
  "provider": "GEMINI",
  "scores": { "CO": 90, "CR": 70, "BP": 25, "BR": 0 },
  "reasons": { "CO": "Voice comms are directly in scope", "CR": "Core transport services required" }
}
```

**Semantic note:** Each root taxonomy is scored **independently** on a 0–100 scale — for example `"CO": 90` means "the Communications Services taxonomy covers 90% of this requirement". Scores across root taxonomies do **not** sum to 100. A score of `0` means the node was _evaluated and found not relevant_. An absent key means the node was _not evaluated_.

### 12.4 Analysis Scores JSON Import

**Endpoint:** `POST /api/scores/import`

Accepts a `SavedAnalysis` JSON body, validates it, and returns the parsed scores and reasons so the frontend can apply them to the tree.

**Request body:** Full `SavedAnalysis` JSON (as produced by `/api/scores/export`).

**Response:**

```json
{
  "requirement": "...",
  "scores": { "CO": 90, "CR": 70, "BP": 25, "BR": 0 },
  "reasons": { "CO": "Voice comms are directly in scope" },
  "provider": "GEMINI",
  "warnings": ["Unknown node code: XYZ"]
}
```

Warnings are generated for node codes not found in the current taxonomy, but the import succeeds unless the JSON is structurally invalid (bad version, blank requirement, empty scores).

### UI Usage

Export buttons appear in the results panel only when analysis scores are present.  Click **📥 JSON** to download a `SavedAnalysis` JSON file.  Click **📤 Load Scores** (always visible in the toolbar) to upload a previously saved JSON file and restore the analysis results.

---

## 13. Administration

### 13.1 Admin Authentication

If `ADMIN_PASSWORD` is set, the admin panel is protected.

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/admin/status` | Returns `{ passwordRequired: true/false }` |
| `POST` | `/api/admin/verify` | Verifies the password; returns a token on success |

Once authenticated, pass the token in the `X-Admin-Token` header for protected endpoints.

### 13.2 LLM Diagnostics

```
GET /api/diagnostics
X-Admin-Token: <token>
```

Returns statistics about LLM calls: total calls, error counts, average latency, provider name, model version.

### 13.3 AI Status

```
GET /api/ai-status
```

Returns `{ available: true/false, provider: "GEMINI" }` (no authentication required).

### 13.4 Prompt Template Editor

Prompt templates control what instructions are sent to the LLM.  Administrators can override individual templates without redeploying.

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/prompts` | List all templates (requires admin) |
| `GET` | `/api/prompts/{code}` | Get a single template |
| `PUT` | `/api/prompts/{code}` | Override a template |
| `DELETE` | `/api/prompts/{code}` | Reset to built-in default |

---

## 14. Embedding Configuration

Semantic, hybrid, and graph searches require the embedding subsystem to be enabled.

### Environment Variables

| Variable | Default | Description |
|---|---|---|
| `TAXONOMY_EMBEDDING_ENABLED` | `true` | Master switch for all embedding features |
| `TAXONOMY_EMBEDDING_MODEL_DIR` | *(empty)* | Path to a locally cached model directory.  If empty, DJL auto-downloads. |
| `TAXONOMY_EMBEDDING_MODEL_NAME` | `djl://ai.djl.huggingface.onnxruntime/all-MiniLM-L6-v2` | DJL model URI or HuggingFace model name |

### How It Works

- The system uses **DJL 0.31.1** with the ONNX Runtime engine.
- On first startup, the `all-MiniLM-L6-v2` model (~23 MB) is downloaded and cached in `~/.djl.ai/`.
- Embeddings are stored in a **Lucene `KnnFloatVectorField`** alongside each `TaxonomyNode`.
- Set `LLM_PROVIDER=LOCAL_ONNX` to use embedding-based scoring without an external API key.

### Embedding Status Endpoint

```
GET /api/embedding/status
```

**Response:**

```json
{
  "enabled":      true,
  "available":    true,
  "modelUrl":     "djl://ai.djl.huggingface.onnxruntime/all-MiniLM-L6-v2",
  "indexedNodes": 1247
}
```

---

## 15. API Reference

Complete list of all REST endpoints.

| Method | Path | Description | Auth Required |
|---|---|---|---|
| `GET` | `/api/taxonomy` | Full taxonomy tree | No |
| `GET` | `/api/ai-status` | LLM provider availability | No |
| `POST` | `/api/analyze` | Analyze requirement text | No |
| `GET` | `/api/analyze-stream` | SSE streaming analysis | No |
| `GET` | `/api/analyze-node` | Analyze single node | No |
| `POST` | `/api/justify-leaf` | Leaf node justification | No |
| `GET` | `/api/diagnostics` | LLM diagnostics | Yes (`X-Admin-Token`) |
| `GET` | `/api/admin/status` | Admin auth status | No |
| `POST` | `/api/admin/verify` | Verify admin password | No |
| `POST` | `/api/diagram/visio` | Export Visio .vsdx | No |
| `POST` | `/api/diagram/archimate` | Export ArchiMate XML | No |
| `POST` | `/api/scores/export` | Export analysis scores as JSON | No |
| `POST` | `/api/scores/import` | Import analysis scores from JSON | No |
| `GET` | `/api/search` | Full-text search | No |
| `GET` | `/api/search/semantic` | Semantic KNN search | No |
| `GET` | `/api/search/hybrid` | Hybrid RRF search | No |
| `GET` | `/api/search/similar/{code}` | Find similar nodes | No |
| `GET` | `/api/search/graph` | Graph-semantic search | No |
| `GET` | `/api/embedding/status` | Embedding status | No |
| `GET` | `/api/prompts` | List prompt templates | Yes |
| `GET` | `/api/prompts/{code}` | Get prompt template | Yes |
| `PUT` | `/api/prompts/{code}` | Override prompt template | Yes |
| `DELETE` | `/api/prompts/{code}` | Reset prompt template | Yes |
| `GET` | `/api/relations` | List relations | No |
| `GET` | `/api/node/{code}/relations` | Relations for a node | No |
| `POST` | `/api/relations` | Create relation | No |
| `DELETE` | `/api/relations/{id}` | Delete relation | No |
| `GET` | `/api/relations/count` | Relation count | No |
| `GET` | `/api/relations/metrics` | Quality metrics overview | No |
| `GET` | `/api/relations/metrics/by-type` | Metrics by relation type | No |
| `GET` | `/api/relations/metrics/by-provenance` | Metrics by provenance | No |
| `GET` | `/api/relations/metrics/top-rejected` | Top rejected proposals | No |
| `POST` | `/api/proposals/propose` | Trigger proposal generation | No |
| `GET` | `/api/proposals` | All proposals | No |
| `GET` | `/api/proposals/pending` | Pending proposals | No |
| `GET` | `/api/node/{code}/proposals` | Proposals for a node | No |
| `POST` | `/api/proposals/{id}/accept` | Accept proposal | No |
| `POST` | `/api/proposals/{id}/reject` | Reject proposal | No |
| `POST` | `/api/graph/impact` | Requirement impact analysis | No |
| `GET` | `/api/graph/node/{code}/upstream` | Upstream neighbourhood | No |
| `GET` | `/api/graph/node/{code}/downstream` | Downstream neighbourhood | No |
| `GET` | `/api/graph/node/{code}/failure-impact` | Failure impact | No |

---

## 16. Error Response Schema

The API uses standard HTTP status codes and returns structured error information.

### HTTP Status Codes

| Code | Meaning | When It Occurs |
|---|---|---|
| `200 OK` | Success | Normal successful response |
| `400 Bad Request` | Invalid input | Missing required parameters, invalid enum values, blank text fields |
| `401 Unauthorized` | Authentication required | Admin-only endpoints when `ADMIN_PASSWORD` is set and `X-Admin-Token` header is missing/invalid |
| `404 Not Found` | Resource not found | Invalid endpoint path |
| `500 Internal Server Error` | Server error | Unexpected exceptions (LLM timeout, I/O errors during export) |

### Error Response Formats

**400 Bad Request:** Returns an empty body with HTTP 400 status (no JSON body).

**401 Unauthorized:** Returns an empty body with HTTP 401 status.

**Analysis errors** (returned in the response body with HTTP 200):

The `status` field is `"SUCCESS"` when all roots are scored, or `"PARTIAL"` when one or
more roots failed or all scores are zero.  The value `"ERROR"` is **not** produced by the
current implementation — failures always yield `"PARTIAL"` with as many scores as could
be collected.

```json
{
  "status": "PARTIAL",
  "errorMessage": "LLM provider returned an error: Connection timed out",
  "scores": { "BP": 75 },
  "warnings": ["Root CS was skipped due to timeout"]
}
```

**Streaming analysis errors** (SSE `error` event):

The streaming endpoint emits an `error` event with `status: "PARTIAL"` and any scores
collected before the failure:

```json
{
  "status": "PARTIAL",
  "errorMessage": "Analysis failed: Rate limit exceeded (HTTP 429)",
  "partialScores": { "BP": 75, "CS": 60 },
  "warnings": ["Some roots may be incomplete"]
}
```

### LLM Error Handling

When the LLM provider experiences an error, the application handles it gracefully:

| Error Type | HTTP Code | User Message | Recommended Action |
|---|---|---|---|
| Connection timeout | 200 (partial) | "LLM connection timed out" | Retry the analysis |
| Rate limit (429) | 200 (partial) | "Rate limit exceeded" | Wait and retry |
| Invalid API key | 200 (error) | "Authentication failed" | Check API key configuration |
| Provider unavailable | 200 (error) | "LLM provider is not available" | Check `GET /api/ai-status` |

---

## 17. OpenAPI / Swagger UI

The application includes auto-generated interactive API documentation via
[springdoc-openapi](https://springdoc.org/).

| URL | Description |
|---|---|
| [`/swagger-ui.html`](/swagger-ui.html) | Interactive Swagger UI — browse and test all API endpoints |
| [`/v3/api-docs`](/v3/api-docs) | OpenAPI 3.0 specification in JSON format |
| [`/v3/api-docs.yaml`](/v3/api-docs.yaml) | OpenAPI 3.0 specification in YAML format |

All endpoints are tagged by functional area (Taxonomy, Analysis, Search, Relations,
Proposals, Graph Queries, Quality Metrics, Export, Administration, Embedding).

---

## 18. Best Practices

### Requirement Text Quality

- Write requirements as **imperative sentences**: *"Provide secure voice communications between HQ and deployed forces."*
- Include domain-specific terminology from the NATO/TOGAF vocabulary (capability, service, information product, etc.) to maximise scoring precision.
- Keep text under 500 words; longer texts do not improve accuracy and increase LLM latency.

### Search Strategy

- Start with **hybrid search** (`/api/search/hybrid`) for general exploration — it combines keyword recall with semantic precision.
- Use **full-text search** (`/api/search`) when you know exact taxonomy codes or names.
- Use **Find Similar** (`/api/search/similar/{code}`) when you have a good anchor node and want to discover related nodes.

### Proposal Pipeline

- Review proposals soon after they are generated; confidence scores decay in relevance if the taxonomy is updated.
- Use the **Quality Dashboard** (`/api/relations/metrics`) to monitor acceptance rates.  An acceptance rate below 30 % suggests the candidate search or compatibility matrix needs tuning.
- **Top Rejected** proposals are the most actionable: high-confidence predictions that were declined indicate systematic errors in the pipeline.

### Embedding

- Enable embedding for all production deployments to unlock semantic and hybrid search.
- If bandwidth or storage is limited, pre-download the model and set `TAXONOMY_EMBEDDING_MODEL_DIR` to the local path.

### Security

- Always set `ADMIN_PASSWORD` in production deployments to protect diagnostics and prompt-template endpoints.
- Rotate the admin password regularly; it is passed as a plain environment variable.

---

## 19. Glossary

| Term | Definition |
|---|---|
| **Anchor node** | A high-scoring leaf node that directly satisfies a business requirement; the starting point for architecture-view construction |
| **Architecture view** | A filtered subgraph of the taxonomy containing only the elements relevant to a given requirement |
| **ArchiMate** | An open standard modelling language for enterprise architecture, maintained by The Open Group |
| **C3** | Command, Control and Communications — the NATO functional area covered by this taxonomy |
| **Capability** | A bounded, outcome-oriented ability of an organisation or system (NAF, TOGAF) |
| **COI** | Community of Interest — a group that shares information under a common governance framework |
| **DJL** | Deep Java Library — an open-source deep-learning framework used to run ONNX embedding models |
| **Embedding** | A dense numeric vector that encodes the semantic meaning of text or a taxonomy node |
| **Hybrid search** | A retrieval strategy that merges full-text and semantic search rankings via Reciprocal Rank Fusion |
| **Information Product** | A specific, structured output of a business process (TOGAF Data Architecture) |
| **KNN** | K-Nearest Neighbours — a vector search algorithm that finds the closest embeddings |
| **LLM** | Large Language Model — the AI component used for scoring and justification |
| **NAF** | NATO Architecture Framework — the standard for describing NATO architectures |
| **ONNX** | Open Neural Network Exchange — an interoperable format for ML models |
| **Provenance** | The origin of a taxonomy relation: `MANUAL`, `ACCEPTED_PROPOSAL`, etc. |
| **Proposal** | An AI-generated candidate relation awaiting human review |
| **RRF** | Reciprocal Rank Fusion — an algorithm for combining ranked lists from multiple retrieval methods |
| **SSE** | Server-Sent Events — a web standard for streaming one-way events from server to browser |
| **Taxonomy node** | A single element in the C3 Taxonomy Catalogue (capability, service, role, etc.) |
| **TaxonomyRelation** | A confirmed, directed edge between two taxonomy nodes stored in the knowledge base |
| **TOGAF** | The Open Group Architecture Framework — a widely used enterprise-architecture methodology |
| **Visio** | Microsoft Visio — a diagramming application; exported as `.vsdx` (Office Open XML) |

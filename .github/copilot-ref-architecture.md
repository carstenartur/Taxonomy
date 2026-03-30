# Architecture & Module Reference

> **Read this when**: You need to understand the codebase structure, module layout, key services, or the taxonomy data model.

---

## Multi-Module Maven Structure

| Module | Purpose |
|---|---|
| `taxonomy-domain` | JPA entities, repositories, shared model classes |
| `taxonomy-dsl` | DSL parser, serializer, AST, validator, mappers — pure Java, no Spring |
| `taxonomy-export` | Visio/CSV/Excel export, depends on `taxonomy-domain` |
| `taxonomy-app` | Spring Boot application, controllers, services, UI — depends on all other modules |

## Key Services (in `taxonomy-app`)

| Service | Purpose |
|---|---|
| `TaxonomyService` | Loads taxonomy from Excel workbook (Apache POI), builds in-memory tree |
| `LlmService` | Calls Google Gemini (or other provider) for analysis; throws `LlmRateLimitException` on 429 |
| `RelationProposalService` | Manages proposed cross-taxonomy relations (create, accept, reject) |
| `LocalEmbeddingService` | Generates local KNN embeddings via DJL + ONNX (BAAI/bge-small-en-v1.5) |
| `DerivedMetadataService` | Recomputes graph metadata (hub/bridge/leaf/isolated roles, relation counts) |
| `ArchitectureSummaryService` | Produces architecture summaries (top capabilities, gaps, hub nodes) |
| `DslGitRepository` | JGit-backed version control for DSL documents stored in the database |

## Taxonomy Data Model

- Nodes are loaded from the **C3 Taxonomy Catalogue Excel workbook** at startup via `TaxonomyService`.
- Each node has a **code** in the format `XX-XXXX` (two uppercase letters, hyphen, four digits — e.g., `CP-1023`, `CR-1047`).
- **Not all four-digit numbers exist.** Codes come directly from the Excel workbook; never invent them.
- Relations between nodes are loaded from `relations.csv` (fallback) or created via the proposal API.
- Hibernate Search 8 + Lucene 9 indexes all nodes for full-text and KNN search.

## The 8 Taxonomy Roots

| Root | Domain |
|---|---|
| `BP` | Business Processes |
| `BR` | Business Roles |
| `CP` | Capabilities |
| `CI` | COI Services |
| `CO` | Communications |
| `CR` | Core Services |
| `IP` | Infrastructure & Platforms |
| `UA` | User Applications |

Total: approximately 2,500 nodes across all roots.

## DSL Architecture

| Component | Status | Notes |
|---|---|---|
| DSL Parser / Serializer / AST | ✅ Keep | Solid, Spring-independent |
| Validator | ✅ Keep | Useful semantic checks |
| AstToModelMapper / ModelToAstMapper | ✅ Keep | Bidirectional transformation |
| JGit commit/read/history | ✅ Keep | Blob→Tree→Commit→RefUpdate pattern is correct |
| `ModelDiffer` | ⚠️ Supplement | JGit `DiffFormatter` provides native text diffs; `ModelDiffer` is useful for semantic/structural comparison |
| `HibernateRepository` | ✅ Required | Must use database storage (not `InMemoryRepository`) for Hibernate Search compatibility |
| Cherry-pick / Merge | ✅ Added | Use JGit's `CherryPickCommand` / `MergeCommand` — do not reimplement |
| Dual JPA+JGit persistence | ❌ Removed | JGit in DB is the single source of truth; do not also save `ArchitectureDslDocument` on commit |

## Technology Stack

- **Spring Boot 4** / Java 21
- **Apache POI** — Excel workbook loading
- **Hibernate Search 8 + Lucene 9** — full-text and KNN search
- **Thymeleaf** — server-side HTML rendering (single Bootstrap 5 page UI)
- **JGit** — version control for DSL documents, stored in HSQLDB/external DB
- **DJL + ONNX Runtime** — local embedding model (BAAI/bge-small-en-v1.5)
- **Google Gemini** — LLM analysis (free-tier, rate-limited)

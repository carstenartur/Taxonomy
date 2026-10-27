# Architecture & Module Reference

> **Read this when**: You need to understand the codebase structure, module layout, key services, or the taxonomy data model.

---

## Multi-Module Maven Structure

Use the [current module inventory and ownership contracts](../docs/en/MODULE_BOUNDARIES.md)
and the root [POM](../pom.xml) for the complete reactor. Taxonomy is a modular
monolith with framework-free foundations, extracted feature libraries, a plugin
runtime and independent plugin, an application composition root, and build/tooling
modules. The domain foundation contains shared DTOs, enums, and contracts;
feature-owned JPA entities and repositories do not belong there.

`taxonomy-app` is the only executable Spring Boot artifact. It owns wiring,
security, cross-context adapters, migrations, packaging, and UI resources.
Provenance/document ingestion and preferences remain application-local. Feature
services, controllers, persistence, resources, and unit tests live with their
owning libraries; the application is not their default owner.

The same executable supports in-process analysis (`local`, the default) or
optional coordinator/worker processes using an external Apache Artemis broker.
That transport supports distributed analysis; it does not certify every web,
editor, plugin, or storage subsystem for general multi-replica deployment. Read
[deployment](../docs/en/DEPLOYMENT_GUIDE.md) and
[Artemis operations](../docs/en/OPERATIONS_GUIDE.md#external-artemis-analysis-operations)
for topology and configuration constraints.

## Key Services and Their Owners

| Service / entry point | Owner | Purpose |
|---|---|---|
| [`TaxonomyService`](../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java) | `taxonomy-knowledge` | Loads and persists the real Excel catalogue and relation seeds, provides catalogue/tree access and readiness |
| [`RelationProposalService`](../taxonomy-knowledge/src/main/java/com/taxonomy/relations/service/RelationProposalService.java) | `taxonomy-knowledge` | Manages cross-taxonomy relation proposals and their review state |
| [`LocalEmbeddingService`](../taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/LocalEmbeddingService.java) | `taxonomy-knowledge` | Local DJL/ONNX embeddings and semantic retrieval through Hibernate Search |
| [`LlmService`](../taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/LlmService.java) | `taxonomy-analysis` | Analysis orchestration, prompt construction, and response parsing; provider gateways own transport/admission policy |
| [`DerivedMetadataService`](../taxonomy-architecture/src/main/java/com/taxonomy/architecture/service/DerivedMetadataService.java) | `taxonomy-architecture` | Recomputes graph roles and relation counts |
| [`ArchitectureSummaryService`](../taxonomy-architecture/src/main/java/com/taxonomy/architecture/service/ArchitectureSummaryService.java) | `taxonomy-architecture` | Produces architecture summaries, gaps, and hub-node views |
| [`ArchitecturePipelineStepRegistry`](../taxonomy-architecture/src/main/java/com/taxonomy/architecture/pipeline/ArchitecturePipelineStepRegistry.java) | `taxonomy-architecture` | Orders and validates architecture-view pipeline contributions |
| [`DslGitRepository`](../taxonomy-workspace/src/main/java/com/taxonomy/workspace/storage/DslGitRepository.java) | `taxonomy-workspace` | Taxonomy-specific JGit facade over database-backed repository storage |

The shipped [application configuration](../taxonomy-app/src/main/resources/application.properties)
disables local embeddings and runtime model downloads by default. `MULTILINGUAL_MINILM_L12`
uses normalized mean pooling, 384 dimensions, 128 tokens and no query/document
prefix. The explicit `BGE_SMALL_EN` profile retains CLS pooling, 512 tokens and the
English query prefix. Provision and runtime profiles must match; a profile change
requires rebuilding the semantic index and discarding cached vectors. Local
embeddings do not provide generative relation or reformulation capabilities. See
the [pinned model provisioning guide](../docs/testing/multilingual-model-provisioning.md).

## Extension Direction

For new providers, use the framework-free `LlmTransportExtension` contract and
open `ProviderId` in `taxonomy-extension-api`. External artifacts expose their
contributions through a `TaxonomyPlugin` ServiceLoader entry. They do not require
a new built-in provider enum value. `taxonomy-analysis` retains selection,
admission, retries, cancellation, and metering; a contributed transport performs
one attempt under host policy. Check existing `CUSTOM_OPENAI` configuration before
adding a compatible transport.

Exports use the executable `ExportFormatExtension` contract in `taxonomy-export`;
the host's generic route and capability discovery consume registered contributions.
`taxonomy-extension-runtime` owns artifact validation, the atomic catalog, and
private classloaders. Providers are startup-bound; dynamic lifecycle changes are
restricted to eligible stateless exports/renderers in a single process. Follow
[extension points](../docs/dev/07-extension-points.md) and the
[plugin lifecycle boundaries](../docs/dev/extension-module-boundaries.md#optional-startup-installation-and-runtime-lifecycle).

## Taxonomy Data Model

- `TaxonomyService` imports the actual **C3 Taxonomy Catalogue Excel workbook**;
  normal restarts reuse persisted catalogue/index state unless reload is explicitly
  requested. Readiness and import timing depend on runtime configuration.
- Official concrete entries commonly use `XX-XXXX`; roots and local navigation
  additions have different identities/provenance. A syntactic prefix is not proof
  that an identifier exists or that it is an official catalogue concept.
- Discover codes and hierarchy from the actual catalogue or its exact recorded
  snapshot; never invent example identifiers for live requests.
- Initial relations come from the workbook's Relations sheet, with
  `data/relations.csv` as fallback when that sheet is absent. Reviewed relation
  changes use the proposal/command paths and workspace versioning contracts.
- Knowledge-owned JPA models and Hibernate Search/Lucene mappings provide
  full-text search; semantic KNN search additionally requires enabled, provisioned
  embeddings and a usable semantic index.

## The 8 Taxonomy Roots

| Root | Domain |
|---|---|
| `BP` | Business Processes |
| `BR` | Business Roles |
| `CP` | Capabilities |
| `CI` | COI Services |
| `CO` | Communications Services |
| `CR` | Core Services |
| `IP` | Information Products |
| `UA` | User Applications |

Do not infer concrete entry counts from the roots; use the catalogue source or
its recorded snapshot.

## DSL Architecture

| Component | Owner | Contract |
|---|---|---|
| Parser / serializer / AST / validator / mappers | `taxonomy-dsl` | Framework-free syntax, semantic checks, and bidirectional transformations |
| `ModelDiffer` | `taxonomy-dsl` | Semantic/structural comparison; JGit `DiffFormatter` supplies text diffs at the repository layer |
| `DslGitRepository` commit/read/history/merge | `taxonomy-workspace` | Uses JGit and the public `jgit-storage-hibernate-core` storage facade; production storage is database-backed, while explicit in-memory constructors support unit fixtures |
| Workspace/versioning operations | `taxonomy-workspace` | Repository identity, durable editor state, undo/redo, and explicit checkpoint preparation/publication |
| Architecture versions and live editing | `taxonomy-workspace` | Git owns durable versions/checkpoints; the private workspace and append-only semantic journal own accepted editing revisions. Do not turn each editor operation into a Git commit; see [ADR 0005](../docs/adr/0005-private-architecture-editor.md) |

## Technology Stack

- **Spring Boot** / Java 25 — exact dependencies and versions belong to the POMs
- **Apache POI** — Excel workbook loading
- **Hibernate Search + Lucene** — full-text and KNN search
- **Thymeleaf + Bootstrap** — server-rendered application pages with modular JavaScript
- **JGit** — version control for DSL documents, stored in HSQLDB/external DB
- **DJL + ONNX Runtime** — local embedding model (sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2)
- **Configured model providers** — gateway-managed analysis; credentials, limits,
  and available capabilities depend on operator/provider configuration

# Architecture Description

This document describes the architecture of the Taxonomy Architecture Analyzer — a Spring Boot web application for browsing, analysing, and visualising C3 Taxonomy data. It is intended for developers and system integrators who need to understand the system design, processing pipelines, and operational setup.

---

## Table of Contents

- [System Overview](#system-overview)
- [High-Level Architecture](#high-level-architecture)
- [Key Components](#key-components)
- [Architecture View Generation Pipeline](#architecture-view-generation-pipeline)
- [Data Loading](#data-loading)
- [CI / CD](#ci--cd)
- [Database](#database)
- [Export Formats](#export-formats)

---

## System Overview

The application is a single Spring Boot 4 / Java 17 web application with the following main characteristics:

- **In-process HSQLDB** — taxonomy data (~2,500 nodes across 8 sheets from an Excel workbook) is loaded at startup into an embedded HSQLDB database. No external database is required by default.
- **Multi-provider LLM integration** — business requirements can be analysed by any of six supported language model providers (Gemini, OpenAI, DeepSeek, Qwen, Llama, Mistral), or by a local offline model (`all-MiniLM-L6-v2` via DJL / ONNX Runtime) that requires no API key.
- **Taxonomy tree visualisation** — the hierarchy is rendered as a collapsible Bootstrap 5 tree with colour-coded match overlays.
- **Architecture intelligence** — scored analysis results are automatically assembled into architecture views, which can be exported to ArchiMate XML, Visio `.vsdx`, and Mermaid flowcharts.

---

## High-Level Architecture

```mermaid
graph TB
    subgraph Client["Browser / REST Client"]
        UI["Bootstrap 5 SPA<br/>(Thymeleaf + 10 JS modules)"]
    end

    subgraph App["Spring Boot 4 Application :8080"]
        direction TB
        Controllers["Controllers<br/>ApiController · GraphQueryApi<br/>ProposalApi · CoverageApi<br/>GapAnalysis · PatternDetection<br/>Recommendation · ArchiMateImport"]
        Services["Service Layer<br/>LlmService · TaxonomyService<br/>SearchService · HybridSearchService<br/>RequirementArchitectureViewService<br/>DiagramProjectionService<br/>RelationProposalService"]
        Persistence["Persistence<br/>HSQLDB (in-process)<br/>Hibernate Search 8 / Lucene 9"]
    end

    subgraph External["External / Local AI"]
        LLM["Cloud LLM Providers<br/>Gemini · OpenAI · DeepSeek<br/>Qwen · Llama · Mistral"]
        ONNX["Local ONNX Model<br/>all-MiniLM-L6-v2<br/>(DJL / ONNX Runtime)"]
    end

    UI -->|"HTTP / SSE"| Controllers
    Controllers --> Services
    Services --> Persistence
    Services -.->|"API call (optional)"| LLM
    Services -.->|"Embedding (local)"| ONNX
```

---

## Key Components

| Service | Role |
|---|---|
| `LlmService` | Multi-provider LLM integration. Dispatches analysis requests to the configured provider (Gemini, OpenAI, DeepSeek, Qwen, Llama, Mistral) or the local DJL/ONNX model. Handles rate-limit exceptions (`LlmRateLimitException` on HTTP 429 / `RESOURCE_EXHAUSTED`). |
| `TaxonomyService` | Loads the taxonomy catalogue from the bundled Excel workbook (Apache POI) or CSV fallback at startup. Manages the 8 taxonomy root categories (BP, BR, CP, CI, CO, CR, IP, UA). |
| `RequirementArchitectureViewService` | Builds architecture views from LLM analysis scores. Selects anchor nodes (score ≥ 70, with fallback to score ≥ 50) and propagates relevance through taxonomy relations to build a structured element/relationship graph. |
| `ArchitectureRecommendationService` | Produces architecture recommendations by combining direct matches, gap analysis, and semantic search results to suggest additional nodes and relations relevant to a given requirement. |
| `ArchitectureGapService` | Identifies missing relations and incomplete architecture patterns in the taxonomy graph relative to a given requirement. |
| `ArchitecturePatternService` | Detects standard architecture patterns (Full Stack, App Chain, Role Chain) in scored taxonomy results. |
| `ArchiMateDiagramService` | Converts architecture views into ArchiMate 3.x Model Exchange File Format XML, suitable for import into tools such as Archi, BiZZdesign, and MEGA. |
| `VisioDiagramService` | Generates Visio `.vsdx` diagram packages from architecture views. |
| `MermaidExportService` | Exports architecture views as Mermaid flowchart code blocks. |
| `DiagramProjectionService` | Projects architecture views into neutral diagram models that can be rendered by multiple exporters. |
| `RelevancePropagationService` | Propagates relevance scores from anchor nodes through taxonomy relations, expanding the architecture view to include indirectly relevant elements. |
| `SearchService` / `HybridSearchService` | Full-text (Lucene), semantic (embedding KNN), hybrid (Reciprocal Rank Fusion), and graph-based search across taxonomy nodes. |
| `LocalEmbeddingService` | Manages the local `all-MiniLM-L6-v2` embedding model via DJL/ONNX Runtime for semantic search and local scoring. |
| `RelationProposalService` | AI-assisted relation proposal pipeline: generates candidate relations and manages the human review workflow. |

---

## Architecture View Generation Pipeline

The following diagram and steps describe how a plain-text business requirement becomes an exportable architecture diagram:

```mermaid
flowchart TD
    A["1. User enters<br/>requirement text"] --> B["2. LlmService sends<br/>to configured provider"]
    B --> C["3. Score map returned<br/>(node code → 0–100%)"]
    C --> D["4. Anchor selection<br/>(score ≥ 70, fallback ≥ 50)"]
    D --> E["5. Relevance propagation<br/>through taxonomy relations"]
    E --> F["6. Element & relationship<br/>building"]
    F --> G["7. Diagram projection"]
    G --> H1["ArchiMate 3.x XML"]
    G --> H2["Visio .vsdx"]
    G --> H3["Mermaid flowchart"]
```

1. **Requirement text** — the user enters a free-text business requirement in the UI.
2. **LLM analysis** — `LlmService` sends the requirement to the configured provider; the response contains a score map (taxonomy node code → match percentage, 0–100).
3. **Anchor selection** — `RequirementArchitectureViewService` selects nodes with score ≥ 70 as primary anchors. If fewer than two anchors are found, the threshold falls back to score ≥ 50.
4. **Relevance propagation** — `RelevancePropagationService` follows taxonomy relations from the anchor nodes and assigns derived scores to connected nodes, building a weighted element graph.
5. **Element and relationship building** — architecture elements and their relationships are assembled from the propagated graph, respecting the taxonomy hierarchy.
6. **Diagram projection** — `DiagramProjectionService` converts the architecture model into a neutral representation that can be rendered by multiple exporters.
7. **Export** — the projected model is exported to the chosen format:
   - `ArchiMateDiagramService` → ArchiMate 3.x XML (`.archimate` / `.xml`)
   - `VisioDiagramService` → Visio `.vsdx`
   - `MermaidExportService` → Mermaid flowchart (`.md`)

---

## Data Loading

At startup, `TaxonomyService` loads the C3 Taxonomy Catalogue from the bundled Excel workbook (`src/main/resources/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx`) using Apache POI. A CSV fallback (`relations.csv`) is available if the Excel file cannot be read.

The 8 taxonomy root categories are:

| Code | Category |
|---|---|
| **BP** | Business Processes |
| **BR** | Business Roles |
| **CP** | Capabilities |
| **CI** | COI Services |
| **CO** | Communications Services |
| **CR** | Core Services |
| **IP** | Information Products |
| **UA** | User Applications |

Children are identified by hierarchical codes (e.g., `BP-1`, `CP-2`, `CR-3-1`).

## CI / CD

Every push triggers the **CI / CD** GitHub Actions workflow:

| Step | What happens |
|---|---|
| **Build & Test** | `mvn verify` — compiles, runs integration tests |
| **Publish Docker Image** | Pushes to GitHub Container Registry (`ghcr.io`) |
| **Deploy to Render** | Triggers a Render deploy hook (if secret is set) |

📋 **[Test Results Report](https://carstenartur.github.io/Taxonomy/tests/surefire-report.html)**
📈 **[Code Coverage Report](https://carstenartur.github.io/Taxonomy/coverage/)**

## Database

### Default: in-process HSQLDB

The application ships with an embedded HSQLDB database. No installation or external database server is required. All taxonomy data is loaded from the bundled Excel workbook at startup.

### MSSQL Compatibility

All entity classes are annotated for correct behaviour on Microsoft SQL Server:

- **`@Nationalized`** on every `String` field → produces `nvarchar` instead of `varchar`,
  preventing corruption of non-ASCII characters (e.g. German umlauts ä, ö, ü, ß).
- **`@Lob`** on text fields that may exceed 4000 characters (`descriptionEn`,
  `descriptionDe`, `reference`) → produces `nvarchar(max)` / `ntext` on MSSQL.
- **`@Lob` + `FloatArrayConverter`** on `semanticEmbedding` fields in `TaxonomyNode`
  and `TaxonomyRelation` → stores embedding vectors as streamable BLOBs using
  little-endian IEEE 754 serialisation.

The application continues to use HSQLDB by default (no MSSQL setup required).

## Export Formats

| Format | Description |
|---|---|
| **ArchiMate XML** | ArchiMate 3.x Model Exchange File Format XML, importable into Archi, BiZZdesign, MEGA, and other ArchiMate-compatible tools. |
| **Visio `.vsdx`** | Microsoft Visio diagram package, compatible with Visio 2013 and later. |
| **Mermaid flowchart** | Text-based Mermaid diagram (Markdown code block), renderable in GitHub, GitLab, Notion, Confluence, and most modern documentation platforms. |

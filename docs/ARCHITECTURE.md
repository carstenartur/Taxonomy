# Architecture Description

This document describes the architecture of the NC3T Taxonomy Browser — a Spring Boot web application for browsing, analysing, and visualising NATO C3 Taxonomy data. It is intended for developers and system integrators who need to understand the system design, processing pipelines, and operational setup.

## System Overview

The application is a single Spring Boot 4 / Java 17 web application with the following main characteristics:

- **In-process HSQLDB** — taxonomy data (~2,500 nodes across 8 sheets from an Excel workbook) is loaded at startup into an embedded HSQLDB database. No external database is required by default.
- **Multi-provider LLM integration** — business requirements can be analysed by any of six supported language model providers (Gemini, OpenAI, DeepSeek, Qwen, Llama, Mistral), or by a local offline model (`all-MiniLM-L6-v2` via DJL / ONNX Runtime) that requires no API key.
- **Taxonomy tree visualisation** — the hierarchy is rendered as a collapsible Bootstrap 5 tree with colour-coded match overlays.
- **Architecture intelligence** — scored analysis results are automatically assembled into architecture views, which can be exported to ArchiMate XML, Visio `.vsdx`, and Mermaid flowcharts.

## Key Components

| Service | Role |
|---|---|
| `LlmService` | Multi-provider LLM integration. Dispatches analysis requests to the configured provider (Gemini, OpenAI, DeepSeek, Qwen, Llama, Mistral) or the local DJL/ONNX model. Handles rate-limit exceptions (`LlmRateLimitException` on HTTP 429 / `RESOURCE_EXHAUSTED`). |
| `RequirementArchitectureViewService` | Builds architecture views from LLM analysis scores. Selects anchor nodes (score ≥ 70, with fallback to score ≥ 50) and propagates relevance through taxonomy relations to build a structured element/relationship graph. |
| `ArchitectureRecommendationService` | Produces architecture recommendations by combining direct matches, gap analysis, and semantic search results to suggest additional nodes and relations relevant to a given requirement. |
| `ArchiMateDiagramService` | Converts architecture views into ArchiMate 3.x Model Exchange File Format XML, suitable for import into tools such as Archi, BiZZdesign, and MEGA. |
| `DiagramProjectionService` | Projects architecture views into neutral diagram models that can be rendered as Visio diagrams or Mermaid flowcharts. |
| `RelevancePropagationService` | Propagates relevance scores from anchor nodes through taxonomy relations, expanding the architecture view to include indirectly relevant elements. |

## Architecture View Generation Pipeline

The following steps describe how a plain-text business requirement becomes an exportable architecture diagram:

1. **Requirement text** — the user enters a free-text business requirement in the UI.
2. **LLM analysis** — `LlmService` sends the requirement to the configured provider; the response contains a score map (taxonomy node code → match percentage, 0–100).
3. **Anchor selection** — `RequirementArchitectureViewService` selects nodes with score ≥ 70 as primary anchors. If fewer than two anchors are found, the threshold falls back to score ≥ 50.
4. **Relevance propagation** — `RelevancePropagationService` follows taxonomy relations from the anchor nodes and assigns derived scores to connected nodes, building a weighted element graph.
5. **Element and relationship building** — architecture elements and their relationships are assembled from the propagated graph, respecting the taxonomy hierarchy.
6. **Diagram projection** — `DiagramProjectionService` converts the architecture model into a neutral representation that can be rendered by multiple exporters.
7. **Export** — the projected model is exported to the chosen format:
   - `ArchiMateDiagramService` → ArchiMate 3.x XML (`.archimate` / `.xml`)
   - Visio exporter → `.vsdx`
   - `MermaidExportService` → Mermaid flowchart (`.md`)

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

# Administration Integration — Status & Roadmap

This document describes the integration of the Taxonomy Architecture Analyzer with administrative knowledge bases and interfaces of the German federal administration. It combines the current implementation status with the strategic roadmap for remaining work.

---

## Table of Contents

1. [Overview of Integration Points](#overview-of-integration-points)
2. [FIM Service Catalog Import](#fim-service-catalog-import)
3. [Administrative Regulation Parser](#administrative-regulation-parser)
4. [115 Knowledge Base Connection](#115-knowledge-base-connection)
5. [XÖV Schema Mapping](#xöv-schema-mapping)
6. [Phase Planning](#phase-planning)

---

## Overview of Integration Points

| Integration Point | Description | Status | Notes |
|---|---|---|---|
| **Administrative Regulation Parser** | PDF/DOCX → requirements extraction from administrative documents | ✅ Implemented | `DocumentParserService` with Apache PDFBox + Apache POI; see [Document Import](DOCUMENT_IMPORT.md) |
| **FIM Service Catalog Import** | Import administrative services as requirements for architecture analysis | ⚠️ Infrastructure ready | `SourceType.FIM_ENTRY` exists; FIM import profile not yet built |
| **115 Knowledge Base Connection** | Administrative domain information as context for RAG-based analysis | ⚠️ RAG pipeline ready | `LocalEmbeddingService` + `HybridSearchService` operational; 115 connector not yet built |
| **XÖV Schema Mapping** | Map XÖV messages to taxonomy nodes | 🟢 Planned | Not yet implemented |

---

## FIM Service Catalog Import

### Goal

The [Federal Information Management (FIM)](https://fimportal.de/) service catalog contains standardized descriptions of administrative services. By importing these service descriptions as business requirements, architecture analyses for administrative processes can be triggered automatically.

### Implementation Status: ⚠️ Infrastructure Ready

The data model supports FIM entries via `SourceType.FIM_ENTRY`, and the document import pipeline can be extended for FIM data. The FIM-specific import profile and format adapter are not yet implemented.

| What is ready | What remains |
|---|---|
| `SourceType.FIM_ENTRY` in source provenance model | FIM XML/JSON format parser |
| Document import infrastructure (`DocumentImportController`) | FIM field mapping configuration |
| Source provenance tracking | FIM Portal API integration |

### Concept

| Aspect | Detail |
|---|---|
| **Data Source** | FIM service catalog (XML/JSON export) |
| **Import Format** | Service name + service description → Business Requirement |
| **Mapping** | FIM service number → analysis reference |
| **Workflow** | Import → automatic AI analysis → architect reviews result |

### Prerequisites

- Access to the FIM service catalog export
- API specification of the FIM portal (if available)
- Mapping definition: which FIM fields are used as business requirements

---

## Administrative Regulation Parser

### Goal

Administrative regulations, service instructions, and domain-specific requirements documents are often available as PDF or DOCX. A parser should automatically extract requirements from these documents to serve as input for the architecture analysis.

### Implementation Status: ✅ Implemented

The regulation parser has been fully implemented using **Apache PDFBox** (PDF) and **Apache POI** (DOCX):

| Component | Implementation |
|---|---|
| **DocumentParserService** | Orchestrates document parsing and text extraction |
| **StructuredDocumentParser** | Extracts structured sections and headings |
| **DocumentAnalysisService** | AI-assisted requirement extraction from parsed text |
| **DocumentImportController** | REST endpoints for upload, extraction, and mapping |
| **taxonomy-document-import.js** | Frontend UI for document import workflow |

Three import modes are available:

1. **Extract Candidates** — Rule-based extraction of requirement candidates from document structure
2. **AI-Assisted Extraction** — LLM-based summarization and requirements extraction
3. **Direct Architecture Mapping** — Regulation-to-architecture mapping via AI analysis

> **Details:** See [Document Import](DOCUMENT_IMPORT.md) for the complete feature documentation.

### Concept

| Aspect | Detail |
|---|---|
| **Input Formats** | PDF, DOCX (additional formats extensible) |
| **Processing** | Text extraction → section structuring → requirements identification |
| **AI Support** | Optional: LLM-based summarization and requirements extraction |
| **Output** | Structured business requirements for the taxonomy analysis |
| **Technology** | Apache PDFBox (PDF), Apache POI (DOCX) |

### Synergies

This feature complements the knowledge preservation from documents described in [USE_CASE_WISSENSKONSERVIERUNG.md](USE_CASE_WISSENSKONSERVIERUNG.md).

---

## 115 Knowledge Base Connection

### Goal

The [115 knowledge base](https://www.115.de/) contains administrative domain information about government services and processes. By connecting it as a context source, the AI analysis can be enriched with administration-specific background knowledge (Retrieval Augmented Generation / RAG).

### Implementation Status: ⚠️ RAG Pipeline Ready

The RAG infrastructure is fully operational. Only the 115-specific data connector is missing:

| What is ready | What remains |
|---|---|
| `LocalEmbeddingService` (BAAI/bge-small-en-v1.5 ONNX) | 115 knowledge base export/API connector |
| `HybridSearchService` (Reciprocal Rank Fusion) | 115-specific data format adapter |
| Full-text + semantic + hybrid search modes | Data synchronization schedule |

### Concept

| Aspect | Detail |
|---|---|
| **Usage** | Context enrichment for LLM prompts (RAG) |
| **Data Source** | 115 knowledge base export or API |
| **Benefit** | Improve administrative domain precision of AI results |
| **Data Protection** | Only publicly available information; no personal data |

---

## XÖV Schema Mapping

### Goal

[XÖV standards](https://www.xoev.de/) define standardized message formats for data exchange in German administration (e.g., XBau, XBezahlen, XPersonenstandsrecht). By mapping XÖV schemas to taxonomy nodes, architecture analyses can take the interface standards in use into account.

### Concept

| Aspect | Detail |
|---|---|
| **Data Source** | XÖV schema repository (XSD files) |
| **Mapping** | XÖV message types → taxonomy nodes (e.g., integration pattern) |
| **Benefit** | Automatic identification of relevant integration standards for an architecture |
| **Export** | XÖV references can be integrated into the ArchiMate export |

---

## Phase Planning

### Phase 1: Completed ✅

| Step | Description | Status |
|---|---|---|
| Parser Implementation | PDF/DOCX text extraction with Apache PDFBox + Apache POI | ✅ Done |
| Import API | REST endpoints for document upload, extraction, and mapping | ✅ Done |
| Document Import UI | Frontend for document import workflow | ✅ Done |
| Source Provenance | Tracking of requirement origins with SourceType enum | ✅ Done |
| FIM Data Model | `SourceType.FIM_ENTRY` in provenance model | ✅ Done |

### Phase 2: Remaining Work

| Step | Description | Effort |
|---|---|---|
| FIM Import Profile | FIM XML/JSON format parser and field mapping | 2 Weeks |
| 115 Connector | Connector for the 115 knowledge base as RAG context source | 2 Weeks |
| XÖV Mapping | Prototypical mapping of XÖV schemas to taxonomy | 3 Weeks |
| Feedback | Pilot operation with partner agency; feedback integration | 4 Weeks |

---

## Related Documentation

- [Document Import](DOCUMENT_IMPORT.md) — Document import feature documentation (PDF/DOCX upload, provenance)
- [Use Case: Knowledge Preservation](USE_CASE_WISSENSKONSERVIERUNG.md) — Government use case for knowledge preservation
- [AI Transparency](AI_TRANSPARENCY.md) — AI transparency and data flows
- [Digital Sovereignty](DIGITAL_SOVEREIGNTY.md) — Digital sovereignty and openCode
- [API Reference](API_REFERENCE.md) — REST API documentation

# Administration Integration — Roadmap

This document describes the planned integration of the Taxonomy Architecture Analyzer with administrative knowledge bases and interfaces of the German federal administration. This is a strategic roadmap document.

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

| Integration Point | Description | Phase | Priority |
|---|---|---|---|
| **FIM Service Catalog Import** | Import administrative services as requirements for architecture analysis | Phase 1 | 🟡 Medium |
| **Administrative Regulation Parser** | PDF/DOCX → requirements extraction from administrative documents | Phase 1 | 🟡 Medium |
| **115 Knowledge Base Connection** | Administrative domain information as context for RAG-based analysis | Phase 2 | 🟢 Low |
| **XÖV Schema Mapping** | Map XÖV messages to taxonomy nodes | Phase 2 | 🟢 Low |

---

## FIM Service Catalog Import

### Goal

The [Federal Information Management (FIM)](https://fimportal.de/) service catalog contains standardized descriptions of administrative services. By importing these service descriptions as business requirements, architecture analyses for administrative processes can be triggered automatically.

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

### Concept

| Aspect | Detail |
|---|---|
| **Input Formats** | PDF, DOCX (additional formats extensible) |
| **Processing** | Text extraction → section structuring → requirements identification |
| **AI Support** | Optional: LLM-based summarization and requirements extraction |
| **Output** | Structured business requirements for the taxonomy analysis |

### Synergies

This feature complements the roadmap for knowledge preservation from documents described in [USE_CASE_WISSENSKONSERVIERUNG.md](USE_CASE_WISSENSKONSERVIERUNG.md).

---

## 115 Knowledge Base Connection

### Goal

The [115 knowledge base](https://www.115.de/) contains administrative domain information about government services and processes. By connecting it as a context source, the AI analysis can be enriched with administration-specific background knowledge (Retrieval Augmented Generation / RAG).

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

### Phase 1: Concept and Prototype (3–6 Months)

| Step | Description | Effort |
|---|---|---|
| FIM Catalog Analysis | Clarify format and availability of the FIM export | 1 Week |
| Parser Prototype | Evaluate PDF/DOCX text extraction with Apache Tika | 2 Weeks |
| Import API | REST endpoint for requirements import from structured sources | 2 Weeks |
| Pilot Test | Test run with 10–20 FIM services | 1 Week |

### Phase 2: Extended Integration (6–12 Months)

| Step | Description | Effort |
|---|---|---|
| 115 Connection | Evaluate the 115 knowledge base as a RAG context source | 2 Weeks |
| XÖV Mapping | Prototypical mapping of XÖV schemas to taxonomy | 3 Weeks |
| Feedback | Pilot operation with partner agency; feedback integration | 4 Weeks |

---

## Related Documentation

- [Use Case: Knowledge Preservation](USE_CASE_WISSENSKONSERVIERUNG.md) — Government use case for knowledge preservation
- [AI Transparency](AI_TRANSPARENCY.md) — AI transparency and data flows
- [Digital Sovereignty](DIGITAL_SOVEREIGNTY.md) — Digital sovereignty and openCode
- [API Reference](API_REFERENCE.md) — REST API documentation

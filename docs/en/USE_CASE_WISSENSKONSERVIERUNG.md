# Knowledge Preservation — Use Case for Government Agencies

This document describes the use of the Taxonomy Architecture Analyzer as a tool for **knowledge preservation** in government IT architecture environments.

---

## Table of Contents

1. [Problem Statement](#problem-statement)
2. [Solution: Architecture Memory](#solution-architecture-memory)
3. [Scenarios](#scenarios)
4. [Knowledge Preservation Features](#knowledge-preservation-features)
5. [Tracing Architecture Decisions](#tracing-architecture-decisions)
6. [Integration into Government Processes](#integration-into-government-processes)
7. [Technical Prerequisites](#technical-prerequisites)

---

## Problem Statement

Government agencies face a critical challenge in **preserving architecture knowledge**:

| Problem | Impact |
|---|---|
| **Staff Turnover** | Experienced architects leave the agency; their knowledge is lost |
| **Project Handover** | New teams take over projects without knowledge of prior decisions |
| **Long Project Lifecycles** | IT projects in government often run 5–10+ years; decisions from the early phases are no longer traceable |
| **Audit Requirements** | Courts of auditors and IT auditors require traceability of architecture decisions |
| **Knowledge Silos** | Architecture knowledge is trapped in emails, meeting minutes, and the minds of individual people |

### Typical Situation

> *"Why was it decided in 2022 to provide the communication service via platform X instead of Y?"*
>
> → Nobody knows anymore. The responsible architect has retired.

---

## Solution: Architecture Memory

The Taxonomy Architecture Analyzer acts as a **living architecture memory** that:

1. **Versions architecture decisions** — Every change to the architecture is captured in a Git repository (JGit)
2. **Logs AI-assisted analysis** — Why was each building block rated the way it was?
3. **Enables full-text search over history** — "Find all decisions related to communication"
4. **Provides comparisons between versions** — How has the architecture evolved over time?
5. **Generates exportable evidence** — ArchiMate XML, DOCX reports, Mermaid diagrams

---

## Scenarios

### Scenario 1: Staff Transition

**Situation:** An experienced IT architect leaves the agency. The successor is to take over the ongoing projects.

**Without Taxonomy Analyzer:**
- Handover document (PowerPoint, outdated)
- Verbal handover (incomplete)
- Successor must reconstruct decisions

**With Taxonomy Analyzer:**
1. Successor opens the application
2. Searches for the project name → finds all related architecture decisions
3. Views the history: which requirements were analyzed, when, and with what result
4. Understands the rationale through stored explanation traces
5. Can evolve the architecture without contradicting prior decisions

### Scenario 2: Project Handover to External Contractor

**Situation:** An IT project is handed over to a new consulting firm.

**Without Taxonomy Analyzer:**
- Extensive documentation must be created
- Architecture decisions are implicit (only in people's heads)
- Misunderstandings during handover

**With Taxonomy Analyzer:**
1. Export the current architecture as ArchiMate XML → import into Enterprise Architect
2. Export as DOCX report → handover document with ratings and justifications
3. Git history shows the evolution of the architecture over time
4. The contractor can navigate and search independently

### Scenario 3: Audit / IT Review

**Situation:** The court of auditors reviews the IT strategy and asks: "How were the architecture decisions for the communication project made?"

**With Taxonomy Analyzer:**
1. Search for "communication" → all analyses and ratings
2. Timeline view: when were which decisions made
3. AI ratings with justification texts (explanation traces)
4. Export as DOCX report for the audit file
5. Git commit history as a complete audit trail

### Scenario 4: Strategic Realignment

**Situation:** The IT strategy is revised after a legislative period. Which existing architecture decisions are affected?

**With Taxonomy Analyzer:**
1. Enter new requirement → AI evaluates against existing architecture
2. Gap analysis: which building blocks are missing, which are no longer relevant
3. Comparison: old architecture vs. new requirements (diff view)
4. Impact analysis: which dependent systems are affected

---

## Knowledge Preservation Features

### Currently Available

| Feature | Description | Access |
|---|---|---|
| **JGit Versioning** | Every DSL change is captured as a Git commit | Versions Tab → History |
| **Branching & Merge** | Maintain different architecture variants in parallel | Versions Tab → Variants |
| **Explanation Traces** | AI justifications for ratings | Analysis Result → Justification |
| **Diff Between Versions** | Changes between commits visible | DSL Diff View |
| **Full-Text Search** | Search in taxonomy descriptions | Search Field |
| **Semantic Search** | AI-powered meaning-based search | Semantic Search |
| **Graph Exploration** | Upstream/downstream dependencies | Graph Tab |
| **Multi-Format Export** | ArchiMate XML, Visio, Mermaid, JSON, DOCX | Export Buttons |
| **Audit Logging** | Who did what and when | Application Logs |

### Planned (Roadmap)

| Feature | Description | Phase |
|---|---|---|
| **Timeline View** | Chronological view of all architecture decisions | Phase 3 |
| **ADR Export** | Generate Architecture Decision Records from commit history | Phase 3 |
| **Document Import** | Import PDF/DOCX and automatically extract requirements | Phase 5 |
| **Meeting Transcription** | Transcribe audio recordings of architecture reviews | Phase 6 |

---

## Tracing Architecture Decisions

### Step by Step

1. **Enter requirement:** Describe the business requirement in natural language
2. **Run analysis:** The AI rates each building block of the taxonomy (0–100)
3. **Review result:** Relevant building blocks are displayed color-coded in the tree
4. **Read justification:** Each building block has an AI-generated justification
5. **Save architecture:** Commit in the DSL repository with a meaningful message
6. **Retrieve later:** Search, filter, history navigation, or graph exploration

### Rating Scale

| Score | Meaning | Color |
|---|---|---|
| 90–100 | High relevance — core building block | 🟢 Dark Green |
| 70–89 | Relevant — include | 🟡 Green/Yellow |
| 40–69 | Possibly relevant | 🟠 Orange |
| 1–39 | Low relevance | 🔴 Red |
| 0 | Not relevant | ⚪ Gray |

---

## Integration into Government Processes

### IT Architecture Management (TOGAF / IT-Grundschutz)

| Process Step | Taxonomy Analyzer Function |
|---|---|
| **Architecture Vision** | Requirements analysis → AI rating |
| **Architecture Development** | DSL modeling → versioning |
| **Architecture Review** | Comparison against existing architecture |
| **Change Management** | Diff view → impact analysis |
| **Compliance** | Audit logs → export for audit file |

### Procurement Processes

The Taxonomy Analyzer can support procurement decisions:

1. Enter requirements from the service specification
2. Identify relevant architecture building blocks
3. Gap analysis: which building blocks are not covered by existing IT
4. Export as evaluation basis for the procurement committee

### Documentation Obligations

| Obligation | Implementation |
|---|---|
| **IT Reference Architecture** | Export as ArchiMate XML → import into EA/Sparx |
| **Architecture Overview** | Export as Mermaid → embed in Confluence/Wiki |
| **Audit Report** | DOCX report with ratings and justifications |
| **Change History** | Git log as a traceable audit trail |

---

## Technical Prerequisites

### Minimum Configuration for Government Agencies

```bash
# Air-gapped operation (no external API calls)
LLM_PROVIDER=LOCAL_ONNX
TAXONOMY_EMBEDDING_ENABLED=true
TAXONOMY_EMBEDDING_MODEL_DIR=/app/models/bge-small-en-v1.5

# Production profile with hardened defaults
SPRING_PROFILES_ACTIVE=production,postgres

# Audit logging enabled
TAXONOMY_AUDIT_LOGGING=true

# Force password change
TAXONOMY_REQUIRE_PASSWORD_CHANGE=true
```

### Data Sovereignty

| Requirement | Implementation |
|---|---|
| **No data leaves the network** | `LLM_PROVIDER=LOCAL_ONNX` |
| **Pre-download all models** | `TAXONOMY_EMBEDDING_MODEL_DIR` |
| **Own database** | PostgreSQL on-premises |
| **Centralized authentication** | Keycloak with LDAP/SAML |
| **Complete audit** | `TAXONOMY_AUDIT_LOGGING=true` |

---

## Related Documentation

- [AI Transparency](AI_TRANSPARENCY.md) — Transparency about AI usage
- [Data Protection](DATA_PROTECTION.md) — GDPR documentation
- [Security](SECURITY.md) — Security architecture
- [Operations Guide](OPERATIONS_GUIDE.md) — Operations and maintenance
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) — Checklist for deployment

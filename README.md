# Taxonomy Architecture Analyzer

[![CI/CD](https://github.com/carstenartur/Taxonomy/actions/workflows/ci-cd.yml/badge.svg?branch=main)](https://github.com/carstenartur/Taxonomy/actions/workflows/ci-cd.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Taxonomy/coverage/badge.json)](https://carstenartur.github.io/Taxonomy/coverage/)
[![Tests](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Taxonomy/tests/badge.json)](https://carstenartur.github.io/Taxonomy/tests/surefire-report.html)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![SBOM](https://img.shields.io/badge/SBOM-CycloneDX-informational?logo=owasp&style=flat)](https://github.com/carstenartur/Taxonomy/dependency-graph/sbom)

**Turn a business requirement into a validated architecture view — in one step.**

Describe what you need in plain English. The Taxonomy Architecture Analyzer scores every node in the C3 Taxonomy Catalogue (~2,500 elements) using AI, discovers architecture relations, and generates exportable diagrams.

```mermaid
flowchart LR
    A["✏️ Requirement"] --> B["🤖 AI Analysis"]
    B --> C["🌳 Scored Tree"]
    C --> D["🏗️ Architecture View"]
    D --> E["📐 Export"]

    style A fill:#e3f2fd
    style B fill:#fff3e0
    style C fill:#e8f5e9
    style D fill:#f3e5f5
    style E fill:#fce4ec
```

---

## Quick Example

> _"Provide an integrated communication platform for hospital staff, enabling real-time voice and data exchange between departments and coordinated workflow management for clinical teams."_

<img src="docs/images/15-scored-taxonomy-tree.png" alt="Scored taxonomy tree" width="700">

The system scores every taxonomy node, selects the most relevant elements (score ≥ 70), propagates relevance through relations, and generates an architecture view — ready for export to ArchiMate, Visio, or Mermaid.

<details>
<summary><strong>Architecture view generated from this requirement</strong></summary>

<img src="docs/images/20-architecture-view.png" alt="Generated architecture view" width="700">
</details>

---

## Core Workflow (UI)

| Step | What you do | What happens |
|:---:|---|---|
| **1** | Enter a requirement in the analysis panel | Free-text input |
| **2** | Click **Analyze with AI** | AI scores every taxonomy node (0–100) |
| **3** | Explore the scored tree | Colour-coded results across 6 view modes |
| **4** | Review relations and proposals | Accept/reject AI-generated architecture relations |
| **5** | Export | One-click export to ArchiMate XML, Visio, Mermaid, or JSON |

<img src="docs/images/01-full-page-layout.png" alt="Full page layout" width="700">

<details>
<summary><strong>Export buttons</strong></summary>

<img src="docs/images/23-export-buttons.png" alt="Export buttons" width="700">
</details>

---

## Key Features

| Area | Capabilities |
|---|---|
| **Analysis** | AI-scored taxonomy mapping · semantic, hybrid, and graph search · relevance propagation |
| **Architecture** | Automatic architecture views · relation proposals with review workflow · gap analysis · pattern detection |
| **Graph** | Upstream/downstream exploration · failure-impact analysis · requirement impact |
| **DSL** | Text-based architecture DSL · JGit-backed versioning with branching and merge |
| **Export** | ArchiMate 3.x XML · Visio `.vsdx` · Mermaid · JSON · Reports (Markdown, HTML, DOCX) |

---

## Installation

### Prerequisites

| Requirement | Notes |
|---|---|
| **Java 17+** | JDK for building, JRE for running |
| **Maven 3.9+** | Build only |
| **LLM API key** _or_ `LLM_PROVIDER=LOCAL_ONNX` | Required for AI analysis; browsing and search work without it |

### Run locally

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy

# With Gemini (default)
GEMINI_API_KEY=your-key mvn spring-boot:run

# Fully offline (no API key)
LLM_PROVIDER=LOCAL_ONNX mvn spring-boot:run

# Browse-only (no AI analysis)
mvn spring-boot:run
```

Open <http://localhost:8080> and log in with `admin` / `admin`.

> ⚠️ **Local development only:** The default credentials `admin` / `admin` are intended
> for local development. For any non-local deployment, set the `TAXONOMY_ADMIN_PASSWORD`
> environment variable before exposing the application.

**→ Now follow the [Core Workflow](#core-workflow-ui) above to run your first analysis.**

### Docker

```bash
docker build -t taxonomy-analyzer .
docker run -p 8080:8080 -e GEMINI_API_KEY=your-key taxonomy-analyzer
```

### Build & Test

```bash
mvn compile           # Compile only
mvn test              # Unit + Spring context tests (no Docker needed)
mvn verify            # Unit + integration tests (requires Docker)
```

---

## REST API (for Automation & Integration)

The primary way to use this product is through the **web-based GUI** (see [Core Workflow](#core-workflow-ui) above).

For **scripting, CI pipelines, and system integration**, a REST API is available:

- Interactive documentation: [`/swagger-ui.html`](http://localhost:8080/swagger-ui.html)
- [API Reference](docs/en/API_REFERENCE.md) — endpoint overview
- [Curl Workflow Examples](docs/en/CURL_EXAMPLES.md) — end-to-end automation examples

> **Note:** The REST API is not intended as a replacement for the GUI for end-user workflows.
> All user-facing features are designed to be used through the web interface first.

---

## Repository Structure

```
Taxonomy/
├── taxonomy-domain/     # Pure domain types (DTOs, enums) — no framework dependencies
├── taxonomy-dsl/        # Architecture DSL: parser, serializer, validator, differ
├── taxonomy-export/     # Export formats: ArchiMate, Visio, Mermaid, Diagram
├── taxonomy-app/        # Spring Boot application: REST API, services, persistence, UI
├── docs/                # Documentation and auto-generated screenshots
└── pom.xml              # Parent POM (4 modules, Spring Boot 4, Java 17)
```

---

## Documentation

| Document | Description |
|---|---|
| **[User Guide](docs/en/USER_GUIDE.md)** | End-user guide with screenshots and workflow walkthroughs |
| **[Examples](docs/en/EXAMPLES.md)** | Worked examples for analysis, impact, proposals, export |
| **[Concepts & Glossary](docs/en/CONCEPTS.md)** | Key terms and domain model |
| **[API Reference](docs/en/API_REFERENCE.md)** | REST API quick-reference with request/response examples |
| **[Configuration](docs/en/CONFIGURATION_REFERENCE.md)** | Environment variables and settings |
| **[Deployment](docs/en/DEPLOYMENT_GUIDE.md)** | Docker, Render.com, health checks |
| **[Deployment Checklist](docs/en/DEPLOYMENT_CHECKLIST.md)** | Pre-deployment verification checklist |
| **[Security](docs/en/SECURITY.md)** | Authentication, roles, permissions, deployment hardening |
| **[Database Setup](docs/en/DATABASE_SETUP.md)** | PostgreSQL, MSSQL, Oracle configuration |
| **[Keycloak & SSO](docs/en/KEYCLOAK_SETUP.md)** | SSO/OIDC/SAML integration with Keycloak |
| **[Architecture](docs/en/ARCHITECTURE.md)** | System design, modules, DSL storage, pipelines |
| **[Developer Guide](docs/en/DEVELOPER_GUIDE.md)** | Module architecture, testing, extending the system |
| **[Feature Matrix](docs/en/FEATURE_MATRIX.md)** | Feature completeness tracking (GUI, REST, docs, i18n) |
| **[AI Transparency](docs/en/AI_TRANSPARENCY.md)** | AI/LLM usage transparency documentation |
| **[Data Protection](docs/en/DATA_PROTECTION.md)** | GDPR and data protection compliance |
| **[Operations Guide](docs/en/OPERATIONS_GUIDE.md)** | Operational procedures and monitoring |
| **[Knowledge Conservation](docs/en/USE_CASE_WISSENSKONSERVIERUNG.md)** | Use case: architecture knowledge preservation |

## Government Readiness (Behördentauglichkeit)

The Taxonomy Architecture Analyzer includes comprehensive documentation for deployment in German government and public administration environments:

| Document | Description |
|---|---|
| **[BSI KI Checklist](docs/en/BSI_KI_CHECKLIST.md)** | BSI criteria checklist for AI models in federal administration |
| **[AI Literacy Concept](docs/en/AI_LITERACY_CONCEPT.md)** | Training concept per EU AI Act Art. 4 (AI Literacy) |
| **[Accessibility / BITV 2.0](docs/en/ACCESSIBILITY.md)** | BITV 2.0 / WCAG 2.1 accessibility concept and action plan |
| **[Digital Sovereignty](docs/en/DIGITAL_SOVEREIGNTY.md)** | Digital sovereignty, openCode compatibility, DVC architecture |
| **[Administration Integration](docs/en/VERWALTUNGSINTEGRATION.md)** | FIM / 115 / XÖV integration roadmap |
| **[AI Transparency](docs/en/AI_TRANSPARENCY.md)** | EU AI Act compliance, data flows, explainability |
| **[Data Protection](docs/en/DATA_PROTECTION.md)** | GDPR/DSGVO compliance, BfDI guidelines |
| **[Security](docs/en/SECURITY.md)** | Authentication, roles, hardening, workspace access rights |
| **[Deployment Checklist](docs/en/DEPLOYMENT_CHECKLIST.md)** | Government/enterprise deployment verification checklist |
| **[Knowledge Conservation](docs/en/USE_CASE_WISSENSKONSERVIERUNG.md)** | Use case: architecture knowledge preservation for government |

**Key capabilities for government use:**
- 🔒 **Air-gapped operation** — `LLM_PROVIDER=LOCAL_ONNX` for fully offline deployment
- 🇪🇺 **EU data residency** — Mistral (France/EU) as cloud LLM alternative
- 📋 **SBOM** — CycloneDX Software Bill of Materials generated at build time
- 🏛️ **Open Source** — MIT license, full source code, no vendor lock-in
- 🔐 **SSO/OIDC** — Keycloak integration for government identity providers (see [Keycloak & SSO Setup](docs/en/KEYCLOAK_SETUP.md))

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Run tests (`mvn test`)
4. Commit your changes
5. Open a pull request

> **Important:** For user-facing features, please read the
> [Definition of Done](docs/en/DEVELOPER_GUIDE.md#definition-of-done--user-facing-features)
> before opening a PR. Features that only add a REST endpoint without GUI support
> are not considered complete.

## License

This project is licensed under the [MIT License](LICENSE).

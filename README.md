# Taxonomy Architecture Analyzer

[![CI/CD](https://github.com/carstenartur/Taxonomy/actions/workflows/ci-cd.yml/badge.svg?branch=main)](https://github.com/carstenartur/Taxonomy/actions/workflows/ci-cd.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Taxonomy/coverage/badge.json)](https://carstenartur.github.io/Taxonomy/coverage/)
[![Tests](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Taxonomy/tests/badge.json)](https://carstenartur.github.io/Taxonomy/tests/surefire-report.html)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## 1. Project Overview

The Taxonomy Architecture Analyzer maps free-text business requirements to the C3 Taxonomy Catalogue (~2,500 nodes across 8 sheets) using an AI language model. It scores every taxonomy node against a requirement, propagates relevance through architecture relations, and generates exportable architecture views and diagrams — all in a single step.

**Technology stack:** Java 17 · Spring Boot 4 · HSQLDB · Hibernate Search 8 / Lucene 9 · Apache POI · DJL/ONNX Runtime · Bootstrap 5 · Thymeleaf

---

## 2. Example

**Requirement:**

> _"Provide secure voice communications for deployed forces."_

**Matched taxonomy elements:**

| Category | Matched Element |
|---|---|
| **Capability** | Secure Communications Capability |
| **Service** | Secure Voice Service |
| **Process** | Conduct Operations |
| **Application** | Operations Coordination System |

The system scores every node, selects the most relevant elements, propagates relevance through their relations, and generates an architecture view — ready for export.

<details>
<summary><strong>Scored taxonomy tree</strong></summary>

<img src="docs/images/15-scored-taxonomy-tree.png" alt="Scored taxonomy tree with match percentages" width="800">
</details>

<details>
<summary><strong>Scored taxonomy tree — fully expanded</strong></summary>

<img src="docs/images/35-scored-bp-tree-expanded.png" alt="Full Business Process taxonomy tree expanded with heat-mapped scores" width="800">
</details>

<details>
<summary><strong>Architecture view</strong></summary>

<img src="docs/images/20-architecture-view.png" alt="Generated architecture view" width="800">
</details>

---

## 3. Key Features

- **Requirement → Taxonomy mapping** — AI scores every taxonomy node against a free-text requirement
- **Semantic and hybrid search** — full-text (Lucene), semantic (embedding KNN), hybrid (Reciprocal Rank Fusion), and graph-based search
- **Architecture relationship discovery** — automatic architecture views from scored results with relevance propagation
- **Architecture impact analysis** — upstream, downstream, and failure-impact neighbourhood queries
- **Relation proposals and review** — AI-generated relation proposals with human accept/reject workflow
- **Architecture graph exploration** — trace dependencies and discover gaps and patterns
- **Architecture DSL with versioning** — text-based domain-specific language backed by JGit for branching, merging, and history
- **Report generation** — export analysis reports in Markdown, HTML, DOCX, and JSON formats
- **Export to Visio, ArchiMate, Mermaid and JSON** — industry-standard diagram formats for downstream tools

---

## 4. Typical Workflow

```mermaid
flowchart LR
    A["✏️ Enter<br/>Requirement"] --> B["🤖 AI<br/>Analysis"]
    B --> C["🌳 Scored<br/>Tree"]
    C --> D["🏗️ Architecture<br/>View"]
    D --> E["📐 Export<br/>Diagram"]

    style A fill:#e3f2fd
    style B fill:#fff3e0
    style C fill:#e8f5e9
    style D fill:#f3e5f5
    style E fill:#fce4ec
```

1. **Enter a requirement** — type a free-text business or mission requirement in the analysis panel.
2. **AI scores the taxonomy** — the configured LLM evaluates every taxonomy node and returns match percentages (0–100).
3. **Explore the scored tree** — results are overlaid on the tree with colour intensity proportional to the score. Switch between view modes for different perspectives.
4. **Generate an architecture view** — anchor nodes (score ≥ 70) are selected and relevance propagates through taxonomy relations to build a structured architecture model.
5. **Export a diagram** — one-click export to ArchiMate XML, Visio, Mermaid flowchart, or JSON.

---

## 5. System Overview

```mermaid
graph TB
    subgraph Client["Browser / REST Client"]
        UI["Bootstrap 5 SPA<br/>(Thymeleaf + JavaScript)"]
    end

    subgraph App["Spring Boot 4 Application :8080"]
        Controllers["REST Controllers (15)<br/>ApiController · GraphQueryApi<br/>ProposalApi · CoverageApi<br/>GapAnalysis · PatternDetection<br/>Recommendation · ArchiMateImport<br/>DslApi · ReportApi · RelationApi<br/>QualityApi · ExplanationTrace<br/>ArchitectureSummary"]
        Services["Service Layer<br/>LlmService · TaxonomyService<br/>SearchService · HybridSearchService<br/>RequirementArchitectureViewService<br/>DiagramProjectionService<br/>RelationProposalService"]
        Persistence["Persistence<br/>HSQLDB (in-process)<br/>Hibernate Search 8 / Lucene 9"]
    end

    subgraph External["External Services"]
        LLM["LLM Providers<br/>Gemini · OpenAI · DeepSeek<br/>Qwen · Llama · Mistral"]
        LocalModel["Local ONNX Model<br/>all-MiniLM-L6-v2 via DJL"]
    end

    UI -->|HTTP / SSE| Controllers
    Controllers --> Services
    Services --> Persistence
    Services -->|API calls| LLM
    Services -->|Embedding| LocalModel
```

**Pipeline:**

```
Requirement  →  Semantic Analysis  →  Taxonomy Matching  →  Architecture Graph  →  Architecture Views  →  Diagram Export
```

---

## 6. User Interface

The UI is a single Bootstrap 5 page rendered by Thymeleaf. The left panel shows the taxonomy tree; the right panel holds the analysis input, scored results, and architecture view.

<img src="docs/images/01-full-page-layout.png" alt="Full page layout" width="800">

Six view modes are available for the taxonomy tree:

| Mode | Description |
|---|---|
| **List** | Collapsible tree with expand/collapse toggles |
| **Tabs** | Grouped by taxonomy sheet in tabbed panels |
| **Sunburst** | Radial hierarchical chart |
| **Tree** | D3 tree layout |
| **Decision Map** | Force-directed graph layout |
| **Summary** | Layered architecture overview (appears after analysis with Architecture View enabled) |

Additional panels: Graph Explorer (upstream/downstream/failure-impact queries), Relation Proposals (AI-generated proposals with accept/reject), and an Admin panel for LLM diagnostics and prompt template editing.

<details>
<summary><strong>Scored sunburst chart</strong></summary>

<img src="docs/images/39-scored-sunburst.png" alt="Scored sunburst chart with heat-mapped colour gradients" width="800">
</details>

### Workflow at a Glance

| Step | Screenshot |
|---|---|
| **1. Analyze** — Score taxonomy nodes against a requirement | <img src="docs/images/15-scored-taxonomy-tree.png" width="250"> |
| **2. Accept proposals** — Review and approve AI-generated relations | <img src="docs/images/36-proposal-accepted.png" width="250"> |
| **3. View architecture** — See the resulting architecture diagram | <img src="docs/images/38-architecture-view-detailed.png" width="250"> |

---

## 7. Export Formats

| Format | Extension | Use Case |
|---|---|---|
| **ArchiMate 3.x XML** | `.xml` | Import into Archi, BiZZdesign, MEGA, and other ArchiMate-compatible tools |
| **Visio** | `.vsdx` | Microsoft Visio 2013+ diagrams |
| **Mermaid** | `.md` | Text-based diagrams renderable in GitHub, GitLab, Notion, Confluence |
| **JSON** | `.json` | Save and reload analysis scores; integrate with external tooling |

<details>
<summary><strong>Export buttons</strong></summary>

<img src="docs/images/23-export-buttons.png" alt="Export buttons for ArchiMate, Visio, Mermaid and JSON" width="800">
</details>

---

## 8. REST API

Interactive documentation is available at [`/swagger-ui.html`](http://localhost:8080/swagger-ui.html) when the application is running.

| Category | Key Endpoints | Description |
|---|---|---|
| **Analysis** | `POST /api/analyze`, `GET /api/analyze-stream` | Score taxonomy nodes against a requirement |
| **Node Analysis** | `GET /api/analyze-node` | Analyse a single node and its children |
| **Justification** | `POST /api/justify-leaf` | Natural-language explanation for a leaf score |
| **Search** | `GET /api/search`, `/search/semantic`, `/search/hybrid`, `/search/graph` | Full-text, semantic, hybrid, and graph search |
| **Graph** | `GET /api/graph/node/{code}/upstream`, `/downstream`, `/failure-impact` | Graph neighbourhood queries |
| **Proposals** | `POST /api/proposals/propose`, `GET /api/proposals`, `POST /api/proposals/bulk` | Relation proposals with review workflow |
| **Relations** | `GET /api/relations`, `POST /api/relations`, `DELETE /api/relations/{id}` | Relation CRUD and browsing |
| **Gap Analysis** | `POST /api/gap/analyze` | Identify missing relations and coverage gaps |
| **Recommendations** | `POST /api/recommend` | Architecture element and relation recommendations |
| **Patterns** | `GET /api/patterns/detect`, `POST /api/patterns/detect` | Detect standard architecture patterns (single node or scored set) |
| **Coverage** | `POST /api/coverage/record`, `GET /api/coverage/statistics` | Requirement coverage tracking |
| **Export** | `POST /api/diagram/archimate`, `/diagram/visio`, `/diagram/mermaid` | Diagram export in multiple formats |
| **Reports** | `POST /api/report/markdown`, `/report/html`, `/report/docx`, `/report/json` | Analysis reports in Markdown, HTML, DOCX, JSON |
| **Explanation** | `POST /api/explain/{nodeCode}`, `POST /api/explain` | Explanation traces for scored nodes |
| **Architecture DSL** | `POST /api/dsl/commit`, `GET /api/dsl/history`, `POST /api/dsl/merge` | Versioned DSL documents with JGit-backed branching and merge |
| **Architecture Summary** | `GET /api/architecture/summary`, `GET /api/architecture/metadata/{nodeCode}` | Architecture summary with next steps and node graph metadata |
| **Quality** | `GET /api/relations/metrics`, `/metrics/by-type` | Relation quality dashboard metrics |
| **Admin** | `GET /api/diagnostics`, `GET /api/ai-status` | LLM diagnostics and system status |

> Full API reference with request/response schemas: [docs/API_REFERENCE.md](docs/API_REFERENCE.md)

---

## 9. Installation

### Prerequisites

| Requirement | Notes |
|---|---|
| **Java 17+** | JDK for building, JRE for running |
| **Maven 3.9+** | Build only |
| **LLM API key** _or_ `LLM_PROVIDER=LOCAL_ONNX` | Required for AI analysis; browsing and search work without it |
| **Docker** _(optional)_ | For containerised deployment |

### Run locally

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy

# With Gemini (default LLM provider)
GEMINI_API_KEY=your-key mvn spring-boot:run

# With OpenAI
LLM_PROVIDER=OPENAI OPENAI_API_KEY=your-key mvn spring-boot:run

# Fully offline (no API key required)
LLM_PROVIDER=LOCAL_ONNX mvn spring-boot:run

# Browse-only mode (no AI analysis)
mvn spring-boot:run
```

Open <http://localhost:8080> in your browser.

### Docker

```bash
docker build -t taxonomy-analyzer .
docker run -p 8080:8080 -e GEMINI_API_KEY=your-key taxonomy-analyzer

# Or use the pre-built image
docker pull ghcr.io/carstenartur/taxonomy:latest
docker run -p 8080:8080 -e GEMINI_API_KEY=your-key ghcr.io/carstenartur/taxonomy:latest
```

### Build & Test

```bash
mvn compile           # Compile only
mvn test              # Unit + Spring context tests (~716 tests, no Docker needed)
mvn verify            # Unit + integration tests (requires Docker for container tests)
```

---

## 10. Repository Structure

```
Taxonomy/
├── taxonomy-domain/               # Pure domain types (DTOs, enums) — no framework dependencies
│   └── src/main/java/com/taxonomy/
│       ├── dto/                   # Data transfer objects (43 DTOs)
│       └── model/                 # Domain enums (RelationType, HypothesisStatus, ProposalStatus)
├── taxonomy-dsl/                  # Architecture DSL core — no framework dependencies
│   └── src/main/java/com/taxonomy/dsl/
│       ├── ast/                   # Abstract syntax tree
│       ├── model/                 # Canonical architecture model
│       ├── parser/                # TaxDSL parser + tokenizer
│       ├── serializer/            # TaxDSL serializer
│       ├── mapper/                # AST ↔ Model mappers
│       ├── validation/            # DSL validator
│       └── diff/                  # Model differ
├── taxonomy-export/               # Export formats — framework-free
│   └── src/main/java/com/taxonomy/
│       ├── archimate/             # ArchiMate model classes
│       ├── diagram/               # Diagram projection models
│       ├── visio/                 # Visio document model + XStream converters
│       └── export/                # Export services (Mermaid, Visio, ArchiMate, Diagram)
├── taxonomy-app/                  # Spring Boot application
│   └── src/main/java/com/taxonomy/
│       ├── controller/            # REST API controllers
│       ├── service/               # Business logic (LLM, search, architecture, graph)
│       ├── model/                 # JPA entities (TaxonomyNode, TaxonomyRelation, ...)
│       ├── repository/            # Spring Data JPA repositories
│       ├── search/                # Hibernate Search configuration
│       ├── config/                # Application configuration
│       └── dsl/                   # DSL storage (JGit) + materialization
│   └── src/main/resources/
│       ├── data/                  # Excel workbook + CSV fallback + JSON taxonomy
│       ├── prompts/               # LLM prompt templates
│       ├── static/                # CSS and JavaScript (Bootstrap 5 UI)
│       ├── templates/             # Thymeleaf HTML templates
│       └── application.properties
├── docs/                          # Documentation and auto-generated screenshots
├── Dockerfile                     # Multi-stage Docker build
├── render.yaml                    # Render.com deployment blueprint
├── pom.xml                        # Parent POM (4 modules, Spring Boot 4, Java 17)
└── README.md
```

---

## Documentation

| Document | Description |
|---|---|
| **[User Guide](docs/USER_GUIDE.md)** | End-user guide with screenshots |
| **[Concepts & Glossary](docs/CONCEPTS.md)** | Key terms and definitions |
| **[Examples](docs/EXAMPLES.md)** | Worked examples for analysis, impact, gap analysis, proposals |
| **[Architecture](docs/ARCHITECTURE.md)** | System design, pipeline, module structure, DSL storage |
| **[API Reference](docs/API_REFERENCE.md)** | Full REST API documentation (97 endpoints) |
| **[Configuration](docs/CONFIGURATION_REFERENCE.md)** | Environment variables and settings |
| **[Deployment](docs/DEPLOYMENT_GUIDE.md)** | Docker, Render.com, health checks |
| **[Developer Guide](docs/DEVELOPER_GUIDE.md)** | Module architecture, testing, how to extend the system |

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Run tests (`mvn test`)
4. Commit your changes
5. Open a pull request

## License

This project is licensed under the [MIT License](LICENSE).


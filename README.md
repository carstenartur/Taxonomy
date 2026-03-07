# Taxonomy

[![CI/CD](https://github.com/carstenartur/Taxonomy/actions/workflows/ci-cd.yml/badge.svg?branch=main)](https://github.com/carstenartur/Taxonomy/actions/workflows/ci-cd.yml)
[![Coverage](https://img.shields.io/badge/coverage-report-brightgreen)](https://carstenartur.github.io/Taxonomy/coverage/)
[![Tests](https://img.shields.io/badge/tests-report-blue)](https://carstenartur.github.io/Taxonomy/tests/surefire-report.html)

NC3T Taxonomy Browser — a Spring Boot web application that loads the
C3 Taxonomy Catalogue (Baseline 7, 8 sheets, ~2,500 nodes) from the bundled
Excel file into an in-process HSQLDB, visualises the hierarchy as a collapsible
tree, and lets you match a plain-text business requirement against the taxonomy
using an AI language model.

## Features

* Full taxonomy tree browser (Bootstrap 5, collapsible nodes)
* Free-text business-requirement analyser powered by **multiple LLM providers**
  (Gemini, OpenAI, DeepSeek, Qwen, Llama, Mistral) **and a local offline model**
  (`all-MiniLM-L6-v2` via DJL / ONNX Runtime — no API key required)
* Recursive match: root → matching children → their children … until leaf nodes
* Results overlaid on the tree with **green shading** (intensity = match %)
* All taxonomy data kept in an **in-process HSQLDB** (no external DB needed)
* AI availability indicator: the Analyze button is disabled with a clear warning
  when no API key is configured

## Quick Start (local)

```bash
# Build & run
mvn spring-boot:run

# or with Docker
docker build -t nato-taxonomy .
docker run -p 8080:8080 -e GEMINI_API_KEY=<your-key> nato-taxonomy
```

Then open <http://localhost:8080>.

## Documentation

| Document | Description |
|---|---|
| [Configuration Reference](docs/CONFIGURATION_REFERENCE.md) | All environment variables: LLM providers, embedding model, admin password, database |
| [API Reference](docs/API_REFERENCE.md) | Complete REST API documentation with examples |
| [Deployment Guide](docs/DEPLOYMENT_GUIDE.md) | Docker, Render.com, health checks, troubleshooting |
| [User Guide](docs/USER_GUIDE.md) | End-user guide for the web interface |

## CI / CD

Every push triggers the **CI / CD** GitHub Actions workflow:

| Step | What happens |
|---|---|
| **Build & Test** | `mvn verify` — compiles, runs integration tests |
| **Publish Docker Image** | Pushes to GitHub Container Registry (`ghcr.io`) |
| **Deploy to Render** | Triggers a Render deploy hook (if secret is set) |

📋 **[Test Results Report](https://carstenartur.github.io/Taxonomy/tests/surefire-report.html)**
📈 **[Code Coverage Report](https://carstenartur.github.io/Taxonomy/coverage/)**

## MSSQL Compatibility

All entity classes are annotated for correct behaviour on Microsoft SQL Server:

- **`@Nationalized`** on every `String` field → produces `nvarchar` instead of `varchar`,
  preventing corruption of non-ASCII characters (e.g. German umlauts ä, ö, ü, ß).
- **`@Lob`** on text fields that may exceed 4000 characters (`descriptionEn`,
  `descriptionDe`, `reference`) → produces `nvarchar(max)` / `ntext` on MSSQL.
- **`@Lob` + `FloatArrayConverter`** on `semanticEmbedding` fields in `TaxonomyNode`
  and `TaxonomyRelation` → stores embedding vectors as streamable BLOBs using
  little-endian IEEE 754 serialisation.

The application continues to use HSQLDB by default (no MSSQL setup required).


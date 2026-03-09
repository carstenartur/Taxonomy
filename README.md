# Taxonomy

[![CI/CD](https://github.com/carstenartur/Taxonomy/actions/workflows/ci-cd.yml/badge.svg?branch=main)](https://github.com/carstenartur/Taxonomy/actions/workflows/ci-cd.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Taxonomy/coverage/badge.json)](https://carstenartur.github.io/Taxonomy/coverage/)
[![Tests](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Taxonomy/tests/badge.json)](https://carstenartur.github.io/Taxonomy/tests/surefire-report.html)

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

## Architecture Intelligence

Describe a business requirement in plain text and the application automatically:

🏗️ **Generates an architecture view** — identifies relevant taxonomy nodes, propagates
relevance through known relationships, and builds a structured architecture model

📐 **Exports to industry standards** — one-click export to **ArchiMate XML** (for tools
like Archi, BiZZdesign, MEGA), **Visio diagrams**, and **Mermaid flowcharts**

🔍 **Recommends missing elements** — gap analysis and semantic search suggest additional
nodes and relations that may be relevant to your requirement

> See the [Architecture Description](docs/ARCHITECTURE.md) for technical details on the
> generation pipeline and system design.

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
| [Architecture Description](docs/ARCHITECTURE.md) | System design, generation pipeline, CI/CD, database |
| [Configuration Reference](docs/CONFIGURATION_REFERENCE.md) | All environment variables: LLM providers, embedding model, admin password, database |
| [API Reference](docs/API_REFERENCE.md) | Complete REST API documentation with examples |
| [Deployment Guide](docs/DEPLOYMENT_GUIDE.md) | Docker, Render.com, health checks, troubleshooting |
| [User Guide](docs/USER_GUIDE.md) | End-user guide for the web interface |


# Research Overview

Taxonomy Architecture Analyzer is a research-oriented software prototype for turning requirements into traceable architecture views. It combines a layered architecture taxonomy, AI-assisted hierarchical scoring, relation discovery, graph exploration, export formats, and repository-backed architecture versioning.

## Research problem

Architecture knowledge is often distributed across requirements, catalogues, diagrams, documents, and informal decisions. This makes it difficult to answer questions such as:

- Which architecture layers are affected by a new requirement?
- Which taxonomy elements are directly relevant and which are only propagated through dependencies?
- Which relations should be proposed, reviewed, accepted, rejected, or exported?
- How can architecture work remain auditable and reproducible when AI is used as an assistant?

The project explores how an architecture-analysis workbench can make these steps explicit and inspectable.

## Core idea

Instead of matching a requirement directly to final leaf nodes, the analyzer performs hierarchical scoring:

1. Score root architecture categories.
2. Distribute relevance into child nodes.
3. Preserve intermediate scores as part of the explanation trace.
4. Derive architecture impact elements and relations.
5. Let users inspect, review, export, and version the result.

This treats the taxonomy as an architectural reasoning structure rather than as a flat keyword index.

## Contributions

The current prototype contributes:

- a GUI-first architecture-analysis workflow for requirements-to-architecture mapping;
- hierarchical scoring traces from root categories to intermediate and leaf nodes;
- multi-layer architecture impact views with anchors, hotspots, propagated relevance, and relation traces;
- an architecture DSL with JGit-backed versioning, branching, merge, rebase, cherry-pick, and history-oriented workflows;
- export paths for architecture artefacts, including ArchiMate, Visio, Mermaid, JSON, Markdown, HTML, and DOCX;
- documentation for deployment, AI transparency, digital sovereignty, security, and reproducibility.

See [Architecture Contributions](docs/research/architecture-contributions.md) for details.

## Research questions

The project can support evaluation around questions such as:

1. Does hierarchical scoring produce more useful explanations than flat keyword or vector matching?
2. Can generated impact views help users identify affected architecture layers more quickly?
3. How well can users trace AI-assisted suggestions back to taxonomy paths and relation evidence?
4. Which export formats are most useful for handoff into existing architecture-management workflows?
5. How can repository-backed architecture workspaces improve reproducibility and auditability?

## Evaluation status

The repository contains implemented functionality and documentation suitable for initial qualitative and technical evaluation. A full empirical user study is not yet included. The proposed evaluation plan is documented in [Evaluation](docs/research/evaluation.md).

## Reproducibility

The project is designed to be reproducible at several levels:

- source-code release through GitHub and Zenodo;
- explicit software metadata in `CITATION.cff`, `codemeta.json`, and `.zenodo.json`;
- Maven-based build instructions;
- Docker-based deployment instructions;
- optional local/offline LLM provider mode;
- documented architecture examples and export formats.

See [Reproducibility](docs/research/reproducibility.md).

## Citation

Please cite the archived software release when using this project in research. See [CITATION.md](CITATION.md) and [CITATION.cff](CITATION.cff).

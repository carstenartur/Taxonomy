# Research Overview

Taxonomy Architecture Analyzer is an open-source workbench for turning requirements
and source documents into reviewable architecture proposals. Its intended users
include public authorities and larger enterprises worldwide, with planning and
procurement of goods, services and organizational changes as application domains.
It combines hierarchical catalogue analysis, relation evidence, human review,
exports and versioned architecture models.

## Research problem

Architecture knowledge is often distributed across requirements, catalogues, diagrams, documents, and informal decisions. This makes it difficult to answer questions such as:

- Which architecture layers are affected by a new requirement?
- Which taxonomy elements are directly relevant and which are only propagated through dependencies?
- Which relations should be proposed, reviewed, accepted, rejected, or exported?
- How can architecture work remain auditable and reproducible when AI is used as an assistant?

The project explores how an architecture-analysis workbench can make these steps
explicit and inspectable. Clearer requirements, components and responsibilities
could reduce repeated coordination and make decisions easier to explain. These
benefits, including any cost or time savings, are research goals rather than
established field-study outcomes. The current C3 catalogue and each domain's
concept coverage remain part of the evaluation.

## Core idea

The analyzer traverses the selected catalogue scope hierarchically:

1. Assess selected root categories and relevant paths within the configured budget.
2. Retain intermediate assessments, reasons and unresolved work.
3. Search for requirement-scoped relationships when the selected analysis mode includes that stage.
4. Build reviewable architecture proposals and clarification questions from the retained evidence.
5. Let users inspect, correct, export and explicitly adopt or version the result.

Unvisited nodes are not negative findings. Technical completion of a run does not
establish semantic completeness of its architecture proposal.

This treats the taxonomy as an architectural reasoning structure rather than as a flat keyword index.

## Contributions

The current prototype contributes:

- a GUI-first architecture-analysis workflow for requirements-to-architecture mapping;
- hierarchical scoring traces from root categories to intermediate and leaf nodes;
- multi-layer architecture views with requirement-scoped relation evidence, alongside the separately documented anchor/propagation view path;
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
6. How do model capability, input quality and human correction affect the usefulness and completeness of architecture proposals?
7. Which parts of planning and procurement benefit from shared requirements, components, responsibilities and decision history across projects?

## Evaluation status

The repository contains implemented functionality, deterministic scenario evidence
and documented validation boundaries. Prepared model responses demonstrate the
software paths they exercise; they do not establish the quality of live model
output. A full empirical user study is not yet included.

Functional development can use a smaller real generative model plus targeted
response fixtures. Architecture quality is evaluated separately on common cases
with recorded model/settings and human review. A more capable model may improve
results without being a prerequisite for developing persistence, review, exports
or distributed execution. The [evaluation plan](docs/research/evaluation.md)
describes these separate evidence levels.

## Reproducibility

The project is designed to be reproducible at several levels:

- source-code release through GitHub and Zenodo;
- explicit software metadata in `CITATION.cff`, `codemeta.json`, and `.zenodo.json`;
- Maven-based build instructions;
- Docker-based deployment instructions;
- self-hosted generative models through a compatible endpoint, with model-specific capacity and licensing;
- separate opt-in local ONNX embeddings for similarity scoring/search;
- prepared-response fixtures for repeatable software checks, distinct from live-model evaluation;
- documented architecture examples and export formats.

See [Reproducibility](docs/research/reproducibility.md).

## Citation

Please cite the archived software release when using this project in research. See [CITATION.md](CITATION.md) and [CITATION.cff](CITATION.cff).

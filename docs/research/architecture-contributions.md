# Architecture Contributions

This document describes the research and engineering contributions of Taxonomy Architecture Analyzer from an architecture-analysis perspective.

## 1. Hierarchical requirement-to-taxonomy scoring

Many search-based architecture tools treat catalogues as flat collections of terms. Taxonomy Architecture Analyzer instead keeps the hierarchy visible:

- root categories receive relevance budgets;
- intermediate nodes receive their own scores;
- leaf results remain connected to the path that produced them;
- explanations can therefore show how a requirement narrows from abstract architecture areas to concrete elements.

This makes the scoring trace itself an artefact for architecture discussion.

## 2. Architecture impact view

The analyzer turns scoring results into an architecture impact view. The view is intended to answer practical architecture questions:

- which layers are touched by a requirement;
- which nodes are direct anchors;
- which nodes are hotspots;
- which nodes are included through propagation or enrichment;
- which relations are proposed between architecture elements.

The result can be inspected in the GUI and exported for follow-up work.

## 3. Traceable relation discovery and review

Relations are not only generated as final edges. They are represented as reviewable proposals with source, target, relation type, relevance, and derivation context. This supports a human-in-the-loop workflow in which architecture suggestions can be accepted, rejected, refined, exported, or versioned.

## 4. Architecture DSL and versioning

The project includes a text-based architecture DSL and JGit-backed persistence. This allows architecture states to be treated like versioned artefacts:

- branches and workspace variants;
- merge, rebase, and cherry-pick workflows;
- history and version navigation;
- readable diffs for architecture model evolution.

The combination of DSL and repository semantics supports reproducible architecture analysis and auditability.

## 5. Multi-format architecture export

The system supports export paths into multiple formats, including ArchiMate XML, Visio, Mermaid, JSON, Markdown, HTML, and DOCX reports. This is important because architecture work often crosses tool boundaries: research prototypes, public-sector documentation, enterprise architecture repositories, and lightweight Markdown workflows can all require different artefact forms.

## 6. GUI-first explainability

The README and user documentation emphasize GUI workflows. This is intentional: the research contribution is not just a REST service, but an interactive workbench where users can inspect scored trees, impact maps, relation tables, graph views, and exports.

## 7. Deployment and governance documentation

The project also documents operational and governance aspects that influence research adoption:

- security and deployment hardening;
- data protection and AI transparency;
- digital sovereignty and local/offline operation;
- public-administration readiness;
- SBOM generation and supply-chain transparency.

These topics matter when architecture-analysis tools are evaluated outside a purely local demo environment.

## Limitations

The project is a prototype and should be evaluated with realistic architecture tasks. Current limitations include:

- dependence on the quality and coverage of the selected taxonomy;
- variability of LLM-assisted scoring when cloud providers are used;
- need for broader empirical evaluation with architecture practitioners;
- need for benchmark datasets and repeatable comparison baselines.

These limitations are addressed in the evaluation and reproducibility documents.

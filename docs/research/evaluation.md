# Evaluation

This document proposes an evaluation approach for Taxonomy Architecture Analyzer. It distinguishes implemented capabilities from future empirical validation.

## Evaluation goals

The evaluation should determine whether the tool helps users move from requirements to architecture understanding in a more traceable and reviewable way than ad-hoc document analysis or flat search.

The main goals are:

1. assess the usefulness of hierarchical scoring traces;
2. assess the quality and explainability of architecture impact views;
3. measure whether relation proposals reduce manual architecture-mapping effort;
4. compare export artefacts with existing architecture-management workflows;
5. verify that results can be reproduced from a specific release, configuration, and input.

## Suggested evaluation tasks

A practical study can use requirements such as:

- integrated clinical communication platform;
- secure public-administration case-processing system;
- cross-department reporting dashboard;
- crisis-management coordination platform;
- knowledge-conservation workflow for architecture documentation.

For each task, participants can be asked to identify affected architecture layers, relevant taxonomy nodes, and required relations.

## Baselines

Potential baselines include:

- manual taxonomy browsing;
- keyword search over the taxonomy catalogue;
- vector search without hierarchy-aware scoring;
- LLM-only free-text answers without inspectable scoring traces;
- existing enterprise-architecture tooling where available.

The comparison should focus on traceability and architectural usefulness, not only retrieval precision.

## Metrics

### Technical metrics

- build success from a clean checkout;
- test success with `mvn test` and `mvn verify` where Docker is available;
- export validity for ArchiMate, Visio, Mermaid, JSON, Markdown, HTML, and DOCX;
- stability of local/offline provider runs;
- response time for representative taxonomy sizes.

### Architecture-analysis metrics

- number of correctly identified affected layers;
- number of relevant taxonomy nodes found;
- quality of root-to-leaf explanation paths;
- precision of proposed relations;
- recall of relevant relations;
- number of manual corrections required;
- usefulness ratings from architecture practitioners.

### Human factors

- perceived explainability;
- confidence in accepted relation proposals;
- ability to justify exported architecture artefacts;
- ease of comparing architecture versions;
- usefulness of GUI views compared with REST-only workflows.

## Study design

A mixed-method design is recommended:

1. **Technical reproducibility check**: build, test, run, and export from a release archive.
2. **Expert walkthrough**: architecture practitioners inspect one or more predefined scenarios.
3. **Comparative task study**: participants solve architecture-mapping tasks using the analyzer and one baseline.
4. **Qualitative interviews**: collect feedback on explainability, trust, usability, and missing features.

## Data collection

For each scenario, store:

- the input requirement;
- the taxonomy version;
- provider configuration;
- generated scores;
- accepted and rejected relations;
- exported artefacts;
- commit or release identifier;
- participant feedback where applicable.

No private or sensitive input data should be required for public reproducibility examples.

## Current status

The repository provides the implementation, documentation, and examples needed for an initial technical evaluation. A full peer-reviewed empirical evaluation is not yet included. This file is therefore a plan and checklist for future validation rather than a claim of completed user-study results.

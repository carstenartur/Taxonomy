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

## Separate software behavior from model quality

Use three complementary kinds of evidence:

| Evidence | Suitable execution | What it can establish |
|---|---|---|
| Functional correctness | Prepared/recorded responses and explicit malformed or interrupted cases | Tested state transitions, human review, versioning, export and recovery behavior |
| Live integration | A smaller real generative model with bounded representative inputs | Whether the configured model/server can complete the tested contracts and workflows |
| Architecture quality | Common scenarios with selected models and human assessment | Completeness, contradictions, supported reasoning, usefulness and correction effort |

A smaller model can support most application development even when its proposals
need substantial correction. Size development resources for the necessary
response format, context and latency. Better architecture quality is a separate
evaluation question; a successful software milestone need not achieve the best
available model quality.

Keep functional and semantic results separate in reports. Authored model playback
is not a live inference result. A stronger model's measured quality does not
transfer automatically to a smaller one, and neither result establishes capacity
or failover behavior on a different deployment.

See [provider guidance](../en/AI_PROVIDERS.md#development-and-result-quality),
[scenario evidence](../testing/scenario-acceptance.md) and
[reformulation verification boundaries](../testing/requirement-reformulation.md).

## Suggested evaluation tasks

A practical study can use requirements such as:

- integrated clinical communication platform;
- secure public-administration case-processing system;
- cross-department reporting dashboard;
- crisis-management coordination platform;
- knowledge-conservation workflow for architecture documentation.

These examples cover particular workflows. The intended planning and procurement
scope also includes goods, services and organizational change in public
administration and larger enterprises. Additional authored cases could examine a
vehicle fleet or building operations, with explicit assumptions about needs,
components and responsibilities. Validate their mapping to the current catalogue
before claiming domain coverage or successful field use.

For each task, participants can be asked to identify affected architecture layers,
relevant taxonomy nodes, required relations and unresolved responsibilities.
Public or authored inputs can support development and initial evaluation without
access to an organization's internal ERP or procurement systems.

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
- results from the documented Maven verification profiles, with selected scope and missing prerequisites recorded; see [Maven Verification Authority](../dev/MAVEN_VERIFICATION.md);
- export validity for ArchiMate, Visio, Mermaid, JSON, Markdown, HTML, and DOCX;
- stability of self-hosted generative runs, embedding-only checks and authored playback, reported separately;
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
- provider, exact model revision where available, quantization and serving runtime;
- prompt/settings versions, context/output limits and whether responses are live, recorded or authored;
- generated scores;
- proposed, accepted and rejected relations, unresolved questions and human corrections;
- exported artefacts;
- commit or release identifier;
- participant feedback where applicable.

No private or sensitive input data should be required for public reproducibility
examples. Keep the input, catalogue and evaluation rubric fixed when comparing
models, and retain differences in settings and resource limits. Include cases
not used to tune prompts; report semantic quality separately from latency and
resource use.

## Current status

The repository provides the implementation, documentation, and examples needed for an initial technical evaluation. A full peer-reviewed empirical evaluation is not yet included. This file is therefore a plan and checklist for future validation rather than a claim of completed user-study results.

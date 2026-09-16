# Workspace graph assessment after authority purification

This is the bounded documentation and evidence deliverable for Slice E of [#1043](https://github.com/carstenartur/Taxonomy/issues/1043), within #628. It classifies the remaining measured dependencies; it changes no runtime behavior or architectural enforcement.

The source snapshot is `bd430ff41788289567de65d78d7d8b8f4adc02a8`, containing reviewed D5b and the module extraction gate. The historical clean native architecture run for that snapshot passed 137 tests across 13 classes, including 111 fixtures and current source/compiler/import inventory checks. It classified 1,408 production classes. The checked package baseline has 140 edges and 472 distinct class pairs. Workspace has no outgoing dependency on another managed context; knowledge retains 117 distinct class pairs into workspace. The full proposed-module graph is still cyclic, even though the measured workspace candidate has no extraction blocker. No physical feature module exists.

## Required deliverable

Produce a concise assessment document and an exhaustive structured class-pair inventory. Every measured `workspace -> knowledge|architecture|portfolio` pair and reverse `knowledge -> workspace` pair must be represented, including an explicit empty result where appropriate. Classify each remaining pair as `owner-api`, `composition-orchestration`, or `residual-violation`, with concrete source evidence and a reason. These are analysis categories, never waivers or a replacement for the ratchet.

An owner API is a workspace-owned contract, neutral identity/value, or cohesive public authority boundary appropriate for its consumer. Composition orchestration coordinates request/application identity and other-context behavior at an outer boundary. A residual violation reaches implementation or persistence authority that should be supplied by an owner API or composition. A target being concrete, or a caller being a controller, alone does not determine the category: inspect the responsibility and actual dependency use.

The existing provisional 117-pair classification is input for comparison only; its 85/25/7 category counts are not requirements. Inspect every actual pair and explain any changed classification. Keep exact class identities, source paths, useful member/location evidence, and self-consistent counts.

The document must distinguish workspace readiness from whole-proposal acyclicity and from merge approval. Record remaining composition/ownership work and the identity semantics it must preserve. In particular, `WorkspaceContext.SHARED` names literal `draft` and is not equivalent to a configured primary default branch. Preserve lazy JPA seed-listener resolution and its built-in-only/fail-closed behavior in any future design. This assessment does not implement that future design.

## Constraints

- Change documentation and its structured evidence only; no production/test implementation, POM, selector, workflow, baseline, context map, exception, coverage floor, schema or review-policy change.
- Regenerate the canonical package baseline from the current compiled source inventory and compare it exactly with the checked file. If it is already exact, leave it unchanged.
- Enumerate all remaining measured pairs exactly once and use only the three specified categories. Never hide a pair to make the result look ready.
- Do not proceed to physical Maven extraction until the proposed module DAG is acyclic. Do not create a feature module or claim #628/#1043 complete.
- Current-head external review and main-targeted canonical CI, database, JGit consumer, UI, security, product and recovery gates remain required. No agent may post a human review attestation.

Only the assessment and its data will be proposed for review. Existing source, tests and mandatory architecture rules remain the authority for runtime behavior and enforcement.

## Provenance clarification — 16 September 2026

This document describes the immutable `bd430ff41788289567de65d78d7d8b8f4adc02a8` assessment, not current extraction status. Run 35156105650 regenerated that exact source graph with one executed graph test; report and policy hashes match the assessment. All 117 classifications are preserved. Current-head CI and review are independent requirements.

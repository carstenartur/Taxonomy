# Local Embedding Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reproducibly compare three actual local encoders on identical catalogue and reference inputs, and report an evidence-based default decision.

**Architecture:** A test-side Java ONNX harness loads one immutable bundle at a time. The app test module supplies the existing catalogue loader, formatter and independent reference fixture. Maven owns opt-in selection and report generation.

**Tech Stack:** Java 25, Maven, ONNX Runtime 1.21.1, DJL tokenizer 0.40.0, Apache POI, JUnit.

**Spec:** docs/superpowers/specs/2026-11-27-embedding-comparison-design.md

## Global Constraints

* No production default, index dimension, threshold or query-specific heuristic changes.
* Reference fixture unchanged; real pinned CPU inference and identical texts for all models.
* Primary comparison uses the production formatter and a common 128-token budget.
* Candidate provenance, community conversion, licence and corpus limitations remain explicit.
* Ordinary builds do not load extra models. Maven selects this explicit comparison.
* Raw results live under target/; retain concise findings and reproduction steps in docs/testing/.

## Review Focus

* A missing or corrupt bundle fails the requested comparison rather than skipping or fabricating a result.
* Jina's 768 dimensions and absence of token-type input work without changing production 384-dimensional contracts.
* Pooling excludes padding and validates finite nonzero output before ranking.
* All 16 cases, including negative and ambiguous cases, stay visible even when results are poor.
* Repeated runs use the same model pins, tokenizer inputs and measured runtime policy.

### Task 1: Controlled model comparison and measured report

**Files:**
- Create: taxonomy-app/src/test/java/com/taxonomy/acceptance/LocalEmbeddingComparisonTest.java and small test-side helper(s) as needed
- Create: `taxonomy-app/src/test/resources/local-embedding-comparison/model-assets.tsv` as the authoritative asset manifest
- Reuse unchanged: taxonomy-app/src/test/resources/scenarios/local-onnx-multilingual-v1.json
- Modify: pom.xml and/or taxonomy-app/pom.xml for a local-embedding-comparison opt-in profile, plus .mvn/verification-suites.json
- Create: docs/testing/european-embedding-comparison.md with pins, reproduction, measured findings and limits

**Interfaces:**
- Consumes local assets beneath the directory set by `taxonomy.embedding.comparison.models`. The checked-in `model-assets.tsv` pins repositories, revisions, paths and SHA-256 for `minilm/`, `jina/` and `narratic-onnx/`, plus `narratic-original/1_Pooling/config.json` and `narratic-original/sentence_bert_config.json`. The reproduction guide's download loop provisions every listed file. The harness also consumes the actual workbook and existing reference loader.
- Produces: taxonomy-app/target/local-embedding-comparison/report.json and a Maven test failure if evaluation cannot execute faithfully.

- [x] Add behavioral tests for exact ranking, padding-aware pooling, corrupt/missing inputs and result accounting; demonstrate failures before implementation.
- [x] Implement test-side encoder and comparison using pinned bundles identified in the recon report.
- [x] Register Maven opt-in execution, preserving default test selection.
- [x] Run the real three-model CPU comparison and inspect all per-case results and latency/memory evidence.
- [x] Document the predetermined decision rule, measured outcome, narrow corpus limits and the retained production default.
- [x] Commit locally and provide the report for independent review.

The measured results and limitations are retained in [the comparison report](../../testing/european-embedding-comparison.md). Independent review recomputed the report metrics and fixture/asset identities. The checked-in `model-assets.tsv` is the authoritative asset contract; the reproduction loop provisions its original-model metadata files as well as the three runtime bundles.

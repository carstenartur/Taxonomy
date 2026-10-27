# Independent Embedding Index Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for this bounded correction.

**Goal:** Permit generative LLM analysis and local ONNX search in the same process.
**Architecture:** Remove chat-provider selection from the existing embedding-index
initializer; preserve its enable flag, worker guard and verified readiness states.
**Tech Stack:** Java 25, Spring Boot, JUnit, Mockito, Maven Wrapper.
**Spec:** `docs/superpowers/specs/2026-10-27-independent-embedding-index-design.md`

## Global constraints and review focus

Keep disabled embeddings, worker-only operation, model/index failures and repeat
startup events safe. Do not change LLM selection or add flags, workflows or remote
provider calls. Existing tests cover those lifecycle boundaries.

## Task 1: Decouple startup and document mixed operation

- [x] Replace the provider-rejection test in `LocalOnnxIndexInitializerTest` with
  Spring-wired readiness assertions for `CUSTOM_OPENAI`, `GEMINI`, `LOCAL_ONNX`
  and an unset provider; observe the existing guard reject the first two and unset.
- [x] Remove the provider field, constructor argument and guard from
  `LocalOnnxIndexInitializer`; update direct constructor callers in existing tests.
- [x] Verify the initializer, search-facade readiness, worker isolation and real
  index-coverage tests through Maven, then run the required broader verification
  and report environmental prerequisites separately from successful checks.
- [x] Update paired provider and custom-LLM guidance; check links and formatting.
- [x] Obtain an independent review and prepare the bounded change for a PR on main.

Focused command: `./mvnw -B -pl taxonomy-knowledge -am -Dtest=LocalOnnxIndexInitializerTest,SearchFacadeEmbeddingStatusTest,CatalogueWorkerIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test`.
Required canonical command: `./mvnw -B verify -Pci -DrunOnnxTests=true`.

Local verification: the old guard produced three expected readiness failures;
the corrected focused run passed 25 cases. The broader selected reactor run
passed 65 cases, including 11 real HSQLDB/Lucene index checks, provider selection,
help rendering and documentation links. Canonical CI was attempted but model
provisioning could not reach the environment's proxy; its retries were stopped.
This local evidence does not claim full CI or real ONNX inference acceptance.

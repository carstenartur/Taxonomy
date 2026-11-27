# AI Model Transparency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display configured and actually loaded AI models, their provenance and runtime state in About without triggering model work.

**Architecture:** A read-only snapshot is owned by the embedding service. A composition service joins it with safe analysis-provider configuration; the existing About response and UI consume the snapshot.

**Tech Stack:** Java 25, Spring Boot, DJL/ONNX, Thymeleaf, modular JavaScript, Maven.

**Spec:** docs/superpowers/specs/2026-11-27-ai-model-transparency-design.md

## Global Constraints

* Retain the current default model and its pinned download contract.
* No inference, downloads, external provider calls or index queries from About.
* Configured profile and verified loaded artifact identity are distinct.
* No secrets or operator filesystem paths in the public response.
* Preserve module directions, context paths, existing About focus behavior and DE/EN support.
* All verification entry points remain Maven-owned; raw evidence belongs under target/.

## Review Focus

* An overridden local model must not inherit the default model's licence or origin as verified facts.
* An enabled but unloaded model must not be called loaded or ready.
* Opening About with invalid provider settings must still show build information.
* Long or hostile model strings must render as text without mobile overflow.
* A second About opening after a failure must not display a stale successful runtime state.

### Task 1: Runtime AI information and visible About section

**Files:**
- Modify: taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/LocalEmbeddingService.java
- Modify: taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/EmbeddingModelProfile.java
- Create: narrowly scoped runtime snapshot/provenance type in the same catalog.service package as needed
- Create: application composition AI information service under taxonomy-app/src/main/java/com/taxonomy/composition/about/
- Modify: taxonomy-app/src/main/java/com/taxonomy/shared/controller/AboutController.java
- Modify: taxonomy-app/src/main/resources/static/js/shared/taxonomy-about.js
- Modify: taxonomy-app/src/main/resources/templates/index.html (About pane only)
- Modify: taxonomy-app/src/main/resources/i18n/messages.properties and messages_de.properties (about.ai.* keys only)
- Test: focused embedding snapshot and application service tests; extend AboutControllerTest; registered browser/JavaScript behavior tests.

**Interfaces:**
- Consumes: retained EmbeddingModelIdentity, EmbeddingModelProfile, read-only LlmProviderConfig configuration methods.
- Produces: `ai` property on GET /api/about with independent embedding and analysis sections; typed JSON records with explicit status strings. UI must tolerate absent optional data.

- [x] Write failing behavior tests for snapshot/provenance distinction, side-effect freedom, invalid provider and safe About presentation.
- [x] Run the focused Maven tests and record the expected failures.
- [x] Implement runtime metadata and read-only composition; extend About and DE/EN UI.
- [x] Run focused tests and relevant existing About/context-path/browser contracts.
- [x] Run broader Maven gates and inspect desktop/mobile rendering.
- [x] Commit implementation and concise verification findings, then obtain independent code review.

## Integrated verification

The integrated Java source passed 105 selected knowledge, analysis and application tests on 27 November 2026. This includes six embedding snapshot cases, eleven provider-composition cases, six Spring About endpoint cases and the existing module-boundary check. Ten additional real Spring/ONNX cases passed with the pinned local MiniLM bundle; the loaded-artifact assertion verified its upstream identity and model digest after native inference.

The Maven-owned About and navigation browser selection passed all 62 cases with Chromium 149 and the pinned Bootstrap 5.3.8 stylesheet. The selection includes DE/EN, the About pane at desktop and 390 px, failed/missing responses, literal rendering of hostile model text, context-prefixed URLs and focus restoration. The previously recorded German 320 px navigation timeout passed in this integrated run without changing its assertions or timeout. Desktop/mobile captures were inspected during the task review.

These focused results supplement the repository's full CI, browser, database and packaging gates. Those gates are recorded on the pull request's exact commit. The separate overlapping-About-response browser regression remains a minor coverage follow-up; the production request-generation fence was independently reviewed.

The full CI exposed three unrecorded dependency-map entries for the new About composition service. Independent review verified that all 282 existing entries and counts match the generated map. The added entries record `com.taxonomy.composition.about` reading analysis DTOs (three class pairs), analysis configuration services (two) and retained knowledge runtime metadata (five). These follow the declared application-composition direction and introduce no reverse context dependency. Recording precisely these entries leaves the policy, source coverage and assertions intact. The unchanged `ArchitectureContextDependencyRatchetTest` reproduced the failure and then passed all 36 cases after the reviewed baseline update.

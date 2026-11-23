# Rancher startup and persistent search implementation plan

> **For agentic workers:** Execute the authorized changes with focused regression tests and a final independent code review.

**Goal:** Serve login and the application shell while catalogue/search preparation runs, report actual progress and failures, and safely reuse durable indexes.

**Architecture:** Keep HTTP availability separate from catalogue and semantic-search readiness. Catalogue completion drives embedding preparation through events. Validate persisted search documents against their authoritative data before reuse; repair only the affected entity scope. Retain the existing database-backed, repository/workspace/branch-specific graph generations.

**Tech stack:** Java 25, Spring Boot, Hibernate Search/Lucene, Thymeleaf/JavaScript, Kubernetes/Rancher and Helm.

**Spec:** Carsten's 22 November startup request: early login, visible indexing progress, persistent indexes and verified branch isolation, including an existing Rancher workload configured without Helm.

## Constraints and decisions

- The application must not modify a live Rancher cluster without a connection and concrete deployment configuration.
- An image update does not apply repository Helm templates; deliver a usable existing-workload patch and exact environment settings.
- Preserve authentication, authorization and server-side feature guards during initialization.
- Show measured phase counts when available and indeterminate progress otherwise; never invent percentages or a fixed completion time.
- Keep local development/test initialization synchronous by default; enable asynchronous initialization by default in the Kubernetes profile, with the existing environment override.
- Reuse the existing branch generation ownership and atomic publication protocol. Do not invent a new branch index directory or mix users' workspaces.
- Filesystem Lucene requires one writer per volume. A single-replica persistent example uses Recreate and an operator-selected PVC.

## Review focus

1. A one-thread asynchronous executor must not deadlock waiting for an initialization task queued behind itself.
2. An index with the right row count but stale content, wrong scope or an interrupted build must not be declared ready.
3. Failure must remain visible in the UI; temporary network failures must be retried without enabling analysis.
4. Direct pod probes must respect the actual servlet context path, independently of ingress rewriting.
5. Restarting an unfinished branch index build must not replace another repository/workspace/branch generation.

## Tasks

- [x] **1. Catalogue and embedding lifecycle.** Extend `AppInitializationStateService` with an atomic snapshot and real progress. Validate/rebuild catalogue indexes in `TaxonomyService`; chain `LocalOnnxIndexInitializer` from completion events. Test partial/stale coverage, reuse, failures and constrained executors.
- [x] **2. Application shell and progress.** Remove catalogue loading from `TaxonomyController.index`. Extend `/api/status/startup` with a consistent phase/progress response. Improve the startup banner and polling in `taxonomy-browse.js`, with German/English text, accessible progress and failure state. Verify the existing server feature guards and readiness routes.
- [x] **3. Kubernetes and Rancher.** Enable asynchronous initialization in the Kubernetes profile. Add `deploy/rancher` patch and instructions; keep HTTP readiness independent of background catalogue work, including the Helm Artemis web deployment. Validate rendered or parsed configuration.
- [x] **4. Branch persistence.** Exercise an interrupted database-backed generation across actual database reopen and recovery, while unrelated scopes remain searchable. Audit history and navigation isolation and fix only demonstrated gaps.
- [x] **5. Verify and publish.** Compile the current reactor; run focused Java, UI and deployment checks plus relevant existing regressions. Review the complete diff independently, publish a focused PR, and report the exact verified state and deployment step.

## Execution record

- Base: `80b8c942062a4d207b849b7e7a930ee4bb9a6fd0`; existing PR #1588 remains separate while its checks run.
- Initial audit: the home controller reads the entire tree solely to set an unused `rootCount`; the ONNX initializer polls catalogue readiness on the shared executor; persisted catalogue initialization does not validate the full-text index before declaring ready.
- Initial branch audit: graph generations already persist as checksummed database archives, with repository/workspace/branch ownership and token-fenced publication. Navigation changes a scoped cursor rather than Git refs.
- History audit found a separate retained-index defect: nonempty database projections skipped all search verification. Recovery now validates exact IDs, content, tenant routing and sortable timestamps; controlled diagnostic bypass remains explicit.
- Independent review reproduced two additional races: optional vector refresh could overwrite a concurrent ORM update/delete; periodic AI-status callbacks could release the startup/active-analysis button gate. Both require dedicated regressions before publishing.
- Reviewed architecture change: the existing `OperationsApiController` composes startup snapshots. Its exact package dependency counts are catalogue service 3→5, search 0→3 and versioning service 0→2. These read-only application-to-domain dependencies introduce no reverse edge; the dependency baseline records only these reviewed changes.
- Test ownership: the actual application shard catalogue assigns `AsyncStartupAvailabilityIT` to `core`; the existing CI Failsafe inventory requires its report. No new exclusion or unowned test was added.


## Final verification

- Current production reactor: `./mvnw -o -B -ntp -pl taxonomy-app -am test-compile -Dmaven.test.skip=true -Dmaven.build.cache.enabled=false` — success. The test-compile phase attaches the existing workspace test-fixture artifact needed by the application; test compilation is deliberately skipped for this production-compilation check.
- Maven-owned JavaScript execution: `JavascriptContractTest` ran 719 selected readiness, startup and navigation callbacks; `JavascriptSourceOwnershipTest` ran 2 checks. All 721 passed with no skips.
- Focused JUnit execution against current classes: 91 knowledge/index tests; 26 history recovery/lifecycle tests; 21 branch persistence/isolation tests; 43 login/status/health tests; the reviewed architecture dependency ratchet passed. Some branch checks also appear in the knowledge run; counts are not additive.
- Real HTTP acceptance: `AsyncStartupAvailabilityIT` passed with actual Spring Boot, an explicitly blocked workbook read, login/shell and both probes available, catalogue API returning 503, and the real catalogue becoming available after release. The loaded workbook contains 2,572 nodes, eight roots and 37 relations.
- Deployment checks: Helm lint/render/package and invalid-configuration checks passed, including all 36 existing Node checks. Local kubectl strategic-merge verification preserved the unrelated image, Secret, resources, sidecar and temporary volume.
- Independent read-only review: the live-write vector race, AI-status button override and diagnostic-switch wording are resolved; no remaining findings. The final vector run also verifies endpoint changes, reader refresh, optional vector degradation and prepared-scope cleanup.
- Limits: the complete local Java test compilation exceeded available memory, so targeted Java runs used an isolated JUnit launcher and bounded JVMs. Native inference uses deterministic fixtures in the index tests. Hosted whole-repository CI, production performance and a live Rancher rollout are separate acceptance steps; no cluster was changed.

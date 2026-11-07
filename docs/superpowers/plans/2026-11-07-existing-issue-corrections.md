# Existing issue corrections — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement each task with recorded verification.

**Goal:** Correct concrete authority and lifecycle defects identified in the existing Taxonomy issues, retaining each issue's remaining requirements until verified.
**Architecture:** Reuse the current analysis continuation authority, named API transport, stable-principal service and versioned template repository. Introduce no second identity, analysis, transport or Git storage system.
**Tech Stack:** Java 25, Maven, JUnit, Spring Security, JGit/jgit-storage-hibernate, existing JavaScript/native-browser owners.
**Spec:** GitHub issues #1354, #626, #746, #785, #786, #787 and #828; authoritative bodies captured on 7 November 2026.

## Global constraints

- Work starts at main b8961b53e6d0e962f9e31e4f33d1a4e0f2fe64d1 on a separate branch.
- Existing tests, authorization, CSRF, source identity, immutable history and supported database dialects remain required.
- No paid LLM calls, production deployment or additional Actions test workflow.
- A partial correction references its issue; only complete, executed acceptance can close one.
- The pending 1.5.3 publication requires its exact parent. Implement independently and do not invalidate that request by merging ahead of it.

## Review focus

- Replayed operations must retain the original actor/source/operation even with an omitted or unrelated request ID.
- A successful workspace mutation must preserve the old wrapper's reconciliation/reload behavior when routed through the named client.
- Retained sessions must not retain authority after current principal disablement; LEGACY ownership compatibility must not silently change.
- Different application instances must observe the same WebDAV authority; expired leases cannot admit a second writer during an in-flight commit.
- Git success followed by uncertain response or lease bookkeeping failure must preserve acknowledged content and immutable history.

## Task 1: Continuation replay identity (#1354)

Files: existing AnalysisApiController and AnalysisApiControllerTest / ClusterAnalysisApiControllerTest.
Consumes: existing persisted continuation replay DTO.
Produces: response X-Analysis-Operation-Id derived from the restored operation on both local and clustered replay paths.
- [ ] Add omitted-header and conflicting-header regressions in both modes, with body/header identity agreement and no second dispatch.
- [ ] Observe failure on the unchanged implementation.
- [ ] Return the persisted identity without relaxing foreign-context/actor checks.
- [ ] Run the complete affected JUnit owners and inspect nonzero reports.

## Task 2: Named workspace/context transport (#626, #746)

Files: existing taxonomy-api-client.js, named API client modules, index.html, taxonomy-workspace-sync.js, taxonomy-context-bar.js and their existing frontend/JUnit owners.
Consumes: current TaxonomyApiClient and immutable workspace scope.
Produces: named workspace/context client methods with unchanged HTTP semantics.
- [ ] Enumerate productive workspace/context requests and record the workspace fetch-wrapper side effects.
- [ ] Reproduce missing CSRF/error/context or lost switch reconciliation through real feature clients.
- [ ] Route the identified calls through existing transport and explicitly retain successful-switch reconciliation.
- [ ] Shrink the existing direct-fetch exceptions; extend actual wrapper/alias detection where needed.
- [ ] Run affected and complete JavaScript contracts through existing Maven/JUnit ownership.
- [ ] Keep #626/#746 open unless their full endpoint/journey acceptance is satisfied.

## Task 3: Retained-session current authority (#785, #786, #787)

Files: existing principal identity/security composition and real security-chain tests.
Consumes: current PrincipalIdentityService and configured LOCAL/STABLE identity mode.
Produces: request-bound validation of current principal/account/binding authority before protected application effects.
- [ ] Reproduce a retained authenticated session continuing after current account/principal/binding disablement.
- [ ] Reuse current identity resolution and fail closed without logging credentials.
- [ ] Preserve browser CSRF, protocol ordering, LEGACY ownership compatibility and stateless bearer validation.
- [ ] Test disable/re-enable, missing/foreign/ambiguous identity and current-authority store failure using actual chain owners.
- [ ] Run focused JUnit owners. Record remaining migration, repository-policy and queued-worker scope.

## Task 4: Shared WebDAV edit authority (#828)

Files: taxonomy-templates WebDAV lock manager/store/servlet/configuration, owned schema composition, existing WebDAV and shared-repository tests, DE/EN operations documentation.
Consumes: existing versioned DocumentTemplateService upload and per-template expected-version CAS.
Produces: shared atomic lock acquisition/refresh/expiry/release and a cross-instance write fence; no buffered edit-session system.
- [ ] Reproduce two independent application lock managers accepting the same resource or bypassing a foreign lock.
- [ ] Use the configured shared database for transient lease authority, with database-clock expiry and transactional fencing across the bounded existing Git upload.
- [ ] Preserve exact resource/token/actor binding, independent-template ETags, restart behavior and durable per-PUT acknowledgement.
- [ ] Test concurrent acquire/refresh/release, expiry during write, rollback, restart, stale ETags and uncertain HTTP acknowledgement against independent persisted Git handles.
- [ ] Run the module owners and required database/native HTTP gates; update setup guidance to describe the executed behavior.

## Integration and remaining queue

- [ ] Review the complete diff independently; fix blocking findings with regressions.
- [ ] Publish one reviewable PR for the integrated correction set and run current-head canonical CI.
- [ ] Wait for existing release publication/exact-parent constraints before merge.
- [ ] Continue with original-commit provisioning (#1356), leased repository recovery (#742), exact-context indexes (#741), complete identity migration/policy and demo/cluster acceptance.
- [ ] Subsequently migrate the remaining Python adapters (#629), then address explicitly deferred feature/measurement issues according to their recorded acceptance.

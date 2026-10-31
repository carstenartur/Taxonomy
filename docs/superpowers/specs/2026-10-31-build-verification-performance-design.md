# Build verification performance design

## Intent and approval

Implement the five improvement areas approved in the conversation on 2026-10-31: measured application-test redistribution, a lean coverage join, targeted Spring fixture improvements, a conservative documentation/draft verification mode, and effective caches/packaging. The current successful baseline is CI run 41216530274 on source tree e1e8d19e8d9be57ae98f3b448cd01233a6515842: 81m05 overall, 75m44 core, 57m04 application module, 3m47 coverage join.

## Architecture

Keep Maven as the single owner of selection, build and verification. Extend existing profiles, the verification catalogue, and the existing CI workflow. Explicit classes move to isolated runtime and domain-integration groups; unassigned tests remain core. Preserve source commit/tree, artifact digests, original JUnit outcomes and complete aggregate coverage.

A documentation mode is limited to ready pull requests whose complete diff is an explicit, reviewed prose allowlist. It checks the changed documentation and exact packaged documentation, publishes distinctly scoped evidence and never represents old coverage as fresh verification. Unknown, empty, malformed, mixed or non-PR changes use full verification. Public user-help changes remain full scope initially. Draft PRs run planning/fast checks in the main verification workflow, and expensive PR-triggered jobs are skipped. They cannot satisfy the required final gate; ready_for_review resumes the selected verification. Independent push-triggered CodeQL source analysis retains its existing routing, including source pushes to branches with draft PRs; push events do not carry PR draft state.

Coverage replay consumes verified class/execution outputs and retains the fresh coverage policy test while eliminating repeated compilation, packaging and ordinary test execution. Missing/changed data must still fail.

## Implementation decisions

- Runtime group: ten measured container/ONNX classes; preserve pinned model provisioning, runOnnxTests and PostgreSQL coverage.
- Domain integration group: 45 measured classes listed in the audit JSON; preserve the related inheritance-family ownership.
- Spring optimizations: remove only demonstrably unnecessary full context/fixture work in selected slow tests, retaining production integration boundaries and state isolation. Keep context cache size 1 and browser JVM isolation.
- Caches: distinguish dependencies, build outputs, models and container layers. Add cross-run Docker layer reuse with safe scope; include standalone Maven POMs in dependency keys; remove unused browser restore; reduce unnecessary Maven archive writes/history and record cache effectiveness.
- Packaging: retain complete security/SBOM outputs at their authoritative producer, avoid generating them again for consumers that need only the early UI artifact.
- Keep the stable required check name Maven verification; full verification is required on main, tags and manual release workflows.

## Alternatives and tradeoffs

Global test threading/context-cache enlargement was rejected because current JGit/HSQLDB/browser state requires isolation. Blanket Markdown workflow exclusion was rejected because documentation is shipped in the JAR and required checks must still report. Reuse of old successful test reports under a new commit was rejected. Isolated class groups increase runner setup work but reduce the critical path while preserving exact test ownership.

## Acceptance

Focused negative and positive tests cover scope classification, missing/mismatched evidence, coverage replay without duplicate tests, complete shard selection including ONNX/Postgres, and cache key/build arguments. Existing product test inventory and coverage policies remain satisfied. One consolidated PR receives a fresh independent review and full exact-head CI; measured timings are reported without promising a theoretical speedup.

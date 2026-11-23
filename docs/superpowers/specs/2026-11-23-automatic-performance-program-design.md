# Automatic analysis performance programme

## Approval and purpose

Carsten approved implementation on 23 November 2026 with "Mach das" after the source-based proposal covering runtime SQL/catalogue work, compact continuation data, lossless result storage, model batching and bounded concurrency. Existing explicit authorization includes fixing, publishing and merging the resulting PRs. Execute the programme in small reviewed PRs without another generic permission round.

Base: main `135de85effa06398732fb340fc01f1903172038a`. The controlled prior measurement and source investigation are in `docs/qa/2026-11-23-automatic-continuation-performance.md` and the conversation. The ordinary production default is local transport with automatic source continuation. The long reference acceptance test intentionally disables automatic continuation; its 254 boundaries are not a production workload count.

## Goals

1. Reduce redundant database requests and repeated materialization in the normal automatic path.
2. Reduce bytes read/written and objects allocated for large durable results; preserve exact historical evidence and external export formats.
3. Use compact, validated continuation inputs wherever current integrity and authority can be proved.
4. Remove avoidable aggregate reconstruction in distributed relation work.
5. Overlap independent, already admitted model questions and evaluate small complete decision packets, preserving quality for modest models.
6. Verify reductions on actual application/driver paths and report provider time separately from application and test-harness work.

## Global constraints

- Java 25; preserve compiler warning policy and existing supported PostgreSQL, HSQL, Oracle and SQL Server behavior.
- Preserve tenant/project/repository/workspace/branch scoping, claim tokens, lease expiry, provider and catalogue binding, fresh cancellation/PAUSED checks and current question state.
- Persist ATTEMPT and budget debit before physical HTTP; persist each accepted reply independently after HTTP. Never hold a database transaction or row lock across provider I/O.
- Preserve accepted-answer replay, uncertain outcomes, deterministic scarce-budget prefixes and historical protocol versions. Do not promise exactly-once HTTP across the crash-before-commit gap.
- Do not reduce architecture coverage, evidence, relation-type/direction/quote checks, assertions, budgets, timeouts, heap guards or CI/security/coverage gates.
- No unbounded caches, thread counts or event replay. CPU work defaults to available processors; model requests also obey provider permits and explicit resource limits.
- Existing backups, REST outputs and document exports retain full evidence. A new internal storage format must have bounded validation and an explicit compatibility/rollout rule.
- Raw equal-length payload changes, malformed payloads and resource cleanup failures remain detectable on every existing unprotected storage path.
- No full-table production rewrite, privileged DB mutation, server deployment or external endpoint reconfiguration is implicit in the code work.
- No assisted-by footers. Keep PRs independently reviewable and far below 10,000 changed lines.

## Approaches and decisions

The user selected the source-grounded incremental programme. We will retain Spring/Hibernate/JGit, existing providers and the current application boundaries. A framework rewrite, arbitrary budget increase, global stale cache or reduced verification is not part of this work.

### Runtime stage

Use narrow database projections already protected by row locks and scope predicates; carry facts already returned by the same transaction. Obtain operation-specific job data without hydrating the entire project history. Preserve authoritative scheduler validation before cluster result writes, including detection of an altered stored grant. Omit the complete report construction that persistence currently discards. Deeper incremental scheduling needs independent admission proof and remains a separate stage.

### Storage stage

A checksum column alone cannot prove mutable LOB content unchanged. PostgreSQL OID payloads and JDBC locator writes can change bytes without updating the owning row. A portable zero-read projection therefore cannot preserve the present unprotected-store contract. First apply the existing bounded lossless payload packing to supported portfolio reads/writes, hash the complete actual stored representation and preserve a legacy fallback for large/incompressible values. Evaluate lossless reference encoding for exactly equal relation-report and coverage copies independently; compare final packed size, not just raw JSON. Durable projections or immutable backing require a separate explicit integrity proof; do not advertise zero-LOB reads until it exists.

### Catalogue stage

Reuse a verified materialization within one exact server-owned execution identity, while preserving independent authority/content validation at claim and execution boundaries. Keep the tested model-less TOCTOU check. Never replace a historical catalogue with the current browser catalogue or share mutable per-run trees globally.

### Model stage

Preserve singleton domain decisions. First overlap unchanged VERIFY questions only when the entire block, including bounded correction calls, is admitted. The owner controls budgets, queues and ordered result publication; workers have isolated provider/observer scopes and durable answer completion. Keep cluster workers' thread-bound diagnostic sessions isolated.

A packet protocol must version and persist its group schedule, attach each target to its original complete proposal, validate every ID and claim, correct only failed IDs and retain genuine unresolved answers. Cross-root decisions belong to the common frozen preparation stage; a target worker still loads only its own root. Historic admissions keep their original protocol. A session relay tests fresh integration, not a weak-model performance/quality claim. Activation on a model profile needs corresponding quality evidence.

## Delivery sequence

1. Runtime SQL/status and admitted distributed-result validation (first small PR).
2. Safe per-attempt catalogue reuse and measured automatic-workload instrumentation.
3. Compact portfolio/journal representation and lossless shared payloads, covering every direct reader and backup boundary.
4. Bounded local overlap and packet protocol with replay/budget/quality regression gates.
5. Remaining incremental scheduling/cursor changes supported by measured work amplification, then complete end-to-end evidence and documentation.

Independent design investigations may run concurrently. Implementers are sequential in the current worktree. While an earlier PR's hosted checks run, the next isolated branch can proceed from the verified commit without changing the earlier head. Merge each only after exact-head gates and review; rebase/restack only when required by actual changes.

## Acceptance and measurement

Use focused real-HSQL statement/row assertions for database work, controlled driver A/B for storage, latch-based actual overlap for concurrency and gold relation/evidence tests for protocol changes. Use the normal automatic configuration separately from the manual reference scenario. Track physical provider attempts, tokens, SQL/transaction counts, bytes, allocation, wall time and output equivalence. Retain no sensitive raw production prompts in public evidence. Full hosted browser/database/security/coverage gates remain the release gate.


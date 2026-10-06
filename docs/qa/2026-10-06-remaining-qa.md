# Remaining QA implementation — 2026-10-06

Base: `1de21a0a202b4f7f92ce0dbcee6afeb8237697b7` (`main`, includes PR #1174).
Implemented and packaged source: `f40a75a79da4745148485a136a1f3b73a8430be3` on
`qa/remaining-20261006`. The later evidence-only documentation commit does not
change the tested application or test implementations.

## Changes and acceptance boundary

| Area | Implemented result | Evidence boundary |
| --- | --- | --- |
| Preferences | Late GET, PUT and reset responses preserve field edits made while the request is pending, including edits away and back. Acknowledged server state and Save availability remain consistent. | Reproduced regressions, actual inline-script contracts and a real Chromium workflow pass. The original deployment-specific analysis-loss cause in #1100 remains unconfirmed. |
| Local semantic retrieval | Pinned multilingual MiniLM replaces English-only BGE as the default; BGE remains an explicit profile. Model downloads validate all five pinned artifacts. Indexed vectors carry their actual model/configuration identity, and all five KNN paths filter by that identity. | Native pooling, frozen-worker parity, mixed-index isolation and real packaged REST evaluation pass. Additional paraphrase/ambiguity misses remain recorded. |
| Authorization and logging | Added real-controller authorization/isolation matrices and reconciled the exact CodeQL baseline against the complete main scan. Removed three obsolete logging exceptions; retained three reviewed authorization fingerprints. | Existing production authorization/logging behavior was already corrected on main. New tests strengthen regression protection; the final branch still needs its own CodeQL scan. |
| Verification infrastructure | Corrected profile-aware fixtures, the worker footprint model pin and nested `@Value` environment-alias discovery. Updated provisioning, model caches and bilingual documentation. | Regression tests preserve existing assertions, security severity rules, selectors and coverage thresholds. |

Detailed findings: [Preferences](2026-10-06-preferences-state.md),
[authorization/logging](2026-10-06-security-contracts.md),
[multilingual retrieval](../testing/local-onnx-multilingual-retrieval.md) and
[model provisioning](../testing/multilingual-model-provisioning.md).

## Verification performed

All Maven runs used Java 21 and the repository wrapper. Build-cache restoration
was disabled for the clean verification, resumed verification and final assembly.
The local runs below are complementary evidence; no single uninterrupted full
`verify -Pci` run is claimed.

| Check | Observed result |
| --- | --- |
| Focused security, logging, Preferences and SARIF contracts | 168 Java tests passed across 13 classes, no failures/errors/skips. Includes 57 authorization and 61 bulk HTTP cases. |
| Complete Maven-owned Node contracts | 832 passed, including 25 Preferences cases. |
| Maven-owned primary admin Chromium workflow | Earlier local execution: 37 checks, including 13 axe audits, passed, zero violations; the actual pending-save race and 150 → 1 node-limit reduction preserve working state. |
| Clean upstream reactor modules | Tooling 161, domain 232, DSL 332, extension API 2, export 387, workspace 891, templates 156, interoperability 193, knowledge 749 and architecture 223 passed. |
| Resumed analysis and portfolio modules | Maven summaries report 911 and 367 tests passed respectively after correcting the explicit model-profile fixture. |
| Native ONNX | Frozen-model parity and model criteria: 5 passed, including two native pooling checks; embedding service: 4 passed; REST endpoints: 5 passed. |
| Reformulation application/browser acceptance | Four local-Chrome browser cases, the complete browser scenario, backend scenario acceptance and two authored scenarios passed. |
| Worker model guards and real footprint probe | Four offline guard/launcher regressions plus the native footprint test passed. All six cold/native process configurations completed without relaxing consumer, filesystem, startup or safe-exit assertions. |
| Final reference/Preferences refresh | 37 tests passed: evaluator 14, cases 11, Preferences UI 2 and service 10. Full reactor `install` succeeded and produced the application JAR below. This was focused assembly, not a full CI run. |
| Coverage recovery | 2,129 tests, zero failures/errors, one locally optional Helm skip; all five aggregate thresholds met. |
| Build policy and critical packages | 391 unit cases: zero failures/errors, 10 Helm skips. Final combined IT reports: 12 cases, zero outstanding failures/errors, one opt-in report E2E skip. All aggregate/critical-package limits, dependency alignment, packaged dependency hygiene, frontend boundary and module packaging checks pass after the evidence recovery described below. |
| Existing complete main CodeQL output with reduced baseline | 412 Java/JavaScript findings processed: zero blocking, three exact accepted fingerprints, zero unused entries. This is main-scan evidence, not a new branch scan. |

The first default verification exposed stale knowledge-module bytecode; a clean
run removed it. The exact source of those stale outputs was not established.
The broad resumed application test JVM later hit the environment's 8 GiB memory
limit and exited with code 137, losing its parent JaCoCo data. Its earlier
configuration-discovery failure was fixed. Docker-only browser setup errors were
rechecked through the repository's supported `test-local` profile with matching
Chrome/ChromeDriver 138.0.7204.183 and unchanged assertions.

Remaining native/application tests were completed with bounded JVM heaps. A
separate coverage-only replay of the 288 completed in-process application
classes whose parent data was lost finished successfully: 2,129 tests, zero
failures/errors and the single locally optional Helm skip. It preserves existing
execution data and excludes 19 process harnesses only after inspecting their boundaries: 12 already
have surviving instrumented child sessions, six never instrument child-only
production execution, and the final matrix's exact parent helper is already fully
covered. This selection is a data-recovery step, not a replacement CI selector.
Deliberately killed producer JVMs and non-forwarding child JVMs have the existing
coverage limitations documented by that inspection.

Aggregate JaCoCo contains all 12 shipped-module groups and no class-ID mismatch
warnings. The unchanged aggregate limits are met:

| Counter | Measured | Required minimum |
| --- | --- | --- |
| Instruction | 87.83% | 81% |
| Branch | 73.19% | 64% |
| Line | 89.56% | 82% |
| Method | 90.65% | 85% |
| Class | 92.98% | 92% |

The first build-policy execution completed 391 unit cases, including 10 Helm skips, then
found two incomplete inputs in the split local verification: the application
Hibernate dependency tree had not been generated, and the security-service
package had only 87.00% line coverage against its unchanged 89% floor. The
existing `PrincipalMappingIT` had not yet run; all seven of its real-database
identity/mapping cases subsequently passed with JaCoCo enabled. Regenerating the
aggregate raised that package to 92.51% line and 78.86% branch coverage. Maven's
two existing application dependency-tree executions supplied the missing reports.
The two affected build gates then actually executed again and passed through the
configured Failsafe execution; all 21 critical package budgets pass. An earlier
direct-goal attempt inherited `skipITs=true` and is explicitly not gate evidence.
The SBOM companion was generated with vulnerability assessment `not-assessed`.

Useful commands for the completed local gate stages, with prerequisite reactor
artifacts and test data already present:

```bash
./mvnw -B -pl taxonomy-coverage -am org.jacoco:jacoco-maven-plugin:0.8.15:report-aggregate -Dmaven.build.cache.enabled=false
GITHUB_BASE_REF=main ./mvnw -B -pl taxonomy-build verify -Pquality -Dmaven.build.cache.enabled=false
GITHUB_BASE_REF=main ./mvnw -B -pl taxonomy-build -Pquality -DskipITs=false -Dit.test=HibernateSearchAlignmentPolicyIT,ReactorCoveragePolicyIT org.apache.maven.plugins:maven-failsafe-plugin:3.5.6:integration-test@default org.apache.maven.plugins:maven-failsafe-plugin:3.5.6:verify@default
```

These commands describe separate executions, including the initial failures and
focused recovery. The full canonical command remains
`./mvnw -B verify -Pci -DrunOnnxTests=true` in its provisioned CI environment.

The checkout is shallow. The unchanged changed-critical-source gate therefore
reserves diff coverage for canonical CI with the required Git history and base
revision; local aggregate/package results do not imply a diff-coverage pass.

The earlier local browser evidence has `sourceCommit=null` and
`applicationArtifactSha256=null`. The Preferences template was byte-compared with
its packaged copy (SHA-256
`d706a26ed70025a4ceeb8c79439d4684cca02b176ebdea9e2e991b183547e6cc`), and that UI
implementation remains unchanged. This narrower check does not establish a
commit/JAR-bound certificate for the whole UI shard.

One application setup contract and ten build-policy Helm cases were skipped
because Helm is unavailable. `DocumentTemplateReportDownloadIT` was also skipped
because its separate opt-in E2E profile was not enabled. Docker and the complete
UI matrix were not available here. The focused primary browser
execution reused the installed pinned Chromium after OS-library installation was
blocked by the runtime. None of those limitations is counted as a successful full
CI lane.

## Packaged real REST evaluation

The application JAR was copied privately after successful final assembly, and
both evaluators were compiled from the current source. The process bound only to
localhost, used real HSQLDB/catalogue import and the real CPU ONNX runtime, had
runtime model downloads disabled, and made no external provider calls. All
2,572 nodes were semantically ready; node and relation indexing finished in
`READY`. The original six query strings and catalogue bindings are unchanged.

| Evidence identity | Value |
| --- | --- |
| Application source | `f40a75a79da4745148485a136a1f3b73a8430be3`, clean at assembly/evaluation |
| Application JAR SHA-256 | `32fcb25a9a05713ef00e3395a7442c83520d5b08c16f48da2393a86940304a6d` |
| Original reference SHA-256 | `628c6b1821273d4e92337241dcb609e850dd48209a687aed38b0bb09b0bff5af` |
| Supplemental reference SHA-256 | `b1fdd3540cc2116cb68f4c381fe3bcbda4e2f1c7c38f6290cfbb79892842a0ca` |
| Canonical catalogue SHA-256 | `ad093001cee3944e131bdb1963b8e66966530bb43da5c74c7686f0b94b472459` |
| Model profile/revision | `MULTILINGUAL_MINILM_L12` / `e8f8c211226b894fcb81acc59f3b34ba3efd5f42` |

The original evaluator made 12 real search requests (six semantic, six full-text),
and the supplemental evaluator made 16 semantic requests with top-K 10.

| Cases | Measured result |
| --- | --- |
| Original EN/DE payroll, document editing and email | All six semantic references at rank 1. Full-text English references at rank 1; German email at rank 7, documents/payroll absent from top 10. |
| Independent paraphrases | Five of six references found: English payroll/documents/email ranks 1/4/1; German documents/email rank 1. German payroll remains outside ten hits. |
| Two artillery distractors | None of the three targeted office/payroll references appears in ten hits. This is a targeted observation, not overall precision. |
| Broad office ambiguities | English finds email at rank 1 but misses word processing; German misses both bound references. |

The original evaluator reports `MEASURED_REFERENCES_FOUND`; the supplemental
report remains `MEASURED_WITH_REFERENCE_MISSES`. Both executions completed without
technical errors. A repeat to correct process-ID namespace handling in the scratch
RSS monitor produced identical result identities/ranks; the first run's failed
memory capture is not presented as a zero-memory measurement.

The measured repeat reached node semantic readiness in 67.845 seconds and
completed the observed startup/evaluation run in 81.895 seconds. With `-Xmx768m`
and two active processors, 163 samples observed up to 1,540,372 KiB RSS; the
kernel high-water mark was 1,540,384 KiB (about 1,504 MiB). This is the whole
application process, including native memory, not model-only consumption or a
capacity guarantee. It was shut down after evaluation. The real worker probe
separately measured roughly 1,473 MiB RSS for its full-catalogue `role=all`
process; that configuration is not a worker-only memory measurement.

The two machine-readable REST reports and the final unmodified coverage gate
report are retained in `docs/qa/evidence/2026-10-06/` as
`local-onnx-reference.json`, `local-onnx-multilingual.json` and
`coverage-gate.txt`. They preserve measured misses as well as successful checks.

## Still required before release acceptance

- Run the final branch through the complete canonical GitHub CI, including
  Docker/database/Keycloak lanes, all UI profiles and branch-specific CodeQL and
  dependency/container scanners, plus changed-critical-source coverage with full
  Git history. Local source-policy and SBOM checks do not
  constitute a new vulnerability scan.
- Keep #1100's original deployment-specific state-loss investigation open until
  the served asset/source identity and original failure can be established.
  The observed Preferences edit race is a distinct, now tested correction.
- Keep the supplemental multilingual misses visible; six original references
  are a regression gate, not proof that arbitrary German requests are solved.

At the local handoff, no remote branch, PR, merge, deployment or issue closure
has been performed for these changes. Publication and the final CI remain pending.

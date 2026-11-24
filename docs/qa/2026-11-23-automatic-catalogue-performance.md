# Automatic catalogue workload and journal-write QA

**Candidate not promoted.** Revert `43d8a2b597905d188b20505b0c7b0c0c298a757b` restored the exact tree of baseline `da1a8b360dd8f636539ad06ab520a883063f8711`, tree `2fb36fd53cd93e474c01f78ec0c18f698bdad699`. The approved workload harness from `750256427677049e341bf8a0512fdc82a8a46565` remains in that tree. Candidate `d255803aa4bb4df6725e7694acdb088185d16306` reduced executed journal statements and worker allocation in both configurations. Default whole-worker times improved in two of three pairs. The bounded times leave a material regression risk unresolved.

The commit identifiers above refer to the retained local experiment history. The withdrawn candidate patch, source overlays and exact compiled runtime binding are preserved with the experiment. No journal production change is delivered by this record.

## Paired whole-workload results

Whole-worker wall time combines entry through snapshot transaction completion with the remaining worker interval. Submit-to-terminal also includes HTTP polling. Startup and the later shadow census have separate scopes. Deltas use the native measurements, so they can differ from subtraction of the displayed times by 0.001 seconds.

| Pair | Run order | Baseline worker (s) | Candidate worker (s) | Worker delta (s) | Worker delta (%) | Submit-to-terminal delta (s) |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Default 1 | Baseline first | 73.119 | 73.373 | +0.254 | +0.347% | +0.205 |
| Default 2 | Candidate first | 79.453 | 64.532 | −14.921 | −18.779% | −14.912 |
| Default 3 | Baseline first | 71.659 | 60.832 | −10.827 | −15.109% | −10.885 |
| Bounded 1 | Candidate first | 74.098 | 85.463 | +11.365 | +15.338% | +11.333 |
| Bounded 2 | Baseline first | 88.016 | 88.775 | +0.758 | +0.862% | +0.779 |
| Bounded 3 | Candidate first | 79.822 | 72.080 | −7.741 | −9.698% | −7.727 |

Each percentage is `100 × (candidate − baseline) / baseline` within its own pair. The reported median percentages are medians of those individual ratios. Default whole-worker median was **−10.827 s (−15.109%)** and bounded was **+0.758 s (+0.862%)**. Default submit-to-terminal median was −10.885 s (−14.917%) and bounded was +0.779 s (+0.873%). The sample variances of whole-worker deltas were 61.6352 s² for default and 91.6351 s² for bounded.

All twelve valid children completed, closed their databases and remain in the analysis. The incomplete default pair 2 baseline launch stopped during JVM initialization before application startup and yielded no timing row. The startup interruption and a CI work pause left 740 seconds between the retained candidate's exit and the replacement baseline's start. The pair remains in the summary with this extra exposure to between-run variation. Default pair 3 improved with the opposite ordering, and pair 1 was nearly flat. Bounded pairs 1 and 3 had opposite results despite both running candidate first. Bounded candidate startup was slower in all three pairs, and the first bounded candidate was also slower in some phases with equal work counts. These observations leave the cause of the mixed bounded timings uncertain.

The remaining comparisons were authorized to resolve the first bounded run's observed regression risk, with three valid pairs per mode in total, including the initial screen. The gate specified neither a default-only promotion criterion nor an acceptable bounded slowdown. The independent review found the bounded risk unresolved, and the experiment closed at the planned fork count.

## Physical work and recovery evidence

Real HSQL execution traces identified a same-question INSERT followed by UPDATE during baseline prepare. The candidate registered each fresh question after assigning its final prepare state. Focused traces showed one INSERT and zero question UPDATEs for ATTEMPT, SKIPPED, both input-limit failures and the subsequent fresh prepare after rollback. Managed historical retries and the separate distributed ownership flush retained their required updates. SKIPPED issued no owner UPDATE in either version. A real owner UPDATE with a substituted stale version affected zero rows and rolled back the flushed question and bookkeeping. Independent committed reads checked ATTEMPT, accepted evidence and distributed delivery ownership. The focused baseline run recorded five expected failures on the single-write assertion. The candidate's five selected suites recorded 64 cases with zero failures, errors or skips.

Every aligned full-run pair removed one JDBC batch addition and execution per fresh admission. Each count fell from 9,374 to 6,251 for default and from 9,391 to 6,265 for bounded. Same-mode query, row traversal, direct UPDATE, compatibility, capacity and commit counts agreed. The SQL trace identifies the eliminated statement independently of the batch counts. Whole-worker allocation fell in all six pairs. The paired median reductions were 164.6 MiB (1.457%) for default and 144.1 MiB (0.727%) for bounded. Private preparation also fell in all six pairs, with median reductions of 7.240 s and 4.148 s, respectively. Preparation, JDBC and transaction measurements overlap.

## Protocol, parity and quality

The harness submits through authenticated HTTP and follows the owned automatic worker through real scheduling, journalling, a DB-backed JGit model and snapshot persistence. Every child used a fresh copy of the same closed, hash-verified four-file HSQL seed with model commit `ad9c2cea7c2cc388953d2af988d9595655fbab91`. Bounded mode changed the authenticated source cadence from 128 to 16. Runs were sequential on Java 25.0.5+8 and HSQLDB 2.7.3 with nine effective CPUs and the normal 2,613,051,392-byte child heap. Packed snapshot writes were disabled. Both variants used the same compiled harness and runtime classpath after their variant overlay. Actual JVM class-load logs and file hashes verified the store and all seven nested classes from the selected overlay, shared harness entries and common HSQL driver.

All twelve children loaded the built-in analysis provider from the same frozen `taxonomy-analysis/target/classes/` directory. The complete retained tree contains 533 files and 5,228,091 bytes (5.23 MB). It independently reproduced the current `taxonomy.builtin.taxonomy-analysis@1.5.0` artifact SHA-256 `7c1ebdade9074c5268e7ee211f5b599567877773cff5aef2a1ed6b5cb7923492`. The older packaged reference used `52ece8e64cced0a0ed98f0f99193e08b7574a12d593cb773c4be78b312d14819`. Historical authority checks accounted for that packaging difference. Each current pair matched the complete current authority and independently verified admitted provider binding.

Within mode, all ordered rule IDs, prompt and reply hashes and text lengths agreed, with 3,123 exchanges for default and 3,126 for bounded. Complete normalized snapshots matched, including proposals, budgets, coverage, quoted evidence, mappings, unfinished states and the explicit operation-ID field. The fixture's genuine operation ID is null. A separate non-null regression witness proved a changed operation ID affects parity. Each child had one winning claim and a committed snapshot linked to its own job and recovery. Both modes matched their own corrected retained reference and retained their distinct evidence order and presence rationale.

Default ran one actual private search pass with zero source-cohort transitions. Bounded ran five private passes with four source-cohort transitions and retained accepted-prefix replay. Both reported 12 derived budget slices. The deterministic authored replies came from an interceptor at the provider client seam. The resulting measurements cover the application with stable provider responses. Native vLLM inference, token usage and alternate-model quality were outside the measurement.

Gold quality stayed `PASS_WITH_OPEN_POINTS` at `PARTIAL` / `COMPLETED_WITH_GAPS`. Both modes retained all 19 required and 17 allowed relations, 20 required coverage elements, zero false positives and the 210-item edge-evidence multiset. All 67 sources were assessed, with zero pending. The 10 unresolved searches, 14 unfinished batches and seven open relation bindings remain in the retained results.

## Other measured hypotheses

- **Catalogue reuse and progress rescanning.** The original diagnostic JFR attributed 65 of 2,623 primary-worker Java samples to catalogue work, and a full run made three catalogue captures. Progress snapshot work measured 0.351 s for default and 0.677 s for bounded despite millions of predicate visits. These observations left the broader ownership and scheduler changes deferred.
- **Compatibility index.** Original compatibility driver time was 2.155 s for default and 2.392 s for bounded. The index remains a conditional follow-up requiring query-plan, end-to-end, migration and restore evidence.
- **Navigation packets and overlap.** Each mode had 710 fresh adjacent true-leaf pairs, and their rendered combined requests occupied 16,011–25,063 effective budget units against a 16,000 limit. The local sibling census counted 16 structural VERIFY positions in the default run and 41 across the bounded run's five private passes. In each mode, 16 positions had two fresh primaries. The separate scheduler shadow found zero fully funded compatible multi-root groups. Encoded journal admission constraints remain unresolved. These observations did not advance packets or concurrent admission.
- **Lossless snapshot references.** The separate storage experiment reduced the combined reference frame by 28.75% versus fast v1. Conservative mixed operations regressed, and the direct policy's small median gains fell within control variation while tails, CPU, allocation and cold reads worsened. The storage stage retained v1 with its writer opt-in. Its isolated measurements and the earlier runtime changes have separate QA records. [Packed snapshot QA](2026-11-23-packed-portfolio-snapshots.md) · [Continuation runtime QA](2026-11-23-automatic-continuation-performance.md)

## Reproduction and retained evidence

`AutomaticCatalogueWorkloadTest` defaults to the authenticated BR-only `SMOKE` path in the ordinary CI test suite. The full default/bounded matrix used the archived seed, explicit class overlays and frozen directory binding. Its commands, manifests, source patch, checks, `matrix-results.md`, `matrix-summary.json` and per-pair native records are retained with the experiment. The original single-run baseline screens and JFR are diagnostic provenance, held separately in the 243 MB `taxonomy-automatic-baseline-evidence-2026-11-23.zip`.

The journal evidence set is organized as `taxonomy-journal-default-evidence-2026-11-23.zip` and `taxonomy-journal-bounded-evidence-2026-11-23.zip` for the closed database states, with `taxonomy-automatic-performance-record-2026-11-23.zip` for the reproducibility and review record. The experiment records local file-HSQL timings under the settings above.

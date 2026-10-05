# Taxonomy QA — 2026-10-05

## Scope and source

Reviewed baseline: `4263e6db42b37530a8a9911c8054e2fb455f120e` on `main`.
This pass covers cluster result integrity, proposal HTTP validation and tenant
boundaries, sensitive service logging, template Git read costs, UI follow-up
guards, and the existing Maven/UI verification gates. It uses deterministic
provider fixtures; no remote paid inference was invoked.

## Findings and fixes

| Finding | Reproduction | Correction and regression evidence |
|---|---|---|
| Cluster aggregation discarded worker assessment/descendant coverage. | A two-root result lost unknown nodes and coverage after persistence and cancellation. | Merge immutable worker coverage, reject duplicate node identities, and retain it through the durable result round trip. Missing/error-empty roots contribute explicit unknown nodes from their admitted frozen snapshot, including descendants. Source identity is checked against the admitted authority. |
| Partial runs could trigger global follow-up analysis. | `PARTIAL` results with absent, incomplete, or zero-failure coverage invoked gap/pattern/recommendation/Copilot requests. | The existing UI guard also checks the analysis status. Scores remain available; derived requests are blocked with the existing visible warning. |
| Mixed old/new cluster evidence could become an invalid v3 export. | A covered root plus a legacy scored root failed `SavedAnalysis.validateCoverageEvidence()`. | Keep aggregate coverage absent if a scored worker result is legacy; do not invent its assessment history. Both all-legacy and mixed exports have regression coverage. Preserve status through the HTTP score export/import round trip independently of coverage. |
| Diagram exports could lose a known partial status when coverage was absent. | Mixed legacy/current evidence produced an unlabelled diagram. | Carry optional analysis status through architecture-view creation, restored views and browser exports; label partial diagrams without inventing assessment counts. |
| Invalid later bulk IDs could mutate earlier proposals and then fail the request. | HTTP `[42,"bad"]` caused the first fixture proposal to be accepted before a cast exception; fractional/overflow IDs were narrowed silently. | Validate the complete list before mutations. Accept exact positive signed-64-bit JSON integers; reject decimal/boolean/string/object/list/overflow values. Existing null-item failures remain ordered per-item results. |
| Bulk review ignored explicit `If-Match`, and malformed keys escaped as server errors. | A stale header was accepted; malformed idempotency input threw instead of returning 400. | Honor the optional existing strong ETag contract, validate headers/keys before mutation, stop at conflicts, and omit obsolete ETags if a branch disappears. Legacy callers without the header retain their existing behavior. |
| Private identifiers and exception contents entered lifecycle logs. | 21 log-capture tests found seeded private values in relation, hypothesis, and DSL materialization events. | Log bounded operations, counts, scopes and error codes. Do not pass raw throwable content to the after-commit warning. Existing writes and tenant scope are unchanged. |
| Template version discovery repeatedly walked Git history per item. | A 12-template fixture needed 90 commit reads for both cold and unchanged lists, 14 for a warm read, and 203 for warm depth-1 PROPFIND. | Resolve versions in one first-parent walk and keep a per-repository-handle, 1,024-entry cache keyed by immutable HEAD and template ID. Preserve individual ETags, cross-handle freshness, negative lookups and eviction. |
| Security route coverage omitted the real bulk endpoint. | The authorization test listed bulk subroutes but not `/api/proposals/bulk`. | Add the actual route to the ordinary-user mutation-denial test. |
| Oversized external idempotency keys could overflow projection/recovery columns after Git mutation. | 129/255/256-character keys were accepted by bulk and single proposal review; the per-item suffix increased their size further. | Bound external keys to 128 visible ASCII characters before mutation, retaining room for bulk suffixes. Keep the optional legacy fallback and apply the same boundary to required-header Git mutation routes. |
| Interrupted backup restoration discarded committed assessment coverage. | Real exported archives restored scores but lost modern coverage; foreign frozen source identities and duplicate unknown nodes escaped coverage validation. | Reuse the live coverage policy before target writes. Resolve unknown nodes against the original archived source, including when the destination authority changes; preserve the legacy compatibility policy. |
| Opening a large architecture graph made its nodes almost invisible. | With the actual 2,564-node catalogue, initial nodes measured about 1.35 CSS pixels wide on desktop and 0.57 on a 390px viewport. Selected mobile nodes had 5px-high labels. | Use readable initial framing and selection focus based on the rendered SVG scale. Desktop/mobile nodes measure 238 × 82 CSS pixels with 16px-high labels. Explicit full-model fitting, complete export geometry and the 200-node/400-edge rendering limits remain unchanged. Tiny/hidden viewport controls prevent invalid scales. |
| Docker-free browser scenarios could not observe actual downloads with Headless Shell. | The existing minimal `BrowserSessionIT` timed out, as did the architecture SVG and reformulation JSON scenario downloads. | Explicitly configure the real browser download directory through CDP in the local test adapter. Release the started driver if configuration fails. The same unmodified download/content/lifecycle test then passes; container configuration and product endpoints are unchanged. |
| Repeated browser sessions collided with existing download evidence. | A second local scenario run overwrote `architecture.svg` but waited for a new filename; a focused real-browser regression exposed the prior `report.json` before any new download. Selenium's binary container transfer also rejects an existing output file. | Isolate each local session's download inventory and stage container transfers before copying retrieved files into the evidence directory. Two successive sessions with the same filename must each start empty and retrieve their own distinct response content. Other evidence and per-session originals are retained. |

## Template read measurements

Counts come from instrumented real Git object reads in the deterministic
12-template regression fixture, not wall-clock benchmarks or production claims.

| Operation | Commit reads before | Commit reads after | Tree reads after |
|---|---:|---:|---:|
| Cold list | 90 | 13 | 98 |
| Unchanged warm list | 90 | 1 | 74 |
| Warm current read | 14 | 2 | 8 |
| Warm depth-1 PROPFIND | 203 | 25 | 566 |

The existing bounded DOTX materialization cache already avoided repeated package
creation. It was retained. A real service/codec-spy regression verifies that
repeated PROPFIND does not repack unchanged templates and a changed template is
repacked once. A Git history edge with a non-directory template root was also
reproduced and corrected without changing first-parent version semantics.

## Verification

All defect groups had failing behavioral regressions before their fixes. Focused
iterations used Java 21, the repository Maven wrapper, disabled local build-cache
reuse, and skipped aggregate SBOM generation only for those focused iterations.
The complete verification below uses the normal SBOM and test selections.

| Gate | Result |
|---|---|
| Independent source review of all changed behavior | All concrete review blockers resolved, including legacy export round trips and preservation of partial/open diagram evidence. |
| Full UI contract command: `cd .github && npm run verify:ui-contracts` | 795 tests passed; no failures or skips. |
| Focused reactor regression run through `taxonomy-app -am test` | 111 tests passed; no failures, errors or skips. Includes all new Java regressions and adjacent scope/lifecycle tests. |
| Focused export/status follow-up reactor | 78 tests passed; no failures or errors. Includes HTTP score exchange, frozen finalization, legacy workbench diagrams and unchanged open-coverage projection. |
| Focused interrupted-restore follow-up | 52 tests passed; no failures, errors or skips. Includes nine new archive cases, live aggregation, finalization, relation coordination and durable observation. |
| Focused proposal key-boundary follow-up | 54 tests passed; no failures, errors or skips. Covers single/bulk mutation boundaries, valid maximum keys, exact suffix identity and legacy compatibility. |
| Expanded Git HTTP mutation boundary follow-up | 101 tests passed; no failures, errors or skips. Covers all five required-header mutation routes and existing proposal/relation controller contracts alongside the optional legacy routes. |
| Reviewed architecture inventory follow-up | 57 tests passed; no failures, errors or skips. The unchanged dependency ratchet, cycle boundary and exception ledger all pass. |
| Packaged editor browser follow-up | 17 checks passed with no page errors. Includes the actual USER editor acceptance, eight real-D3 renderer combinations, hidden/30px controls, selection focus, explicit Fit, unchanged export scene, keyboard behavior and Axe. The real catalogue is checked at 1440px and 390px; the initial red run had nine intended geometry failures. |
| Docker-free browser runtime and download follow-up | Eight `BrowserSessionTest` cases and both real `BrowserSessionIT` cases passed. Failing-before evidence covers missing downloads, stale evidence across sessions and the container transfer's existing-file rejection. Original assertions and deadlines are unchanged; the additional browser test checks two successive downloads with distinct contents. |
| Full templates reactor: `./mvnw -B -pl taxonomy-templates -am test -Dmaven.build.cache.enabled=false -Dcyclonedx.skip=true` | 773 tests passed across the selected reactor; 156 in templates, including nine new regressions. |
| Full Docker-free reactor: `./mvnw -B clean verify -Ptest-local -Dmaven.build.cache.enabled=false` | Not certified: the initial run exposed the architecture inventory mismatch and then exceeded this environment's 8 GiB memory limit during the browser scenario. The ratchet is corrected and verified separately. Subsequent focused scenario and authoritative CI results are recorded in the associated PR description. |
| PR CI/CD, security and compatibility gates | Authoritative status is recorded by the associated PR's exact-commit checks. |

The local runtime supplies matching Chrome/ChromeDriver and a Mockito premain
agent because dynamic self-attachment is unavailable. The environment's proxy CA
is added to a local trust store; TLS validation remains enabled. These are local
prerequisites, not repository changes or relaxed assertions.
The local memory limit recorded an OOM kill; subsequent local JVMs are bounded to
fit that environment. No timeout, assertion or memory-acceptance threshold is
relaxed to compensate.

The architecture ratchet reported one intentional structural change, reviewed
independently before updating its exact count from 18 to 23. The new class pairs
are `ClusterAnalysisStore` and `ClusterAnalysisBackupRestorer` to
`RootCatalogueSnapshot`, and `ClusterAnalysisCoverage` to `RootCatalogueSnapshot`,
`RootCatalogueSnapshot.Node` and `CatalogueSourceIdentity`. These consume the
existing immutable catalogue snapshot contract to retain original source identity;
they introduce no new package direction, module dependency, reverse edge or cycle
exception. All 173 package edges remain checked by the unchanged ratchet.

## Boundaries

- `test-local` is the documented Docker-free gate. Container execution,
  PostgreSQL/Oracle/SQL Server, ONNX and the complete Playwright/accessibility
  matrix require their existing CI/profile evidence; local success is not a
  substitute.
- The scoped proposal HTTP tests use the actual review and state services with
  deterministic repository/Git boundaries. They do not claim live multi-database
  transactional acceptance.
- This reduces template read amplification; it does not make every PROPFIND
  payload/snapshot lookup constant-cost. Issue #839 remains broader.
- Logging tests cover the changed services. They do not certify every logger in
  the application or close the broader logging/security issue #857.
- This preserves durable result evidence and safe partial-result handling. It
  does not complete the broader continuation/HA lifecycle epic #808.
- Graph geometry checks measure rendered CSS pixels and viewport reflow. They do
  not certify native browser zoom or every possible display configuration.
- No security baseline, timeout, test assertion or CI gate was weakened. The
  reviewed architecture inventory adjustment above records the exact new class
  references. Unrelated backup/release work was not included.

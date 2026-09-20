# #1075 feasible completion evidence

Evidence snapshot recorded 2026-09-20 at 17:37 UTC, through local source
`dfc0de97f9b43089ada325c9b1a191134b9b7876` and the explicitly identified CI runs. The feasible Word, native semantic, conditional publication and
Visio slices are implemented. This record separates tested behavior from proprietary
product acceptance and remaining release verification. The chronological
[decision register](1075-completion-decisions.md) preserves the implementation
choices, rationale, risks and superseded decisions.

| Slice | Executed evidence and exact scope |
|---|---|
| Frozen Word reports | [PR 1088](https://github.com/carstenartur/Taxonomy/pull/1088), merge `085f620a5141de8fc26140137010b59414ddead1`, tree `b17e05137e761bc379e8d3c96856ab5b29d764b5`. Both DOCX routes contain the frozen graph, native inventories and provenance. The decision report additionally includes the complete linked decision tree/table; the standalone architecture report has its own navigation/TOC. All 116 local and 79 final CI pages plus five browser images were visually reviewed. Final [civilian CI 35507510981](https://github.com/carstenartur/Taxonomy/actions/runs/35507510981): 68 decision + 11 standalone pages, 344/85 checks, no empty body pages. Required CI accepted; CodeQL policy-skipped. |
| AM/XMI semantic v2 and native editing | [PR 1089](https://github.com/carstenartur/Taxonomy/pull/1089), merge `b45711981d8e1bdaf38642b4c3469ae28bd76a12`, tree `2695c87adbd0b1fcaedf992f49d663be1aca0a74`. Native packages/placements, exact endpoint and requirement mappings, shared AM/XMI features, scoped bounded collection traversal and real cross-JVM replay. Final source `fa0be677b2d4c63a23858f084ce3b79c00d61204` passed required core, UI, PostgreSQL/Oracle/SQL Server, civilian, document, security, JGit and Kubernetes gates. [Civilian CI 35515329213](https://github.com/carstenartur/Taxonomy/actions/runs/35515329213) used real EN/DE package edit/reload, no interception, 78 LLM playback calls and 17 verified export hashes. |
| Visio graphical completion | Accepted graphical source `9e0fb1b8a5d7a6612a16fa9bf5e88ed46b403c0a`; accepted byte-budget/loss fix `d3a1a272cc6fe87c52eee9e206a3ac64cfef9c26`, 69 final focused tests. All 11 actual HTTP/Draw pages inspected: 38 nodes, 44 canonical relations, 44 readable source→target/type captions. Eight dense-overview caption omissions are explicit and covered in detail pages. The unchanged aggregate 32 MiB package-part, 32-page, 20k-node and 60k-connector limits remain. The original graphic artifact is retained with its honest source identity; the fix changes no relevant graphic data in this fixture. [Detailed evidence](visio-graphical-quality.json). |
| Conditional publication foundation | Exact verified adapter policy, directed planner, durable state machine, independent completion guards and real HTTP receipts. Task 1: 43 focused tests; Task 2: 121 covering tests, followed by accepted correction `55e21da4b2bf2021a2d87e43d6da0597c922ac73` with 34 tests. The old two-client-JVM test had its provider inside the client; it is not substituted for the independent matrix below. |
| Publication API/UI/security | Browser-ready source `4c761f74f170a326cf55f6026fb2f22c1b01236c`, tree `7b941bc55f5bb5e7220e5345d61fec6232a3a7fd`: seven JVM and three DOM tests passed. Corrected source `dfc0de97f9b43089ada325c9b1a191134b9b7876`, tree `7c1ecbbf6aa564013f7469aff061d2305fbd7c69`: 23 functional covering tests passed; the sole intentional dependency-count mismatch was then corrected with 22 ratchet tests passing. Seven DOM regressions passed. Exact-state/branch guards, frozen reload/retry, output budget before effects, no file-route bypass, wrong actor/scope/provider, credential reflection, redirect/URI/size faults and generic 401/429/500 ambiguity are covered. |
| Independent process recovery | `IntegrationPublicationBoundaryRestartTest`: two tests, 12 observed steps, 12 forcibly terminated provider JVMs, 10 forcibly terminated client JVMs and two normally completed verified client replays. Genuine HTTP, persistent HSQL/Git and atomic provider resource/key/receipt storage; no domain-success mock, production crash hook or fabricated accepted journal. [Versioned evidence](conditional-publication-contract-v1.json). |

The accepted Task 2 correction's [lookup-only recovery, shared invocation budget,
durable late Git handling and evidence limitations](1075-completion-decisions.md#d44a--accepted-task-2-lookup-only-recovery-correction)
are retained permanently. Its final 34-test result and independent approval do not
explain the earlier corrected R1 failure; that historical cause remains unproved.
Inherited HSQL/Flyway/dialect and Lucene/JDK warnings are nonblocking, with no
connected Task 3 failure; verification output is not claimed warning-free.

## Process boundary matrix

| Boundary | Real observation and invariant |
|---|---|
| Plan commit | Push plan and local staging committed together before dispatch; no local semantic change. There is no separate committed-plan/pre-local-apply product window. |
| Dispatch intent | Persisted in-flight intent before HTTP mutation. Restart retains exact operation, plan, item key and bound request digest. |
| Provider commit before response | Independent provider saves resource and receipt atomically, then stops before sending the response. Both processes terminate; one resource effect survives. |
| Response before journal | Client receives the durable lookup receipt, then stops before persisting acknowledgement. Recovery reuses that receipt/key. |
| Partial result | First acknowledgement is committed; remaining items stay unattempted and COMMON remains absent. |
| Local apply before Git | Separate Sync scenario stops after the same atomic plan/local-stage transaction has really applied one typed local operation. |
| Git before acknowledgement | Real Git/editor checkpoint completes while the integration journal still records checkpoint-pending. Restart adds no duplicate Git or semantic effect. |
| All receipts before finalization | Every selected mutation is acknowledged; full-scope verification and COMMON are still pending. |
| Completed replay | Completion is committed before termination. A fresh client/provider pair replays the saved review and retry without extra resource, semantic or Git effects. |

Push retains one setup semantic operation and two setup Git commits throughout;
three remote resource mutations each occur once. Sync adds exactly one local semantic
operation and one Git checkpoint; its seeded remote element is not rewritten. The
fixture also has unchanged scope metadata, which is counted separately from mutations.
Controlled per-process clock offsets expire the real persisted 30-second claims;
no leases are rewritten. These are process-crash tests, not power-loss certification.

## Current browser and release verification

[Draft PR 1094](https://github.com/carstenartur/Taxonomy/pull/1094) publishes the
conditional-publication/Visio work. Actual [civilian CI 35524367930](https://github.com/carstenartur/Taxonomy/actions/runs/35524367930)
passed at CI source `33444cca584abce11e8e73ae7504ceb7596934f6`, exact tree
`7b941bc55f5bb5e7220e5345d61fec6232a3a7fd`. Artifact `10609229684` has SHA-256
`ce85bcc450b18e5edf3920b2f1099d1a3c9300f3cf73eaf4b813aca9fd8f7cdb`.
All 17 export and 90 rendered-page hashes were checked. Word retained graph
`5365c5d5fcae616a37e84ed3be15f3c62a2ad658a35e60c0bf2568dfc7f7c191`,
68 + 11 pages and zero extra LLM calls. All 11 LibreOffice 24.2 Visio pages from **35524367930**
were also visually inspected; all 44 direction/type captions remain readable.

All ten initial new browser images were inspected. Nine show the intended native
or publication state. The EN package image showed the wrong viewport and the
initial flow did not execute enabled reconciliation. Corrected capture source
`dfc0de97f9b43089ada325c9b1a191134b9b7876` reselects the saved package and verifies
that its controls are in view; it adds a real local change/checkpoint → Push →
explicit SKIP/PARTIAL → enabled reconciliation → linked successor preview, with
EN/DE captures and keyboard action. All 14 corrected images passed actual [civilian CI 35525893725](https://github.com/carstenartur/Taxonomy/actions/runs/35525893725)
and controller visual inspection at source `ca2e28dba4b258cf33db3261aa8449e832ebfb2f`,
exact tree `7c1ecbbf6aa564013f7469aff061d2305fbd7c69`. Artifact `10610101827`
SHA-256 `efa6fc1afd77579462c519f04c9d511b78370e1523471d0b1f79cc01d3263eed`
retains 78 LLM calls, 17 verified exports and 90 verified page images. The new
79 Word/11 Visio page images were hashed, not freshly viewed: unchanged render
dependencies permit reuse of complete prior Word acceptance and the Visio visual
review from 35524367930.
[The committed images and byte manifest](conditional-publication-browser.md) show
all package buttons, native endpoints, directed review, partial UNKNOWN, recovery,
enabled reconciliation and the actual linked successor in both languages.
This corrected tree is published as PR head
`4109815b6c045ee37aa4b6cb3588baaeb68e4c5e` (same tree as local `dfc0de97`).
Exports are frozen before these actions, which use isolated private native/publication
workspaces. No request interception or artificial UI image is used.

The initial core run completed 2,294 app tests with only the known ratchet failure
and no errors. Its aggregate workflow also failed the intentional draft-PR guard;
that draft gate is not a separate product failure. The first three database lanes
each completed 2,285 app tests with only the known
ratchet 5→6 failure; they are failed runs, not accepted database results. The reviewed
single-edge correction is locally green and awaits new actual database CI. The
separate document-template workflow failed at EN 390×844 saved-diff horizontal
reflow; DE 1280×900 passed. Publication CSS is not loaded there. The next source
adds only bounded failure-layout diagnostics and an actual failure screenshot,
leaving the assertion in place. [Document CI 35525893726](https://github.com/carstenartur/Taxonomy/actions/runs/35525893726)
subsequently passed with no layout fix and no overflow diagnostic files: its first
measurement passed. The actual mobile saved-diff image was inspected and fits the
page; the responsive history table scrolls internally. Artifact `10609547675`
SHA-256 `0e77f1ad005a6c823e2af5edbd212ee34ffcd555d17687f7015f7cc13c703d9e`
retains that evidence. The diagnostic now asserts the frozen first measurement,
so diagnostic time cannot mask an initial failure. The original overflow cause
remains unresolved; a later pass is not evidence of a diagnosed layout fix. Final whole-source
review, clean-root and exact-tree
required CI remain release gates; these pending gates are not proprietary feature gaps.

## Product acceptance still requiring proprietary execution

* EA import/edit/export round trips and preservation of EA-specific layout/content.
* Versioned live PCS scope CAS, atomic creation, durable idempotency and receipt lookup
  before enabling production writes. OSLC supports writes in principle; those specific
  safety/recovery guarantees remain unverified.
* Actual Microsoft Word and Microsoft Visio behavior; POI, LibreOffice and Draw checks
  do not certify those applications.
* A built and hosted SBPI plugin against the supplied proprietary SDK, if desired.
  The optional [action/API/deep-link design](../features/sparx-integration.md#authenticated-api-and-optional-sbpi-client-design)
  is documented and Taxonomy works without it.

The authoritative [Sparx product matrix](sparx-compatibility.json) remains
`NOT_EXECUTED`. The civilian example still has the recorded
[domain/reference-case gaps](civilian-reference-review.md); complete transport and
rendering do not certify an operational flood-management architecture.

## Bounded service review correction after the UI fix

Against base `f082a5725ca615ce09672b590e968ba118dba3cd`, the
[D80 correction](1075-completion-decisions.md#d80--late-receipt-authority-and-joined-exact-checkpoint-proof)
retains obsolete nonterminal attempt evidence without resolving a newer SEND,
protects terminal receipts from uncertainty callbacks, and moves the direct editor
helper's authoritative HEAD observation inside its existing joined lock. Actual
HTTP receipts, persisted claims and Git/ORM rollback produced four behavioral REDs;
the initial CLOB test error is not counted as one. Seven focused tests and 41 final
covering tests passed (23 recovery, 9 flow, 4 partial, 1 concurrency, 4 editor), with
zero failures/errors/skips. The shared journal/checkpoint guards are covered through
real Push/Sync and rollback recovery; no broad suite or external-product execution
is claimed. The separate Task 3 UI fix review is approved; independent review and
final full-source CI for this service correction remain open. Earlier unexplained
failures and inherited warning qualifications above remain unchanged.

# Lossless context encoding for cross-taxonomy reconciliation

This package-5 slice starts from main `549d3f7`. It reduces exact repeated question
context in the existing Phase-B request; it does not partition the graph or
substitute local reviews for a whole-input review. Original requirements,
statements, conditions, edges, user answers and persisted revisions are unchanged.

## Representation and limits

Reconciliation now uses the same request-local discovery-context table as node
synthesis. Identical repeated strings longer than 128 characters can appear once
in `discoveryContextTable`; discoveries and merged origins refer to that exact
string with `contextRef`. The table travels inside the SAME reconciliation JSON.
Distinct contexts remain complete and inline, including their final conditions.
Prompt instructions describe the references and treat values as untrusted data.

The final reconciliation encoder compares the savings against the entire table
AND its instruction overhead. When factoring would grow the request, the original
inline representation is retained. There is no arbitrary length truncation,
external-reference lookup, model-generated summary or changed provider budget.
If unique context still exceeds the budget, the existing explicit size error
occurs before HTTP. Previously saved partial evidence remains available.

The helper extraction preserves node-request output byte-for-byte for the checked
small/large/repaired fixtures. Existing successful reconciliation checkpoints also
remain valid: their full canonical input and fully supplied evidence are unchanged.
No blanket cache invalidation or automatic recomputation is introduced. A formerly
rejected request has no successful result checkpoint to bypass the new encoding.
Historical provider recording/replay still keys off the actual encoded prompt.

## Tests and evidence

`ReconciliationContextTest` has nine ordinary JUnit methods delegating to executable
checks. They cover exact duplicate factoring, unique/short context, unprofitable
dictionary overhead, merged origins/aliases and user answers, deterministic repair
payloads, a real budgeted gateway, its actual parser-repair path, irreducible input
with zero HTTP requests, and the real Phase-B reconciler with directed boundaries.
Decoding the dictionary must reproduce EVERY input field of the scoped JSON, not
just its IDs. Tests also verify immutable inputs and canonical question identities.

Before production changes, six new behavior checks failed as intended (three
missing-table assertions and three actual budget failures); unique-context and
irreducible-input regression checks passed. One initial test fixture compared
Java numeric node types rather than their JSON representation; that fixture was
corrected before counting the meaningful RED results. A ninth test then reproduced
unprofitable encoding and passed after retaining inline data for that case.

Fresh Java21 compilation and all nine executable checks passed against the green
#1101 runtime artifact `10670380386`. The unencoded final provider request was
40,795 characters and exceeded its 16,000-character test budget. The encoded final
request is 14,322 characters with the same complete data, and uses one HTTP call.
The intentionally malformed first response uses exactly one repair (two calls).
These are authored loopback model replies, not evidence of real-model language
quality or token billing. Character counts are not actual provider token usage.

All six pre-existing grouped-evidence checks, the small/large/original-limit node
checks and the real serial-versus-parallel gateway fixture also passed. The latter
still uses four calls per run with at most two concurrent calls and identical
result documents. Four node prompt snapshots (small/large, initial/repair) are
byte-identical to the predecessor. No database, workflow, POM, threshold or
architecture inventory is modified.

## Verification limits

The canonical `./mvnw -B -ntp -pl taxonomy-analysis -am test` and full
`./mvnw -B verify -Pci` were attempted locally; Maven 3.9.16 could not be downloaded,
so no local full Maven/JUnit pass is claimed. The ordinary JUnit test is selected
by the existing full analysis suite; no separate CI selector is added. Current-head
CI and independent review remain required. Particularly large DISTINCT connected
contexts, retention, adoption and export remain separate plan work.

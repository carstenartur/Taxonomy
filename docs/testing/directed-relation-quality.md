# Directed-relation quality: bounded first delivery for #927

The existing `CivilianArchitectureAcceptanceTest` still executes the real application,
HTTP authentication, catalogue, two-pass analysis, persistence, export/reopen and
optional browser checks. Only the outbound model responses are authored playback.
The fresh-process launcher now evaluates the saved result before its completion
marker. No second analysis, provider, runtime module, Maven profile or CI workflow
is introduced. No production search algorithm, prompt, budget or quality gate changes.

## Output and provenance

The existing `target/civilian-acceptance` artifact directory gains
`relation-quality.json`, `relation-quality.csv` and `relation-quality.html`.
JSON and HTML retain the case/reference versions, fixture and exact snapshot hashes,
application source revision, catalogue and prompt fingerprints, actual relation-call
count/budget/duration, full-scenario call count and directed mismatch lists.
CSV is a compact numeric summary. Relation calls concern the selected snapshot;
full-scenario calls include both verification passes and must not be added again.

The seven expected relationships are an explicitly authored closed-world reference,
kept outside the provider response corpus and never supplied to prompts by this
post-processor. This is one synthetic acceptance case, not expert ground truth.
The reference is not derived from the generated graph. The existing independent
scenario assertions are retained unchanged.

## Metric contract

A relation matches only when source, type and target all match. Reversed direction
or a different type creates a false positive and leaves the required relation
unobserved. Duplicate predictions and overlapping reference classifications fail
instead of being silently deduplicated. Collections and report ordering are stable.

Required, allowed and unresolved reference sets are distinct. Allowed predictions
count as classified correct predictions, but their absence does not lower required
recall. Unresolved predictions count as neither correct nor false; they are listed
explicitly. Any unresolved reference prevents a complete PASS, even if unobserved.
A known false positive or missing required relation still produces FAIL.

For a successful run, precision is `(matchedRequired + matchedAllowed) /
classifiedPredictions`, excluding explicitly unresolved predictions from the
denominator. Recall is `matchedRequired / requiredReferenceCount`. Both measures
are conditional on the authored reference, not calibrated probabilities. A zero
denominator is `null` in JSON and blank in CSV, never an invented 100%.

PARTIAL, FAILED and CANCELLED runs remain INCONCLUSIVE and have no quality rates;
NOT_RUN is separate and cannot contain predictions. Counts remain inspectable.
A top-level SUCCESS with unfinished relation searches, relation warnings or a stop
reason is treated as PARTIAL. Missing fields and conflicting snapshot identities
are rejected, not interpreted as empty successful results. `searchExhausted` alone
never certifies completeness.

The report always says `AUTHORED_PLAYBACK_NOT_LIVE_MODEL`. PASS means that this
recorded projection matches this closed-world reference; it does not prove language
quality, semantic completeness on arbitrary input or superiority over another tool.

## Execution and bounded inputs

The ordinary `./mvnw test` runs the JUnit contracts and the existing scenario launcher.
The documented civilian acceptance profile also uses that unchanged launcher.
Before a scenario starts, earlier quality reports are removed. If it fails before
producing its final saved snapshot/run receipt, no replacement success report is
published. Metric mismatches reached by the post-processor are written before the
new verification assertion fails. Original scenario failures and timeouts remain
failures. This first slice does not add early-failure analysis event reporting.

Only the fixed `snapshot.json` (maximum 32 MiB) and `run.json` (maximum 64 KiB)
files are read, with bounded reads. Case and snapshot identities must agree, and
source catalogue/prompt/fixture hashes must be well-formed. The report does not
copy original requirement text, prompts, answers or private source documents.
HTML escapes report text; CSV contains only the fixed case ID, enums and numbers.

## Verification performed for this change

The local environment could read GitHub through the connector but could not clone
GitHub via the shell. `./mvnw test` was attempted in the recovered source subset
and failed before execution (wrapper absent, exit 127). **The new JUnit tests and
full application/Maven suite were not run locally.**

Supplementary Java 21 checks compiled the actual new helper sources and passed
29 metric checks plus 14 report checks. Report checks use the genuine saved
civilian snapshot from CI run `36354588531`, artifact `10944295583` (archive SHA-256
`1fa87243ac9dce65e348de0caf3210962f9ded7e24a1917b70868b60de95dacf`).
The report retains that artifact's recorded source revision; it is not relabelled
as a fresh execution of this branch. The checked snapshot contains seven expected
relations and records 136 relationship calls under its explicit 256-call fixture
budget, not the production default.

Compilation used the actual Jackson 3.1.5 jars from application artifact
`10959860682` (archive SHA-256
`f1e81da95a9c25c052a82ebe4fd6fbf4fae219e8b0022fff10155e867d275eae`).
These isolated checks are supplementary evidence, not JUnit or full E2E passes.
Normal exact-head CI and review remain required before merge.

## Remaining #927 scope

The general versioned case importer/runner, held-out cases, acceptable alternative
groups, hierarchical metrics, broader domains/languages, baselines, live-provider
cost accounting and resumable evaluation runs remain open. This delivery neither
closes #927 nor claims to implement the rest of the product roadmap. It creates a
small executable measurement boundary in an already running acceptance workflow.

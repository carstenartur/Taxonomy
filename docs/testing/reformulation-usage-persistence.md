# Durable reformulation usage evidence

Package 5 continuation of the approved requirement-reformulation plan. This binds
transport observation to the existing authenticated run and recovery lease, and
adds a read-only consumption panel. It does not adopt text, answer questions,
change the original requirement/current version or architecture, or create Git
commits. The earlier transport observation tests remain in `reformulation-usage.md`.

## Admission, outcomes and scope

`LlmTransportMeter.Journal` is separate from the optional best-effort consumer.
The application worker installs a journal only inside its captured run execution.
The existing explicit capture propagates both consumers and the journal to
parallel child tasks. No implicit inheritable thread-local or global run exists.

Before each actual gateway client exchange, including each internal retry, the
journal commits a unique attempt. Admission joins the existing proposal -> run ->
lease locking through a short transaction and rechecks the exact owner/epoch,
dispatch and authorization. No network call runs inside that transaction. A failed
admission prevents the request; the gateway does not swallow it or retry it as a
provider error. Configuration/budget rejection before transport invents no attempt.

Outcome recording is another short transaction. It can complete only the exact
committed attempt with its original run/owner/epoch and current authorization.
A response arriving after cancellation, lease replacement or run completion may
still fill its own receipt, but it cannot admit more work or publish a proposal.
Duplicate identical starts/completions are idempotent; conflicting identities or
receipts fail. Another lease owner cannot replace the prior attempt's receipt.

A receipt-storage error leaves the durable start unresolved. It does not resend a
paid request, discard a successful provider response or manufacture zero usage.
A crash between admission and transport cannot be distinguished from a crash
while awaiting the response: these are **admitted client attempts, not proof of
provider receipt, billing or exactly-once execution**. The UI says so. Existing
recovery may legitimately repeat an unresolved request. Successful checkpoints
are reused without inventing additional transport events.

Replay has separate source identity, no HTTP status and no new token counts.
Reported input/output/total/cached-input/reasoning values remain independent.
Missing values stay unknown; real zero stays zero; invalid metadata remains
flagged. Totals are not recomputed. Aggregation uses BigInteger and returns decimal
strings, preserving sums beyond both Java long and JavaScript exact integers.
No prompt, response text, URL, API key or exception message is in these records.

## Persistence and read API

Additive PostgreSQL V26 creates `reformulation_usage_session` and
`reformulation_usage_attempt`. Prior migrations are unchanged. HSQL and other
existing database modes use the same JPA mappings. Tests extend the PostgreSQL
fresh/adoption version lists and exact column/FK contracts.

A recording session distinguishes never-recorded runs from recorded zero.
`fromFirstAttempt` is true only if recording began with the first lease and no
prior checkpoint in that run; it is not a completeness or billing certificate.
First activation during recovery retains a warning about missing earlier history,
including after restart. The recording origin is immutable. Unknown receipts are
counted explicitly, even after the run ends.

`GET /api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}/synthesis-runs/{runId}/usage`
reuses requirement/workspace authorization, verifies the scoped offer/run and
returns Cache-Control: no-store. It reads scalar attempt data rather than prompt
or checkpoint LOBs. Counts distinguish HTTP admissions, transport retries, replay,
pending outcomes, HTTP/transport errors and invalid usage. Each token field carries
its exact reported sum plus reported/unknown observation counts. No pricing,
retention/deletion policy or automatic completion percentage is introduced.

The DE/EN usage panel is part of saved partial-result inspection. It is separate
from editable text/questions; stale responses cannot replace another selection.
Unchanged polling retains the DOM. Missing measurements and late activation have
explicit warnings. Failed measurement reads do not remove existing partial text.
The existing API adapter performs GET-only, authenticated, no-store reads.

## Verification and evidence boundaries

Tests precede implementation. Observed initial RED: journal boundary absent,
usage endpoint 404, worker sending HTTP without committed admission, and missing
usage UI. Additional RED exposed a missing recording-origin flag and a null-source
validation error; both have focused regression coverage.

`LlmDurableTransportTest` uses the same executed plain checks for admission order,
failed admission (zero HTTP), failed receipt storage (one HTTP, no retry), capture,
replay and isolation. `ReformulationUsageBoundaryTest` validates identities,
provider/source/retry values, every nullable token field, statuses and exact
attempt/session ownership. Existing transport metadata/retry tests are retained.

`ReformulationUsageTest` starts two fresh application JVMs with a shared file HSQL
DB. It checks real HTTP authorization, no-store, never-recorded/zero recording,
starts, retries, replay, unknown outcomes, idempotence/conflicts, late original-owner
receipts after takeover and cancellation, rejection of successor/stale writes,
large exact sums, incomplete pre-recording history, unchanged source/offer and
restart persistence. Only input analysis/events are authored; the BP root is
obtained from the real TaxonomyService. No cloud model is called.

`ReformulationUsageWorkerTest` runs the actual application worker/gateway/parser
and file database against authored loopback HTTP replies. The remote handler
checks committed admission from a separate DB transaction before answering. A
second case forcibly kills the producing JVM during the second request after its
first node/question was checkpointed. Automatic recovery keeps the same run and
question identity, issues only the remaining request, and retains the unresolved
old request. Normal-exit child JVMs inherit the real JaCoCo agent when present;
coverage from the forcibly killed producer is not assumed to be flushed.

The actual API adapter and progress-action contract retain prior pagination,
markup safety, stale response, unsaved draft and cancellation assertions while
checking exact large token sums, pending counts and late-activation warnings.
These are DOM/API-boundary tests, not a full browser-to-server acceptance.

Local compilation and plain checks use Java 21 plus actual CI runtime libraries;
the full public source was refreshed from the exact integrated 3c5cda7 CI archive.
The canonical local `./mvnw verify -DexcludedGroups='real-llm'` remains blocked before
compilation by the unavailable Maven distribution download. The supplemental
workflow executes normal Maven/JUnit tests and the unchanged architectural ratchet,
verifies positive counts and preserves source/reports. It does not replace full
reactor, coverage, all databases, browser gates or independent review. Exact-head
CI and review are required; no threshold or policy is weakened for this feature.

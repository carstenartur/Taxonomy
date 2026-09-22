# Bounded shutdown and reclaimable local recovery slots

This package-5 correction continues PR #1101 from `201917f`. It changes no
requirement version, architecture, proposal-edit contract, usage ownership rule,
review threshold or database migration. Confirmed adoption remains separate.

## Canonical CI blocker

The predecessor's supplemental usage tests succeeded. Canonical CI and all three
database lanes stopped earlier in `PythonSourceRatchetRepositoryTest` because the
new supplemental workflow introduced two executable Python heredocs. This was a
workflow defect, not evidence of failed database migration or usage persistence.

The workflow now uses the dependency-free Java tooling command
`check-junit-reports`. It verifies each exact named XML suite, positive minimum
test counts, zero failures/errors/skips and matching testcase evidence. Missing,
malformed, misnamed or internally inconsistent reports fail. The existing safe
XML parser rejects DOCTYPE and external entities. The productive-Python ratchet,
its baseline and the normal Maven verification commands are not weakened.

The supplemental lane additionally runs the tooling module's tests and requires
the new local-recovery tests. It still does not replace canonical full verification.

## Bounded child-executor cleanup

`ExecutorService.close()` could join an uncooperative child indefinitely even
when the caller had been interrupted or a sibling had failed. Walk-up execution
now shuts down explicitly: a normal failure allows at most one second for
already-running siblings to finish useful checkpoints, then requests interruption.
An interrupted caller does not wait for this grace period. The original exception
and caller interrupt status are retained. Dependent parents never start.

Java cannot forcibly terminate a supplier that ignores interruption. Such daemon
children may outlive the caller until their underlying operation returns. The
existing run/lease checks remain the authority for checkpoint and proposal writes;
this correction does not promise cancellation/refunds of an in-flight provider call.
Successful child results are still assembled in deterministic plan order.

## Local dispatch ownership is distinct from database lease ownership

Each queued delivery now has its own local reservation. A failed database-clock
heartbeat retires that exact reservation, interrupts its attached runner and frees
its logical recovery slot. The next bounded scan may queue a successor while the
old HTTP response is still outstanding. A stale worker's `finally` uses identity-
checked removal, so it cannot remove the successor's reservation. Duplicate queue
delivery cannot enter the same reservation twice or perform its cleanup.

Attaching/detaching/interruption share the reservation monitor, preventing a late
interrupt from reaching a pooled thread after that thread has moved to other work.
This does not expand the shared executor or guarantee progress if every physical
worker is stuck in an operation that cannot be interrupted.

Coordinator shutdown marks the execution service as stopped, prevents new starts
and retires queued/running local work without waiting on database I/O. It leaves
recoverable database work for a later process instead of marking it FAILED.
The persistent local retirement flag is checked at admission and checkpoint
boundaries, because JDBC/transport operations may clear a thread's interrupt flag.
Late own usage receipts retain their narrow existing permission; retirement never
grants new transport or proposal-publication rights.

## Finite Visio geometry must also be computationally bounded

The previous finite/integer-coordinate checks still allowed huge finite rectangles
with billions of spatial cells. Each occupancy operation now rejects more than
4,096 cells before visiting or allocating any. Column/row arithmetic uses long and
a division-based comparison instead of an overflowing product. Ordinary geometry
and the prior integer-boundary checks remain unchanged. This is an explicit export
budget error, not truncation of the diagram.

## Executed verification

Tests were written and run against the predecessor first:

- Child failure and caller interruption both demonstrated the unbounded join.
- The real application demonstrated `Expired local worker permanently consumes
  the recovery slot` while a provider response was held.
- The shutdown case demonstrated `Stopped execution service admitted a new run`.
- A second iteration exposed that interruption alone could allow another HTTP
  request; the permanent local retirement marker fixes that boundary.
- A large finite Visio rectangle reached its first cell visitor instead of being
  rejected. The test visitor stops immediately, so RED does not allocate/scan the
  giant grid.
- The report-verifier contract failed because the command was absent.

Final local Java-21 compilation and executed checks passed:

- Five report-verifier groups, including corrupt XML and forbidden DOCTYPE.
- Two shutdown regressions plus all five existing scheduling boundary groups,
  repeated three times; the existing real-gateway parallel fixture still yields
  equal serial/parallel documents, four calls and maximum concurrency two.
- Two fresh Spring/application JVMs exercise actual HSQL persistence, leases,
  usage journaling, gateway/parser and loopback transport. Only provider timing,
  queue delivery and authored responses are controlled. Capacity recovery makes
  three calls (including the old late receipt), advances the lease once and
  publishes one proposal revision. Graceful stop admits no extra run or HTTP call.
  Original requirement/version count stays unchanged in both cases.
- The existing duplicate-dispatch application check passes with two calls and one
  proposal revision after these changes.
- The finite-grid test plus existing malformed/integer-boundary cases pass.
- Both Java report commands successfully check actual predecessor JUnit XML;
  those old reports are not represented as newly executed tests.

The complete source is present locally. The canonical Maven command and updated
supplemental Maven command were attempted but cannot download Maven 3.9.16 from
this environment; neither is claimed to pass locally. Production overlays were
compiled against genuine CI runtime dependencies. JUnit wrappers call the exact
plain checks above; new-head CI must still run them, coverage, the unchanged
architecture dependency inventory and database/browser suites. A new nested local
reservation references the existing portfolio Claim contract; any inventory update
must follow the actual unchanged ArchUnit output, not guessed counts.

No live cloud model was called. These are execution/safety tests, not a claim about
real-model wording quality or full civilian acceptance. This is author review, not
independent approval. Large aggregate/discovery-context fidelity, checkpoint query
indexing, retention, subsequent adoption and final acceptance remain separate work.

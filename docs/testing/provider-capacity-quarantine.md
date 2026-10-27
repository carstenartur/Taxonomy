# Conservative provider-capacity quarantine

## Scope

This extends the existing opt-in `taxonomy.analysis.provider-permits.enabled`
mechanism. It changes no database schema, rate-quota policy, provider API key,
REST wire contract or ordinary local-mode behavior. It uses the existing permit
queue and worker send/consume permissions; no scheduler or extra broker address
is introduced. All HTTP providers already using the permit port benefit without
a model-specific token-count adapter.

## Protocol and failure outcomes

| Stage or outcome | Available capacity |
| --- | --- |
| Acquired but not started | Original schema-1 token is still transactional; unused close/worker loss can return it. |
| Confirmed start | One transaction consumes the available token and writes a persistent schema-2 `IN_FLIGHT` receipt. No HTTP may precede this acknowledgement. |
| Start acknowledgement lost | No HTTP. The broker may retain the original token or the receipt, depending on commit; never send an unpaired replacement. |
| In flight | The worker holds the exact receipt. Rollback, worker death or broker restart redelivers only the unavailable receipt. |
| Complete synchronous 200 or terminal 400/401/403/404/413/415/422/429 rejection | Close the old session, consume that exact permit/attempt receipt in a fresh session, then atomically return one available token. |
| 202, 206, redirect, 408, server/proxy failure, timeout, cancellation or transport error | Keep the receipt unavailable. Neither a local close nor retry timeout proves the remote computation ended. |
| Return acknowledgement lost | Never compensate. Preserve a valid received HTTP answer; capacity may be available or still quarantined until inspected. |
| Explicit administrative reconciliation | Consume the exact receipt once and return one token in the same transaction; repeated/stale IDs cannot add capacity. |

The private random attempt identity prevents a late worker from returning a slot
already reconciled after connection loss. A different attempt on the same physical
slot has a different identity. Validation rejects malformed or noncanonical UUIDs
before constructing a selector. Receipt messages contain only group, permit ID,
state, attempt ID and timestamp; no requirement/prompt/response or credentials.

Response codes in the table assume the existing synchronous inference transport.
They are not a promise about a gateway which returns a false success or continues
work despite a terminal rejection. Unknown outcomes keep capacity blocked, possibly
indefinitely. Repeated ambiguous failures may therefore exhaust available slots;
this is deliberate conservative admission, not an automatic retry/refill scheme.
A journal or quota failure after receipt creation can also require reconciliation
even though no HTTP was sent. Rate debits keep their own conservative semantics.

## Metrics and decoration

The production metrics wrapper forwards the complete permit lifecycle through the
transport-neutral `observeClose` decorator. Metrics must not replace a permit with
a close-only lambda and silently bypass durable start. The process-local lease
outcome is now `CLOSED`, not `RETURNED`: a closed lease may leave a quarantined
receipt. Adjust dashboards filtering that outcome label. `FAILED` still records
failed lease cleanup; the in-flight gauge is local, not a global available-slot
count. Broker inspection remains necessary to observe retained receipts.

## Coordinated upgrade

Stop admission and drain or stop **all** processes using the quota group before
upgrading. Independently settle any outstanding upstream requests. Deploy the new
code to every worker/web role which can acquire permits before admitting new work.
Do not mix old/new consumers, and do not roll back to an old consumer while any
schema-2 receipts remain. Existing schema-1 available tokens stay compatible; do
not replenish an existing queue based on its current message count.

Keep one durable broker authority, `journal-sync-transactional=true`, persistent
queues/messages, no expiry, no auto-delete/purge-on-no-consumers, unlimited
redelivery and a non-dropping full-address policy. Review existing transaction and
connection timeouts. An address/queue policy which deletes receipts, or a restored
older broker journal, invalidates capacity accounting. Test the actual HA and
partition/failback topology before production approval.

## Administrative inspection and recovery

Use the existing broker/TLS secret environment and a separate administrative
account. Workers do not need browsing, deletion or provisioning privileges.
The packaged entry point does not start Spring or contact an LLM:

```bash
java -Dloader.main=com.taxonomy.composition.analysis.artemis.ArtemisProviderPermitProvisioner \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  --inspect shared-account
```

This lists up to 1,000 observed receipts as permit ID, attempt ID and start time,
followed by a truncation indicator. It is a bounded diagnostic observation, **not**
proof that there are no other active requests or in-delivery receipts. Use the
broker's administrative tools for a larger backlog; never infer refill authority
from a count. The timestamp is diagnostic metadata, not a lease or expiry deadline.

Before release, stop every old/competing worker which could still start or finish
a request on that group and independently confirm the affected upstream work has
ended. When individual upstream identity is unavailable, establish that the whole
relevant account/group is drained; elapsed time alone is not confirmation.
Then use the exact IDs from inspection, not invented IDs:

```bash
java -Dloader.main=com.taxonomy.composition.analysis.artemis.ArtemisProviderPermitProvisioner \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  --reconcile shared-account "$PERMIT_ID" "$ATTEMPT_ID" \
  --confirmed-upstream-stopped --confirmed-workers-stopped
```

Both confirmations are mandatory; they are operator assertions, not automated
proof. The command rejects a missing queue, wrong group/attempt, already consumed
receipt or unconfirmed commit. A second invocation with the same IDs never creates
a second token. Do not replace/delete/refill the queue after an uncertain command.
Inspect its exact state instead. This command never refunds or resets RPM/TPM/RPD.

### Kurzfassung für den Betrieb

Ungewisse Provider-Aufrufe behalten ihren Platz dauerhaft. Ein Neustart oder
Timeout gibt ihn nicht mehr automatisch frei. Vor `--reconcile` müssen sämtliche
betroffenen Worker gestoppt und die externen Aufrufe unabhängig nachweislich beendet
sein. Beide Bestätigungen und die exakte Permit-/Versuch-ID sind erforderlich.
Die Belegung wird genau einmal freigegeben; ein wiederholter Aufruf erzeugt keinen
zusätzlichen Platz. Keine gemischten alten/neuen Worker betreiben, keine belegte
Queue auffüllen und keine Ratenkontingente zur Fehlerbehebung zurücksetzen.

## Tests and evidence boundaries

`ProviderPermitLifecycleTest` registers the real admission/meter lifecycle cases.
`ArtemisPermitTransactionTest` uses the production permit state machine with an
explicit deterministic JMS transaction model and actual Artemis message objects.
This catches acknowledgement uncertainty and stale-owner effects, but is **not**
a real broker test.

`ArtemisProviderPermitQuarantineTest` adds four real persistent-TCP-broker cases:
normal completion, uncertain state across restart, exact confirmed reconciliation,
and killing an independent worker JVM while a loopback HTTP handler remains active.
The child uses the production metrics wrapper too. The latter waits for the broker to roll back the dead delivery before asserting
replacement admission is blocked. Only this disposable fixture uses 1,000 ms
connection TTL and 250 ms client-failure checks; no production timeout is changed.
These tests require the normal existing JUnit/Artemis-server test dependencies.

Run from a complete checkout with Java 25; no paid model, Docker or Kubernetes is
needed for this selected real-broker fixture:

```bash
./mvnw -B -pl taxonomy-app -am test \
  -Dtest=ProviderPermitLifecycleTest,ArtemisPermitTransactionTest,ArtemisProviderPermitQuarantineTest,ProviderConcurrencyGatewayTest,ProviderPermitRateReservationTest,ArtemisProviderConcurrencyPermitsTest,ArtemisProviderPermitsConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The prior concurrency fixture counts in-delivery permit messages. Under the new
protocol those messages are receipts during HTTP; available plus in-flight capacity
still follows the configured bound. Original unused-permit rollback/restart cases
remain relevant and must continue to pass.

The canonical gate remains `./mvnw -B verify -Pci -DrunOnnxTests=true`. Compilation,
direct case-body execution and an executable acceptance test file do not establish
that the Maven/JUnit, real broker or complete CI gates passed. Retain raw evidence
under `target/` or in CI artifacts. The [remaining JMS/API acceptance](../dev/JMS_API_ACCEPTANCE.md)
also includes multi-web-pod browser recovery, deployment HA and measured load;
this change does not add a generic Gemma/vLLM hard-TPM adapter.

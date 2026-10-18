# Durable provider-capacity quarantine

The remaining unsafe case is broker/worker loss while a synchronous provider
request may still be executing. A rolled-back available permit must not authorize
another request in that case. Do not infer termination from a local timeout.

Before entering an admitted physical request, atomically consume its available
permit and publish a persistent metadata-only IN_FLIGHT receipt in the same queue.
Normal consumers select only available permits. Hold the exact receipt during
HTTP; rollback can therefore redeliver only the unavailable receipt. Reuse capacity
only after a completed synchronous HTTP response or explicit operator reconciliation.
Uncertain broker commits authorize no HTTP. Uncertain return commits never produce
an unpaired replacement. A private random attempt ID prevents a late owner from
returning capacity already reconciled by an operator.

Response 200 and terminal client rejections 400/401/403/404/413/415/422/429 permit
normal return under the existing synchronous provider contract. Async 202,
transport exceptions, timeouts, cancellation, redirects and server/proxy errors
remain quarantined. A caller cannot infer a finished upstream job from those cases.

The existing provisioner gains bounded inspection and exact-receipt reconciliation.
Reconciliation requires explicit upstream-stopped AND workers-stopped acknowledgements and consumes
one matching receipt transactionally. These are operator assertions, not remote proof. It never seeds capacity or resets rate quotas.
No database migration, new broker address, periodic polling or paid provider test.

Deployment requires a coordinated upgrade of every permit consumer, persistent
transaction commits (journal-sync-transactional=true), and the existing no-expiry,
no-auto-delete permit-queue policy. Mixed old/new workers are not supported.
This is not general provider fencing against a peer that lies about completion.

A failed capacity cleanup must preserve a successfully received answer. It must
not mask that answer and thereby cause another paid request. No unpaired
compensating send is permitted after either start or return uncertainty.

The production metrics adapter must transparently forward both lifecycle hooks.
A framework-neutral close-observation helper preserves that contract and invokes
its observer exactly once. Local closure is labelled CLOSED rather than claiming
quarantined capacity was RETURNED. No new cross-context dependency is needed.

# JMS and REST API acceptance boundaries

Status reconciled on 2026-10-18 against baseline `44a8ee8228cca1bb0f90b264a1aab617f75b8874` plus the provider-quarantine change.
This is an implementation/acceptance index, not a deployment certificate and not
a statement that every endpoint's complete schema has been audited.

## Implemented baseline

| Capability | Implementation and evidence owner |
| --- | --- |
| Durable root/relation workers, frozen input, cancellation and replay | [Task DAG](ANALYSIS_TASK_DAG.md), [durable execution](CLUSTER_ANALYSIS_EXECUTION.md) and the existing broker/database/cluster suites. |
| Distributed continuation and saved answers | `AnalysisContinuationService.beginDistributed`, frozen worker checkpoint binding and `FrozenClusterContinuationTest`. Local execution deliberately keeps its own path. |
| Conservative provider capacity after loss | [Durable receipt quarantine](../testing/provider-capacity-quarantine.md), lifecycle/transaction regressions and a separate real-broker/worker-JVM acceptance test. Execution of that acceptance remains required. |
| Durable call diagnostics | `AnalysisProgressController.detail` delegates to `cluster.callDetail` for an owned cluster run; summaries do not load complete prompt/response bodies. |
| Shared RPM/RPD and provider cooldown | Separate opt-in `ArtemisProviderRateQuotas`, explicit credential groups and durable state provisioning; see [operator contract](../en/OPERATIONS_GUIDE.md#cluster-wide-physical-attempt-rate-quotas). |
| Supported hard TPM accounting | Only the explicitly supported [Responses token adapter](../en/OPERATIONS_GUIDE.md#supported-openai-responses-tpm-adapter). Unsupported transports reject before HTTP when hard TPM is enabled. |
| REST descriptions and selected full contracts | [OpenAPI contract tests](OPENAPI_CONTRACTS.md). The classpath inventory and the selected generated-document contracts have different coverage. |

The old #1188 scope is not the current JMS backlog. Do not reimplement these
features merely because a historical PR description says they were out of scope.

## Remaining acceptance, not silently completed by green unit tests

| Boundary | Required evidence before claiming completion |
| --- | --- |
| Worker or broker loss during provider HTTP | Hold a real loopback provider request open, terminate the worker/process or its broker connection, and observe remote outstanding work and replacement admission independently. Report any overlap above the configured slot count. A returned broker token does not prove the original remote computation stopped. |
| Browser switches web pods | Authenticated admission and SSE on one web pod, work on another process, then reconnect to a different web pod with the saved cursor. Assert identical owned operation/input, monotonic revision, no repeated accepted answer and no foreign-scope disclosure. |
| Deployment HA | Real TLS partition, primary/backup promotion, failback and database failover in the selected topology. Component shared-store failover is useful evidence but does not establish the target deployment's quorum/fencing and recovery behavior. |
| Resource/capacity comparison | Repeated runs of the same frozen workload and provider delays for local/full-worker/sharded-worker layouts, with declared worker counts, heap/RSS peaks, elapsed time, physical calls and useful partial results. Keep existing budgets; retain raw measurements in `target/` or CI artifacts. |
| Additional hard-TPM providers | Exact model/tokenizer/chat-template/input and output bounds, unsupported/mismatched rejection, retry/cancellation/usage accounting and frozen continuation identity. OpenAI-compatible syntax alone does not validate Gemma/vLLM or contributed endpoints. |
| Complete API contract coverage | Expand real generated-document and HTTP-behavior checks beyond the selected controllers, with exact request fields, errors, schemas and feature conditions. Do not introduce generic placeholder descriptions or an allowlist of undocumented endpoints. |

These are distinct work items. Passing source/annotation tests cannot close an
external failure exercise. Existing green historical runs remain evidence for
their exact commits and scenarios only.

## Guaranteed semantics versus remote execution

The implementation provides idempotent durable effects and conservative retained
rate debits. It does **not** currently guarantee a hard upper bound on still-running
remote computations after worker/broker loss. A local timeout, closed connection,
returned JMS permit or restored database cannot establish that the remote peer
stopped. Recovery must not interpret UNKNOWN as no usage or as a validated answer.

The provider-permit adapter now implements durable quarantine of uncertain capacity
and exact-receipt operator reconciliation. This closes the former unconditional
return in the implementation: failure after confirmed receipt creation cannot make
that receipt an available slot. It does not prove a remote job stopped, replace
provider-supported cancellation/fencing, or certify a deployment. Mixed worker
versions, destructive broker resets and unconfirmed operator release invalidate
that safety contract. The real-broker, worker-loss and deployment acceptance above
remain separate gates; a deterministic transaction model is not their substitute.

## Reproduction and closure

The [OpenAPI instructions](OPENAPI_CONTRACTS.md) contain the focused Maven command.
The canonical repository entry point remains:

```bash
./mvnw -B verify -Pci -DrunOnnxTests=true
```

Tests remain JUnit/Maven-owned. Do not replace the existing gates, add a second
workflow-owned selector, weaken coverage/security thresholds or call a direct
Java diagnostic an executed Maven/JUnit/full-reactor result. A final closure must
identify the source commit, the selected tests and actual outcomes, and keep
unexecuted environment scenarios explicitly open.

# Cluster provider concurrency permits

The P08 provider boundary uses `ProviderConcurrencyPermits` in `taxonomy-analysis`.
`LlmGatewayRegistry` injects the optional implementation into every Gemini and
OpenAI-compatible gateway. Every physical attempt, including a retry, acquires
capacity after local admission. Unused and proven-complete capacity returns before
retry backoff; uncertain capacity stays unavailable. Recording replay makes no
HTTP attempt and acquires no permit.

`ArtemisProviderConcurrencyPermits` receives an available schema-1 token in a
transacted JMS session. Before the admitted physical request, it atomically consumes
that token and publishes a persistent schema-2 `IN_FLIGHT` receipt in the same queue.
Normal consumers select `permitState IS NULL` and cannot use a receipt as capacity.
Worker/broker loss after this commit therefore redelivers an unavailable receipt,
not an available slot. A complete synchronous response permits an exact-receipt
return in a fresh transaction; an unconfirmed return never causes a compensating send.

Transport failures, cancellation, asynchronous responses and ambiguous server/proxy
errors leave the receipt for explicit operator reconciliation. Failed cleanup does
not discard a successfully obtained answer or force another provider call.
See [quarantine protocol and operator recovery](provider-capacity-quarantine.md)
for the exact response policy, coordinated upgrade and acceptance boundaries.

## Configuration and initialization

The feature is explicitly enabled in Artemis transport mode:

```properties
taxonomy.analysis.transport.mode=artemis
taxonomy.analysis.provider-permits.enabled=true
taxonomy.analysis.provider-permits.destination-prefix=taxonomy.analysis.provider-permits
taxonomy.analysis.provider-permits.maximum-wait-ms=120000
taxonomy.analysis.provider-permits.provider-groups.openai=shared-account
taxonomy.analysis.provider-permits.provider-groups.custom-openai=shared-account
taxonomy.analysis.provider-permits.provider-groups.gemini=google-account
```

Every used HTTP provider must have an explicit mapping. Missing mappings fail
closed; display names, model names, endpoints and API keys never infer aliases.
Each group uses the durable anycast address and queue
`<destination-prefix>.<quota-group>`. Two mappings to `shared-account` consume the
same capacity. Local mode and configurations with permits disabled retain the
existing process-local behavior.

Provision each new queue once with
`ArtemisProviderPermitProvisioner.provision(factory, prefix, quotaGroup, capacity)`
or its administrative `main(<quota-group>, <capacity>)`. The command uses the
`TAXONOMY_ANALYSIS_ARTEMIS_*` broker/TLS/credential environment and optional
`TAXONOMY_ANALYSIS_PROVIDER_PERMITS_DESTINATION_PREFIX`. It does not launch the
Spring application. Use separate administrative credentials with queue-creation
permission; application consumers only need send/consume access to their queues. Inspection
uses a separate administrative account with browse access, not worker credentials.

For the packaged Spring Boot application (replace the JAR path as needed):

```bash
java -Dloader.main=com.taxonomy.composition.analysis.artemis.ArtemisProviderPermitProvisioner \
  -cp taxonomy-app/target/taxonomy-app-1.4.1-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher shared-account 2
```

Provisioning atomically claims a **new** broker queue, then commits all N tokens in
one transaction. An existing queue always rejects provisioning, even when empty
or when all its tokens are delivering. Application startup/reconnect never seeds
or replenishes tokens. Simultaneous operators therefore cannot double the count.
If initialization fails after queue creation, stop all consumers and inspect the
queue before an operator deletes/recreates it. Never infer missing capacity from
the available-message count while holders exist. Capacity changes require the
same stopped-consumer procedure.

Broker permit-address policy must preserve capacity: durable queues, no auto-delete,
no purge-on-no-consumers, no expiry or expiry-delay, unlimited redelivery,
and non-dropping address-full policy. The broker transaction timeout should exceed
the maximum physical HTTP duration. Transactional journal synchronization must be
enabled. Available tokens contain schema version, quota group and random permit
ID; in-flight receipts add only state, a random attempt ID and a start timestamp.
No prompt, response, API key or task payload enters these messages. All consumers
must be upgraded together; old workers do not implement the receipt selector.

With the separate `taxonomy.analysis.provider-quotas.enabled` feature disabled,
RPM remains **process local**. This document describes concurrency permits, not
the separate, now implemented [shared rate-quota protocol](../en/OPERATIONS_GUIDE.md#cluster-wide-physical-attempt-rate-quotas).
That protocol adds durable RPM/RPD admission, shared cooldowns and explicitly
supported TPM contracts; enabling permits alone does not enable it.
After a cluster wait, the local limiter revalidates
its reservation and any concurrent `Retry-After` before the physical attempt. If
local admission is no longer valid, it returns the cluster permit before waiting
again. The configured local maximum queue wait bounds the complete admission
attempt across both stages and repeated revalidation; the cluster maximum wait
adds its own limit. Failed or cancelled cluster acquisition refunds the unused
local RPM reservation. This mechanism makes no claim of cluster-wide RPM, TPM or
RPD enforcement by itself. See the [current acceptance boundaries](../dev/JMS_API_ACCEPTANCE.md)
for the distinction between admitted attempts and outstanding remote computation.

Cancellation/deadline checkpoints run before and after bounded 100 ms JMS receives.
Broker connection/control calls remain bounded by the existing Artemis connection
timeouts. A timeout or connection loss does not prove an accepted external request stopped.
The receipt remains unavailable without an automatic expiry; this favors bounded
admission over availability. It does not remotely cancel or fence provider work.
Operator recovery requires stopped workers AND independently confirmed upstream
completion. Target-environment fault acceptance remains necessary.

## Architecture boundary

The application composition layer implements the analysis-owned
`ProviderConcurrencyPermits` port. The original P08 review recorded these eight
direct class pairs from `com.taxonomy.composition.analysis.artemis` to
`com.taxonomy.analysis.service`:

| Application class | Analysis types |
| --- | --- |
| `ArtemisProviderConcurrencyPermits` | `ProviderConcurrencyPermits`, `ProviderConcurrencyPermits.Permit`, `ProviderConcurrencyPermits.UnavailableException`, `LlmProvider`, `AnalysisStoppedException` |
| `ArtemisProviderConcurrencyPermits.TransactionalPermit` | `ProviderConcurrencyPermits.Permit` |
| `ArtemisProviderPermitSettings` | `LlmProvider` |
| `ArtemisProviderPermitsConfiguration` | `LlmProvider` |

The current package/class-pair ratchet, not this historical P08 inventory, owns
the allowed dependencies of the complete application. The original dependencies
implement the port, bind explicit provider aliases and preserve
the analysis cancellation contract. JMS and Artemis remain in the application
adapter; the analysis module has no dependency on this implementation. The entry
records this reviewed composition direction without changing architecture rules,
selectors, thresholds or cycle exceptions. The independent P08 branch contains no
metrics adapter, so subsequent composition changes must review their own edges.

## Executable checks

Run from the repository root with Java 21:

```bash
./mvnw -B -pl taxonomy-app -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=ProviderConcurrencyGatewayTest,ProviderPermitRateReservationTest,ArtemisProviderConcurrencyPermitsTest,ArtemisProviderPermitsConfigurationTest \
  test
```

`ProviderConcurrencyGatewayTest` covers production-registry gateway injection,
retry acquisition/return order, cooperative cancellation, fail-closed capacity
and replay bypass for both gateway families. `ProviderPermitRateReservationTest`
uses a controlled monotonic clock to prove an expired reservation and a 429
received during cluster waiting cannot bypass local RPM/backoff admission.

`ArtemisProviderConcurrencyPermitsTest` uses a disposable **persistent TCP Artemis
broker** and a real loopback HTTP server. It exercises two independent connections,
explicit aliases sharing N=2 capacity with eight calls, bounded/cancelled waits,
cancellation at delivery, holder connection loss, broker restart including an
outstanding permit, concurrent/repeated provisioning, an interrupted-initialization
empty queue, and durable metadata-only tokens. No JMS mock or paid LLM is involved.

`ArtemisProviderPermitsConfigurationTest` checks defaults, invalid mappings,
explicit alias binding, and Spring injection into the production registry.
These focused checks are not a replacement for the full CI, external-database,
browser or constrained-cluster acceptance lanes.

On 2026-10-04, the focused `package` run including these checks plus
`GeminiGatewayTest`, `OpenAiCompatibleGatewayTest`, `LlmGatewayRegistryTest` and
`AnalysisAdmissionPolicyTest` passed **100 tests** (88 analysis, 12 application),
with zero failures/errors/skips. The real HTTP fixture completed eight calls
across two independent gateway registries/connections at peak concurrency two,
and restored exactly two durable tokens. The packaged `PropertiesLauncher`
entry point was also checked without arguments: it reached the provisioner's
usage validation without starting Spring or contacting a broker.

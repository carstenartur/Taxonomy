# Cluster provider concurrency permits

The P08 provider boundary uses `ProviderConcurrencyPermits` in `taxonomy-analysis`.
`LlmGatewayRegistry` injects the optional implementation into every Gemini and
OpenAI-compatible gateway. Every physical attempt, including a retry, acquires
capacity after local admission and returns it before retry backoff. Recording
replay makes no HTTP attempt and acquires no permit.

`ArtemisProviderConcurrencyPermits` in the application adapter receives one durable
message in a transacted JMS session. Completion sends one replacement carrying
the same permit ID and commits the send and original consumption together. An
uncommitted or lost holder rolls back; a failed or uncertain commit never triggers
a compensating send. Capacity is determined by the durable token count, not by the
number of application pods.

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
permission; application consumers only need send/consume access to their queues.

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
and non-dropping address-full policy. The broker transaction timeout must exceed
the maximum physical HTTP duration. A permit message contains only schema version,
quota group and a random permit ID; no prompt, response, API key or task payload.

RPM remains **process local**. After a cluster wait, the local limiter revalidates
its reservation and any concurrent `Retry-After` before the physical attempt. If
local admission is no longer valid, it returns the cluster permit before waiting
again. The configured local maximum queue wait bounds the complete admission
attempt across both stages and repeated revalidation; the cluster maximum wait
adds its own limit. Failed or cancelled cluster acquisition refunds the unused
local RPM reservation. This mechanism makes no claim of cluster-wide RPM, TPM or
RPD enforcement.

Cancellation/deadline checkpoints run before and after bounded 100 ms JMS receives.
Broker connection/control calls remain bounded by the existing Artemis connection
timeouts. No broker-backed semaphore can fence a request already accepted by an
external provider: if a broker connection is lost while the HTTP peer continues
work, token redelivery alone cannot prove that upstream work has stopped. Strict
upstream execution fencing across network partitions needs provider support.

## Architecture boundary

The application composition layer implements the analysis-owned
`ProviderConcurrencyPermits` port. The dependency baseline registers exactly eight
direct class pairs from `com.taxonomy.composition.analysis.artemis` to
`com.taxonomy.analysis.service`:

| Application class | Analysis types |
| --- | --- |
| `ArtemisProviderConcurrencyPermits` | `ProviderConcurrencyPermits`, `ProviderConcurrencyPermits.Permit`, `ProviderConcurrencyPermits.UnavailableException`, `LlmProvider`, `AnalysisStoppedException` |
| `ArtemisProviderConcurrencyPermits.TransactionalPermit` | `ProviderConcurrencyPermits.Permit` |
| `ArtemisProviderPermitSettings` | `LlmProvider` |
| `ArtemisProviderPermitsConfiguration` | `LlmProvider` |

These dependencies implement the port, bind explicit provider aliases and preserve
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

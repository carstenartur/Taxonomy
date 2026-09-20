# Sparx XMI implementation validation

Date: 2026-09-19. Baseline: `0bd708eb965e630fa60218df0fce136958dea688`.
Implementation commit: `d083e8f0734557f6b018520c4298fa121a0badde`.
The implementation is an experimental delivery slice of #1075, based on the
synthetic fixture described in `taxonomy-export/src/test/resources/interoperability/sparx/ORIGIN.md`.

## Focused regression evidence

All **56 selected tests passed** with Maven 3.9.16 and Java 25.0.2, compiling for
Java 21. These comprise:

| Suite | Tests |
|---|---:|
| Sparx XMI codec | 11 |
| Sparx native projection and export binding | 6 |
| Sparx identity conflicts | 2 |
| Sparx persisted application workflow | 5 |
| Existing exchange standards, three-way diff, application, restart and architecture projection | 32 |

The independent review findings were reproduced and corrected: reverse direction,
historical UUID ownership, native export binding in the presence of loss reports,
canonical type-tag updates, and explicit connector-end loss reporting. The persisted
deletion regression verifies that tombstones retain identity ownership and that
`TAKE_EXTERNAL` cannot override another GUID's reserved UUID.

JavaScript syntax, compatibility JSON parsing and Git whitespace checks passed.
The focused run selects these nine suites; it does not replace the complete
repository gate.

## Complete repository gate

Required command: `./mvnw verify -DexcludedGroups="real-llm"`.
An initial run could not initialize Mockito's inline mock maker in the container
because external JVM self-attachment failed. Starting the installed Mockito 5.23.0
premain agent explicitly through `JAVA_TOOL_OPTIONS` resolved that environment
failure. No test selectors, exclusions or repository gates were weakened.

Final outcome: **BUILD FAILURE** after 13 minutes 48 seconds. Across the completed
unit-test phases, 4,778 tests ran: 4,769 passed, with 5 failures and 4 errors in
the two existing ONNX suites. All 24 new Sparx tests passed in this full run too.

`OnnxEmbeddingServiceTest` (4 errors) and `OnnxRestEndpointTest` (5 failures)
require the pinned local embedding model. It is absent in this environment, and
these tests explicitly disable downloading it (`embedding.allow-download=false`).
No `.onnx` model or tokenizer was found in the available scratch workspaces.
The later Failsafe/container and aggregate-quality phases were not reached.
The complete gate must run in CI with its pinned model preparation before merge;
the selected regression run is not a substitute for that gate.

## Product acceptance

No actual EA or PCS execution was available. Neither the passing fixture tests nor
the application tests establish real-product compatibility. The authoritative
unexecuted product matrix and remaining OSLC acceptance items are recorded in
[sparx-compatibility.json](sparx-compatibility.json).

## Semantic version 2 — Task 1 (2026-09-20)

Review base: `3ca66eed8cbc9a85b7151b4361b38f95bc6afc90`. This slice adds
shared AM/XMI feature semantics, frozen profile-version dispatch and bounded,
complete AM collection traversal. Native package editing and requirement
projection remain subsequent work. The v2 fixtures are handwritten contracts;
real EA/PCS compatibility remains `NOT_EXECUTED`.

Final focused verification compiles for Java 21 on the local JDK 25 runtime:

```sh
TAXONOMY_EMBEDDING_MODEL_DIR=/workspace/scratch/dae1667028d4/civilian-models/bge-small-en-v1.5 TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false python /workspace/scratch/dae1667028d4/verify-repo.py -pl taxonomy-app -am test -Dtest=SparxXmiCodecTest,SparxOslcAmCodecTest,SparxOslcAmReaderTest,ExchangeConnectorRegistryTest,IntegrationRemoteRetryTest,SparxIntegrationFlowTest -Dsurefire.failIfNoSpecifiedTests=false -DexcludedGroups=real-llm
```

**BUILD SUCCESS: 72 selected tests passed** (19 XMI codec, 14 AM codec,
24 AM HTTP reader, 2 registry, 5 durable retry and 8 app flow cases).
The selection covers shared feature/connector identities, deterministic XMI
roundtrips, duplicate tags, owner/position/prefix validation, complete collection
sets, every collection's HTTP pagination and later-page failure, aggregate bounds,
credential/URI guards, exact profile versions and feature-only stale apply.

A clean owning-reactor attempt (`-pl taxonomy-app -am clean test`, no test selector)
passed all completed upstream modules, including 78 interop tests, then failed in
portfolio test discovery with
`Unable to create test class 'com.taxonomy.portfolio.service..rsync-tmp.SolutionCatalogPolicyContractTest'`.
Portfolio ran no tests and app was not reached. The earlier clean attempt exposed
old one-argument registry mocks in the retry suite; those Task 1 failures were
corrected and covered in the final focused selection. Both failed logs are retained
in the task workspace. The controller stopped further full-reactor retry loops
for the diagnosed transient discovery condition; the broader gate remains
incomplete. The separately confirmed Word-foundation dependency-ratchet failure
also remains a controller-owned gate; no baseline was changed in this task.

Detailed RED/GREEN logs, API changes and review notes are recorded in
`.superpowers/sdd/2026-09-20-sparx-semantic-completion/task-1-report.md`.

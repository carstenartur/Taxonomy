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

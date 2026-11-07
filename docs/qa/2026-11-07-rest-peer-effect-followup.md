# REST effect follow-up on the concurrent peer implementation — 2026-11-07

Base: `3dbc8a5a54ab08975e5ffd0de9cee98a9139ccc1`. The earlier local
`2a030dc36a8e72242b73990f708e1570e79d2e42` checkpoint is not the integration source.
This delta retains the peer's permission ports, conditional imports, optional-automation
guards, workspace consumers, explicit Resume UI and coordinator/finalization race fixes.

## Changes

- Exact source/editor focus and document AI extraction/regulation-mapping operations require `analysis:run`. Focus's optional `useAi` is part of the complete operation; deterministic requests use the same execution capability.
- The five input-reanalysis diagram routes require both `analysis:run` and `integration:export`. Existing-view exports remain export-only; score downloads are classified before the broad score-read rule.
- A successful explicit pass continuation admits retained-operation finalization through the existing stored-ID resume API when the account has both business-write and execution capabilities. Analysis-only continuation stays observational afterward. Failed or uncertain commands are observed without automatic replay.
- Configured connector publication keeps the peer's `architecture:publish` permission, authorized persisted retry direction and resource checks. It is a reviewed business action using server-configured profiles/credentials. Workspace external push/full-sync retains publication plus technical administration.
- No layout, strings, styles, CSRF configuration, permission port or coordinator implementation changes are included.

## Verification

All checks use existing Maven/JUnit owners with JDK 25 and disabled Maven build cache.
Local logs are under `target/`; passing counts contain no failures, errors or skips.

| Evidence | Result |
|---|---|
| `rest-peer-effect-red.log` | 71 HTTP cases, 10 expected assertion failures before the exact capability fixes: four model investigations, five reanalysis exports and one score-download scope. |
| `rest-peer-continuation-red.log` | 45 JavaScript callbacks, four expected failures before explicit continuation-finalization admission. Existing peer recovery/uncertain-response cases remain present. |
| `rest-peer-continuation-green.log` | BUILD SUCCESS; all 45 callbacks pass, including EN/DE admission, unchanged stored identity, analysis-only restrictions and uncertain-response observation. Required build-policy owners also pass. |
| `rest-peer-boot-and-boundaries.log` | BUILD SUCCESS; 182 tests: workspace reads 31, real publication HTTP 4, request policy 71, retained operation port 13, conditional imports 13, OpenAPI access 44, generated OpenAPI 1, documentation inventory 3, Swagger 2. |
| `rest-peer-full-javascript.log` | BUILD SUCCESS; all 2,338 JavaScript callbacks, both source-ownership checks and the standard command's required build-policy owners pass; no failures/errors/skips. |

The real publication HTTP owner retains the peer's positive configured-connector permission
cases and proves the exact requested workspace is used when another owned copy is active.
Foreign, missing and unready pins and missing retained addresses do not produce remote writes.
The provider is the existing local contract fixture, not a live external system.

Independent review found no remaining Important/P2 in the narrow delta, independently reran
all 45 continuation callbacks and inspected the passing 182-case application evidence.
Reviewed production blobs: policy `9501c2eef91334602b8d30199061a794def9edf0`,
client `56c6be67682c190ce7763421c89e1bf3d6c3c44e`.
The coordinator composes the separately reviewed CSRF leaf and owns publication. This record
does not claim full CI, native-browser, live Keycloak, real provider or EA/PCS execution.

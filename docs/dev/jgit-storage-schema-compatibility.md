# JGit Core schema compatibility

Taxonomy classifies the physical `jgit-storage-hibernate` Core schema before Flyway is allowed to migrate it. The classifier is deliberately fail-closed: it accepts only exact released table shapes and rejects partial or unknown combinations.

## Reflog schema generations

| Core migration | Required reflog columns added | Taxonomy behavior |
|---|---|---|
| before `0.9.1` | none | accepted only as the exact legacy released shape and advanced by Flyway |
| `0.9.1` | `REF_NAME_KEY` | accepted only with the matching current pack schema |
| `0.9.2` | `DELIVERY_ID` in addition to `REF_NAME_KEY` | accepted only with the matching current pack schema |

For a managed schema, migration history and physical columns must agree. `REF_NAME_KEY` requires migration `0.9.1` or a later exact baseline; `DELIVERY_ID` requires migration `0.9.2`. An unversioned but otherwise exact schema is baselined at the precise detected generation so Flyway does not reapply an already represented DDL change.

This contract lets Taxonomy run both with the current `jgit-storage-hibernate` release and with the `0.9.2` schema candidate used by the upstream real-consumer compatibility matrix, without weakening rejection of unknown future shapes.
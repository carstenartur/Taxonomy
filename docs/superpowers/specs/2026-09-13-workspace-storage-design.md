# Workspace-owned JGit storage

This is D5b of #1043/#628, following the application-schema separation in D5a.
The binding request is the workspace ownership decision and explicit storage
relocation in https://github.com/carstenartur/Taxonomy/issues/628, constrained by
https://github.com/carstenartur/Taxonomy/issues/1043. The user has authorized
implementation and continuation of that architecture work.

## Ownership

Move the eleven production classes currently in
`com.taxonomy.dsl.storage` to `com.taxonomy.workspace.storage` inside
`taxonomy-app`. Workspace already owns repository identity, version authority,
semantic operations and checkpoint publication. Its existing read/version/
publication ports remain the contracts. The application Flyway composition
introduced by D5a stays in `composition.persistence`.

The alternatives considered were moving all adapters into a generic adapter
module, or leaving forwarding classes at the old package. Both retain the
ownership problem and conflict with the issue's stated architecture. Direct
relocation to the existing workspace boundary gives these implementations the
correct owner and removes the storage-only DSL adapter exception. Physical
Maven extraction remains a subsequent decision based on the measured graph.

## Global Constraints

- Change production Java only by the eleven package relocations and exact imports/FQCN references needed by them. Preserve methods, visibility, annotations, constructors, bean names, data records, control flow, logging, counters and metric names after namespace normalization. No forwarding classes, compatibility shim, new port or implementation redesign.
- Preserve repository/workspace/branch identity, system-versus-selected repository routing, semantic operations separate from Git checkpoints, exact-head CAS/conflicts, retry/recovery, merge/diff/version behavior and every existing test assertion. Preserve old negative ownership assertions for types that are not moved in this slice.
- Preserve all SQL resources/checksums, tables, indices, history/adoption rules, database-family support, Flyway enablement and strategy order. The qualified application strategy and its ten integration tests stay in composition.persistence. No schema, DDL, mapping, transaction or bootstrap behavior change.
- Move the existing storage coverage protection to com/taxonomy/workspace/storage with LINE 0.87 and BRANCH 0.71 and the corresponding changed-source prefix. Retain every other package floor and the 0.75/0.60 changed-source minimums, including independent composition/persistence protection.
- Remove only the obsolete com.taxonomy.dsl.storage.. context/exemption scope. Workspace is classified by the existing com.taxonomy.workspace.. prefix. Keep remaining DSL export exceptions, all exception IDs/owners/expiry dates and all unrelated context mappings. Never extend exemptions to workspace.storage or introduce a new waiver.
- Preserve every existing architecture selector and add the storage ownership guard identically to pom.xml and .mvn/verification-suites.json. Preserve mandatory cycle and exact dependency-ratchet enforcement. Update the baseline only from freshly compiled production bytecode with every changed edge explained.
- Update real runtime and build consumers: both Hibernate schema-filter FQCNs, both OpenTelemetry method targets and the factory Micrometer key, five moved consumer report paths, and new storage source/test workflow filters. Retain existing commands, report requirements, old source filters, Docker/CI requirements and all unrelated instrumentation.
- Work only in /tmp/taxonomy-d5b-workspace-storage-20260913. The implementer must not run Maven, mutate Git, publish, edit another worktree or spawn subagents. Root owns one serial native Maven reactor, measured baseline/coverage evidence, independent review, commits and publication.

## Compatibility and evidence

The public HTTP and persisted contracts do not change. The internal Java
namespace and checked-in Hibernate configuration change together. All source,
resource, reflection, observation and evidence-path consumers must be audited;
a blanket replacement of every old prefix is incorrect because historical
negative ownership assertions and previous stage documents can remain valid.

Move the seventeen existing storage test/support files with their assertions
intact. Reuse the real HSQLDB storage/in-memory Git contracts, migration strategy
tests, repository/workspace isolation tests and observation configuration
reflection tests. PostgreSQL and the other database lanes remain independent
canonical CI gates.

A new non-vacuous ownership guard requires all eleven classes at their new
names and rejects production classes in the old storage package. Its honest
RED must run against the actual pre-move output; GREEN must use freshly compiled
post-move output without old application classes leaking onto the classpath.

The D5a baseline has 537 cross-context class pairs and 146 package edges;
workspace's 42 outgoing pairs all target historical DSL storage, and 117 pairs
run from knowledge to workspace. These are starting measurements, not promised
post-move results. Root measures the new graph, enumerates remaining workspace
outgoing and knowledge incoming edges, and records what still blocks extraction.

No full local or remote verification success is inferred from focused tests.
The current local environment has no Docker and its pinned ONNX cache is absent;
do not suppress tests or alter embedding behavior to disguise that limitation.
Run covering existing contracts locally and require canonical CI before merge.

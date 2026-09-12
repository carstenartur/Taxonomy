# Context module extraction gate

`ArchitectureModuleExtractionTest` runs with the ordinary application tests.
It reads `.github/architecture-contexts.json`, discovers reactor POMs and
production source files, and imports the compiled production classes with
ArchUnit. It projects their direct class dependencies onto the proposed Maven
modules. It does not read the dependency ratchet baseline or the exception ledger.

Run the normal test suite with `./mvnw test`. The authoritative CI command remains
`./mvnw verify -DexcludedGroups="real-llm"`. The generated report is printed in
the test output and written to
`taxonomy-app/target/architecture-module-graph.txt`.

The report includes every class-derived module edge with a deterministic
representative class pair, declared production POM edges, application dependencies,
one cycle witness and all members of each cyclic group, and an extraction
assessment for every planned feature module. Both the evaluator and its real
bytecode/source adapter have fixtures. A missing or empty production inventory
cannot produce a successful extraction assessment.

## When enforcement applies

The complete proposal is evaluated on every run. Before physical extraction,
existing proposal cycles are reported as blockers; they do not make unrelated
application changes fail. There is no assertion that the current complete
proposal is already a DAG, and no baseline, waiver, property, or skip flag for
the extraction gate.

A target module POM in the reactor automatically makes its assessment mandatory.
Profile-declared modules are included. A target POM present outside the declared
reactor fails discovery, so omitting it from `<modules>` does not evade the gate.

Extraction means moving the whole mapped context. A present feature module must
contain production classes, and all classes assigned to its target must have
left `taxonomy-app`. Adding an empty POM or leaving part of its context in the app
fails. The gate rejects any dependency path from an extracted module that:

- returns to `taxonomy-app`;
- reaches an unextracted context owner or a class still physically owned by the app;
- reaches a cycle, including a cycle further down its dependency chain.

A disconnected cycle among unextracted contexts is still reported without
blocking an otherwise independent safe extraction. Internal production POM
dependencies supplement the class graph, including runtime and profile
dependencies; test-only and dependency-management declarations are not production
edges. This also catches an unused POM dependency that points back to the app.

The POM projection follows matching local parent POMs, including the default or
explicit `relativePath` and reactor-coordinate lookup. It inherits properties,
dependencies, and dependency management before resolving expressions. Child
properties and explicit dependency scopes override inherited values; omitted
scopes use local or inherited managed scopes before defaulting to `compile`.
The `project.groupId`/`pom.groupId` aliases and corresponding artifact, version,
and parent coordinate aliases resolve in the child model. Raw inheritance keys are preserved before interpolation, matching
[Maven's model-building order](https://maven.apache.org/ref/3.9.16/maven-model-builder/index.html).

Unresolved expressions that could identify an internal group, or that affect an
internal dependency's coordinates or scope, fail explicitly. Profile-dependent
property or managed-scope choices also fail when they could change an internal
edge; a profile-only managed `test` scope cannot hide the base `compile` default.
External-parent and imported-BOM inheritance is outside this local projection and
is not fetched or inspected. New internal edges or scope settings supplied through
those mechanisms require extending the adapter before extraction.

## Ownership and fail-closed behavior

Root composition classes are the explicit `rootCompositionClasses` entries,
including their nested classes. Contexts with a null or missing `targetModule`
remain application-owned and are named as unresolved in the report.

The existing `taxonomy-domain`, `taxonomy-dsl`, `taxonomy-export`,
`taxonomy-extension-api`, and `taxonomy-tooling` libraries keep exact physical
class ownership. Their real dependencies remain in the graph. In particular,
existing library classes under shared package roots are not mistaken for app
composition classes, and app adapters under DSL/export roots are not excluded.

Unmapped classes, invalid or duplicate physical owners, overlapping package
assignments, missing reactor POMs, and source files missing from imported bytecode
fail even before extraction. Stale compiled classes with no current source fail
with a request for a clean reactor build. The source/bytecode inventory uses the
repository's current `src/main/java` and `target/classes` module layout; a future
layout change must update this inventory explicitly.

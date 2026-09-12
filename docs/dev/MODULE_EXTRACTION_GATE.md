# Context module extraction gate

`ArchitectureModuleExtractionTest` runs with the ordinary application tests.
It reads `.github/architecture-contexts.json`, discovers reactor POMs and
production source files, and imports the compiled production classes with
ArchUnit. It projects their direct class dependencies onto the proposed Maven
modules. It does not read the dependency ratchet baseline or the exception ledger.

Run the normal test suite with `./mvnw test`. The focused architecture command is
`./mvnw test -Parchitecture-tests -Dsurefire.failIfNoSpecifiedTests=false`; its
Maven-owned selection includes both gate test classes. The canonical CI command
is `./mvnw -B verify -Pci`. Core CI adds `-DrunOnnxTests=true -Dtaxonomy.ui.skip=true`
and runs UI verification in separate lanes. Plain local `verify` skips integration
and post-reactor gates and is not equivalent to CI. The generated report is
printed in the test output and written to
`taxonomy-app/target/architecture-module-graph.txt`.

Run the architecture suite from the repository root across the complete reactor.
An app-only `-pl taxonomy-app` invocation does not compile the sibling outputs
required by this inventory.

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
Discovery resolves declared and unregistered property-based artifact IDs through
the same local POM model described below. Declared artifact IDs must resolve
uniquely; constant reactor coordinates are collected first so local parent lookup
does not depend on their declaration order. An unresolved expression that could name an unregistered planned feature
also fails discovery; it cannot make an extraction attempt invisible.
Directory symlinks are checked against the checkout before traversal, including
aliases into otherwise excluded build directories. Safe aliases use the same POM
file identity as the declared reactor entry; directory-link cycles fail discovery.

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
Dependency-management matching uses effective group, artifact, type, and classifier
coordinates on both sides; unresolved internal coordinates fail before matching.
The `project.groupId`/`pom.groupId` aliases and corresponding artifact, version,
and parent coordinate aliases resolve in the child model. Raw inheritance keys are preserved before interpolation, matching
[Maven's model-building order](https://maven.apache.org/ref/3.9.16/maven-model-builder/index.html).

Reactor parent identity includes both group and artifact; an external parent may
share a reactor artifact name. Unknown `com.taxonomy` parents still fail closed.
Registered reactor parents are also matched through their resolved artifact IDs;
a literal reference to a property-based parent name retains its inherited edges
and managed scopes after the effective group and artifact are checked.
Every POM path is checked against the checkout before content is read, including
normalized traversal and symlink targets. A `relativePath` outside the checkout
fails even when Maven would allow it; symlinks that remain inside are permitted.
Use Maven's explicit empty `<relativePath/>` for an external parent with no local
lookup. External-parent content remains outside this projection.

Declared parent coordinates must resolve before any external-parent fallback;
unknown or cyclic expressions fail immediately. Matching local parents require
exact versions. A Maven version range on a matching local parent fails explicitly,
so unsupported range selection cannot silently discard inherited dependencies or
managed scopes.

Unresolved expressions that could identify an internal group, or that affect an
internal dependency's coordinates or scope, fail explicitly. Profile-dependent
property or managed-scope choices also fail when they could change an internal
edge; a profile-only managed `test` scope cannot hide the base `compile` default.
Parent-candidate filtering preserves this same profile uncertainty. Identical raw
parent group or artifact expressions are retained as possible local matches; if their
parent-only and child resolutions disagree, the adapter fails explicitly instead
of discarding inheritance. This projection does not guess a profile activation
state or implement Maven's complete context-dependent parent interpolation.
External-parent and imported-BOM inheritance is outside this local projection and
is not fetched or inspected. New internal edges or scope settings supplied through
those mechanisms require extending the adapter before extraction.

## Ownership and fail-closed behavior

Root composition classes are the explicit `rootCompositionClasses` entries,
including their nested classes. Contexts with a null or missing `targetModule`
remain application-owned and are named as unresolved in the report.

The existing `taxonomy-domain`, `taxonomy-dsl`, `taxonomy-export`,
`taxonomy-extension-api`, and `taxonomy-tooling` libraries keep exact physical
class ownership. A support module named by a production POM edge must also be
present in the reactor; a cached dependency artifact is not evidence of ownership.
Their real dependencies remain in the graph. In particular,
existing library classes under shared package roots are not mistaken for app
composition classes, and app adapters under DSL/export roots are not excluded.

Unmapped classes, invalid or duplicate physical owners, overlapping package
assignments, missing reactor POMs, and source files missing from imported bytecode
fail even before extraction. Before importing bytecode, the running JDK compiler
compiles the current production sources once into an in-memory inventory of
binary names and source modules. Class bytes are discarded and no source or
build output is written. The inventory must match the actual compiled classes;
an obsolete nested or additional top-level class fails even when its original
source file still exists or timestamps match. The compiler also accounts for
legitimate local, anonymous, and synthetic classes without guessing their names.
Missing or obsolete binaries fail with a request for a clean reactor build.
Every reactor module's existing output directory is inspected, even when its
source directory is absent or contains no Java sources. Genuinely empty support
and POM modules remain valid; their leftover binaries do not.

This pass uses Java 21 and the complete `surefire.test.class.path` (falling back
to `java.class.path` outside Surefire), plus reactor class directories. Annotation
processing and implicit source compilation are disabled. The inventory uses the
current `src/main/java` and `target/classes` module layout; generated source
layouts, annotation-generated classes, or new compiler options require explicit
adapter support. Compilation errors fail the gate. The pass validates binary
declarations; normal reactor compilation remains responsible for compiling the
current method bodies that ArchUnit inspects.

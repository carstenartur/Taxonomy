# Context module extraction gate

`ArchitectureModuleExtractionTest` is an ordinary Surefire test owned by
`taxonomy-build`, downstream of `taxonomy-app`, `taxonomy-coverage`, and
`taxonomy-tooling`. In a full-reactor test or verify it reads
`.github/architecture-contexts.json`, discovers reactor POMs and production
source files, and imports the compiled production classes with ArchUnit. It
projects their direct class dependencies onto the proposed Maven modules. It
does not read the dependency ratchet baseline or the exception ledger.

Run the normal test suite with `./mvnw test`. The focused architecture command is
`./mvnw test -Parchitecture-tests -Dsurefire.failIfNoSpecifiedTests=false`; its
Maven-owned selection includes all three module-gate classes:
`ArchitectureModuleGraphTest`, `ArchitectureModuleExtractionTest`, and
`ArchitectureSelectorSynchronizationTest`. The canonical CI command
is `./mvnw -B verify -Pci`. Core CI adds `-DrunOnnxTests=true -Dtaxonomy.ui.skip=true`
and runs UI verification in separate lanes. Plain local `verify` skips integration
and post-reactor gates and is not equivalent to CI. The generated report is
printed in the test output and written to
`taxonomy-build/target/architecture-module-graph.txt`.
The owner fixture requires the unclassified direct coordinates `com.taxonomy:taxonomy-app`,
`com.taxonomy:taxonomy-coverage`, `com.taxonomy:taxonomy-tooling`, and
`com.tngtech.archunit:archunit-junit5`, together with their expected type and
scope. A same-named artifact from another group or a classified variant cannot
satisfy the contract; unrelated dependencies remain permitted.

Run the architecture suite from the repository root across the complete reactor.
App-only commands, including the documented Keycloak-only selection, do not run
this whole-repository test. They remain supported focused lanes; ordinary
full-reactor verification and the complete architecture profile enforce the
module graph after every inventory producer has been compiled.

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
file identity as the declared reactor entry; duplicate and cyclic declarations
are detected by physical POM identity while ownership and errors retain the
declared path. Directory-link cycles fail discovery.

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
and managed scopes after the effective group and artifact are checked. The same
effective-coordinate check applies to a non-reactor local parent reached through
`relativePath`; a different effective coordinate is not inherited.
Every POM path is checked against the checkout before content is read, including
normalized traversal and symlink targets. A `relativePath` outside the checkout
fails even when Maven would allow it; symlinks that remain inside are permitted.
Local-parent recursion uses physical POM identity only for the active resolution
chain, after this boundary check. Logical paths remain the cache, diagnostic, and
relative-resolution paths, and a shared acyclic parent can be reused after its
active resolution has ended.
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

Root composition ownership follows the exact originating source files named by
`rootCompositionClasses`, as reported by the compiler. This includes genuine
nested, local, anonymous, and synthetic classes produced from an allowed source.
A distinct top-level source whose file name contains `$` needs its own explicit
entry and cannot inherit another file's allowance. Contexts with a null or
missing `targetModule` remain application-owned and are named as unresolved in
the report.

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
compiles the current production sources once, retaining binary names, source
modules, exact source-file names and fresh class bytes in a temporary directory.
ArchUnit derives dependencies from these fresh bytes, not from possibly stale
same-named classes in `target/classes`. The temporary output is removed on both
success and failure; reactor source and build outputs are not modified. The
binary-name inventory must still match the existing compiled classes;
an obsolete nested or additional top-level class fails even when its original
source file still exists or timestamps match. The compiler also accounts for
legitimate local, anonymous, and synthetic classes without guessing their names.
Missing or obsolete binaries fail with a request for a clean reactor build.
Every reactor module's existing output directory is inspected, even when its
source directory is absent or contains no Java sources. Genuinely empty support
and POM modules remain valid; their leftover binaries do not.

The architecture policy path, including each linked ancestor, must resolve inside
the checkout before its content is read. Every consumed production source root,
source file, output root, and output file must likewise resolve inside the
checkout before javac or ArchUnit can use it. This check applies independently of
POM discovery, including source packages named `target`, individual linked class
files, and linked output directories. Safe file and directory aliases retain
their logical checkout paths for package and physical-owner mapping. Linked
directories are checked before descent, and directory cycles fail explicitly.
The fixed architecture report path and each existing ancestor are likewise
checked before its parent directory is created or content is written. Report
file and directory aliases whose targets remain in the checkout are permitted;
an alias outside the checkout is rejected without writing its target.

This pass uses Java 21 and the complete `surefire.test.class.path` (falling back
to `java.class.path` outside Surefire), plus reactor class directories. Annotation
processing and implicit source compilation are disabled. The inventory uses the
current `src/main/java` and `target/classes` module layout; generated source
layouts, annotation-generated classes, or new compiler options require explicit
adapter support. Compilation errors fail the gate. The pass validates binary
declarations; normal reactor compilation remains responsible for compiling the
current method bodies that ArchUnit inspects.

Selector inputs (`pom.xml` and `.mvn/verification-suites.json`) must resolve
inside the checkout before XML or JSON is read, including linked ancestor
directories. The exact ordered selector, owner paths and Java declarations are
also checked by the independent ordinary `ArchitectureModuleGraphTest` owner
contract, not just by the synchronization test's own selected execution.


The architecture profile also binds the pre-existing ledger guard through the
fixed `architecture-selector-anchor` Surefire execution in `taxonomy-app`.
It requires the three module-gate entries independently of the mutable selector
lists, and checks each corresponding source path and top-level Java declaration.
Thus deleting all downstream gate classes cannot turn the profile into a silent
success. These source files must physically remain inside the checkout before
parsing; contained aliases remain valid. The complete ordered selector and all
other selected guards remain the downstream synchronization check's responsibility.


## Required guard execution

The upstream selector anchor checks that `taxonomy-build` is an unconditional,
unique root reactor member with the `jar` lifecycle. It validates three fixed
Surefire executions in that owner POM, one per module guard, each failing when
its selected class produces no tests. This is an execution contract rather than
an assertion that a correctly named source file is necessarily a runnable test.
The ordinary build-module test scan excludes these three classes to avoid
running them twice in normal CI; additional build tests remain in that scan.
Explicit focused Maven test selectors may also select them in the default
execution, but cannot remove the required executions or their fail-on-empty
settings. The existing source-declaration and checkout-containment checks remain.
No feature module or application production code depends on build-test classes.

The required executions and app anchor use literal `<test>` and fail-on-empty
values, not `${test}` or `${surefire.failIfNoSpecifiedTests}` expressions.
Maven CLI user properties therefore do not replace these execution values; see
[Apache Maven MNG-4979](https://issues.apache.org/jira/browse/MNG-4979).
An executed [six-case Maven experiment](https://github.com/carstenartur/Taxonomy/actions/runs/35184566936)
copies the unchanged owner configuration and passes `-Dtest=UnrelatedTest`,
`-Dsurefire.failIfNoSpecifiedTests=false`, and `-Dsurefire.failIfNoTests=false`.
Both healthy fixtures still run the required guards or app anchor; making each
of the three guard classes or the anchor empty makes Maven fail. This experiment
checks configuration precedence and execution, not just the effective XML.

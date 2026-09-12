# Module gate checkout and reactor ownership follow-up

> **For agentic workers:** Use `superpowers:subagent-driven-development` for this bounded PR1054 review follow-up. This is root-owned planning provenance.

**Goal:** Close confirmed checkout-boundary gaps and keep the mandatory module extraction gate in a genuinely downstream reactor owner without breaking supported application-only selections.

**Base:** Published PR1054 head `82f7865a9a3ec6d9b8dd116fe615be631ba17847`, tree `c318e71e4b96243e38e59aa09129bebe85f98263`, already integrated with main `746e1ce6d9c33eabef1cff06618e8802876949a2`.

**Binding evidence:** Root's `../gate82-current-findings.md` records all three current review items. `../gate82-diagnostic-report.md` records independent exact-head adapter probes and real Maven ModelBuilder comparisons. Read that report before implementing. Normal external Java source links were already rejected by POM discovery, but source links below a package segment named `target`, external compiled class links and external output-root links can pass the current adapter. Property-artifact reactor parent aliases match actual Maven in file/directory/registration alias probes, including runtime and managed-test dependencies; that reported bypass is disproved, not a reason to change effective-model behavior.

## Global constraints

- Work only in `/workspace/scratch/5f847bdd1880/Taxonomy-module-gate`. No Maven, commit, push, other worktree edits or subagents by implementer. Root owns serial native verification, integration, review and publication.
- Preserve all 83 existing fixture invocations and all existing assertions. Preserve the real repository gate as an ordinary mandatory Surefire test in full reactor verify and the complete architecture profile. No tags, skips, exemptions, opt-in enforcement, stale-class acceptance, reduced context coverage, or test-only ownership escape.
- Preserve source/binary declaration identity checking, in-memory javac, absence of annotation processing, support-module actual-presence checks, bounded fail-closed effective-POM rules and existing independent-extraction semantics.
- Do not change production classes, context map, baseline, cycle exceptions, coverage policy, workflow commands or exact-head/human review rules. Test-scoped build dependencies must not create new runtime graph edges.
- Only concrete failing probes justify adapter behavior changes. Retain safe in-checkout aliases that current tests already support; never fetch external parent/BOM models or weaken checkout containment.

## Task 1: Harden checkout inputs and move the gate to build policy

Move these three unchanged-package test sources from `taxonomy-app/src/test/java/com/taxonomy/` to `taxonomy-build/src/test/java/com/taxonomy/`:

- `ArchitectureModuleGraph.java`
- `ArchitectureModuleGraphTest.java`
- `ArchitectureModuleExtractionTest.java`

Keep package and test names, so both existing architecture selectors remain complete and synchronized without renaming. Add `archunit-junit5` with the existing `${archunit.version}` and test scope to `taxonomy-build/pom.xml`; add test-scoped `taxonomy-tooling` at `${project.version}`. Build already depends on app and coverage; the tooling edge makes build downstream of the other independent support module for `-am` and parallel scheduling. No application dependency is removed: app's other architecture tests still use ArchUnit.

Within `ArchitectureModuleExtractionTest`, enforce actual checkout containment for source roots/files and production output roots/files before they reach javac or ArchUnit. Reuse the existing file-identity/real-path boundary semantics where appropriate. The check must not rely on POM discovery visiting a package directory named `target`. External class symlinks and an external linked output directory must fail explicitly before imported declarations affect the measured graph. Keep ordinary legitimate sources/outputs and supported in-checkout aliases working. Do not modify parent-coordinate resolution merely to appease the disproved alias review item.

Move the generated report to `taxonomy-build/target/architecture-module-graph.txt` and update actual direct owner/path/command documentation in `README.md`, `docs/dev/06-testing-by-change-type.md`, `docs/dev/MAVEN_VERIFICATION.md`, and `docs/dev/MODULE_EXTRACTION_GATE.md`. Explain ordinary downstream full-reactor enforcement and supported app-only selections; no claim that standalone app tests enforce the whole-reactor graph. Root will record measured new counts after native verification. Do not invent successful runs.

Add cohesive regression fixtures for confirmed escape cases and safe in-checkout controls. Preserve property-artifact-plus-alias runtime and inherited managed-test behavior with explicit regression coverage grounded in the independent ModelBuilder probes, without changing its already-correct adapter resolution. Include a build-owner contract or focused structural evidence proving the gate is absent from the app test source owner, present in build and ordered after app/coverage/tooling, with existing selectors preserved; root separately validates actual Maven selections. Avoid tests that merely mirror each implementation line.

- [x] Read the diagnostic report, exact current adapters/fixtures, build/app POM dependencies and documented app-only/Keycloak selectors.
- [x] Establish RED evidence for confirmed escapes against the exact published adapters in fresh standalone output; preserve all 83 original fixture invocations and their assertions.
- [x] Apply the bounded input-containment fix, downstream source/report move and test-scoped build dependencies, plus grounded regressions and accurate docs.
- [x] Compile current gate fixtures with JDK21 from current source and run the complete standalone fixture suite; retain exact counts, commands, warnings and limitations. Verify pure gate model content remains unchanged after source relocation.
- [x] Write `task-1-report.md` in this plan's ignored SDD workspace and freeze. No Maven or Git mutation by implementer.
- [ ] Root obtains independent scoped spec/quality review, explicitly removes any evidenced stale former app gate binaries, runs supported selection checks plus a fresh full reactor and architecture profile, checks actual repository gate/report and source inventory, publishes the exact tree, resolves only addressed/disproved review threads with evidence, and requests fresh exact-head review. Default local checks do not replace canonical CI.

## Controller verification

At source commit `e4bf1ad45f4ca47f0d406cfa1ca3b8eabe821a74`, a clean detached
checkout passed `clean verify -DexcludedGroups=real-llm`: 3,074 app tests and
185 build tests, zero failures or errors, with one existing Helm test skipped.
The complete architecture profile passed 110 tests: 18 app tests and 92 build
tests, including all 91 graph fixtures and the real repository extraction gate.
The report retained seven declared production POM edges and no enforced
violation; the complete proposed feature graph still contains reported blockers.

Actual Maven `validate -pl taxonomy-build -am` selected all nine reactor modules,
including tooling. `validate -pl taxonomy-app -am` selected its six prerequisites
and excluded build and tooling. This confirms reactor selection, not a Docker or
Keycloak execution. A class-file SourceFile audit found all 1,047 app production
and 660 app test classes belonged to current sources, with no missing output or
obsolete app-owned gate class. The independent task review found no critical or
important issue. A subsequent test-only hygiene change places external fixture
directories under a second JUnit-managed temporary root; its focused native
verification and review are recorded separately from the full build. At
`d8e98b6d68f799e0dac29ce6dd8059fdfba82b9d`, the full-reactor focused command
`test -Dtest=ArchitectureModuleGraphTest -Dsurefire.failIfNoSpecifiedTests=false`
passed all 91 fixtures with no failures, errors, or skips in 37 seconds. This is
a supplement for the test-only change, not a second full verification run.

Local verification used Java 21 and a detached checkout outside the synchronized
workspace after that workspace recreated a deleted, obsolete app test source.
The committed source move and strict owner assertion were preserved. Canonical
CI, Docker-backed integration checks, and current-head review remain required
before merge.

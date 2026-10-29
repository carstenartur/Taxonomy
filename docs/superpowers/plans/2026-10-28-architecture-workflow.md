# Architecture workflow implementation plan

> For agentic workers: use superpowers:executing-plans for this focused correction.

**Goal:** Preserve source identity through architecture navigation and reduce
editor list overload.

**Architecture:** A small shared browser adapter owns explicit workspace pins
and source links. Existing controllers still own domain operations. Two
architecture templates share a source-context fragment.

**Tech stack:** Existing Thymeleaf, JavaScript, CSS, Maven-owned Node contracts.

**Spec:** ../specs/2026-10-28-architecture-workflow-design.md

## Global constraints

No provider calls, auto-adoption or backend mutation through navigation. Preserve
explicit shared workspace pins, application prefixes and EN/DE labels. Do not
replace the primary analysis session router. Keep #1334 independent.

## Review focus

- An explicit blank shared pin must survive a later active-workspace switch.
- An external link or explicit different destination must not inherit scope.
- A historical snapshot must not become an editor checkpoint or editable model.
- Missing provenance must not silently open another repository.
- Retained source links must remain correct after creating a different workspace.

## Tasks

1. Write failing `architecture-workflow.test.mjs` cases for link targets,
   shared/private scope, source return, malformed identity and scoped transport.
   Register the file in `.mvn/javascript-junit-sources.txt`. Run through
   `./mvnw -B -pl taxonomy-tooling -am test -Dtest=JavascriptContractTest
   -Dsurefire.failIfNoSpecifiedTests=false
   -Dtaxonomy.javascript.sources=.github/scripts/architecture-workflow.test.mjs`.
2. Implement `shared/architecture-workflow.js`: `scopeUrl`, `snapshotLinks`,
   `editorSourceLinks`, `showSnapshot`, `showEditor`. Add the shared context
   fragment/CSS and call it from workbench/editor render functions. Load the
   adapter before standalone portfolio API clients. Re-run the focused cases.
3. Bound editor element/history lists and provide keyboard-accessible region
   labels. Document source-versus-working-state semantics in both user guides.
4. Run all Maven-owned JavaScript contracts and the canonical reactor
   verification. Record failures and environmental limits exactly. Obtain an
   independent whole-diff review before handing over a draft change.
5. Address the runtime-discovered default-configuration 404 on editor working-copy
   creation. Add a dedicated editor command using existing provisioning and
   membership services; test source mismatch, private-source rejection, contributor
   permission and successful creation. Re-run the domain HTTP probe with the
   multi-repository catalogue feature disabled.

## Execution record

- Baseline application package/start: exact CI artifact verified and started.
- Baseline local rebuild: dependency resolution blocked (proxy trust/DNS).
- Manual browser: blocked by local socket and cloud localhost restrictions.
- Domain HTTP probe: plain-text analysis, explicit existing/planned portfolio
  links, editor preview/accept/checkpoint/undo succeeded with mock analysis.
  Reformulation synthesis explicitly rejects mock providers; no supplier-question
  quality result is claimed. The first probe exposed the disabled working-copy
  endpoint before the scenario was repeated with that feature explicitly enabled.

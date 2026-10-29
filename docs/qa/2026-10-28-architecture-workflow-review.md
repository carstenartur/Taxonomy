# Architecture workflow review — 28 October 2026

## Scope and evidence

Reviewed source baseline: `ed60382a4b56629f2407848fd8d3330deeb7f198`.
The task was to perform a system architect's journey from a plain-language
requirement through clarification, architecture, portfolio and versioned editing.

The exact application distribution from [CI run 40737095664](https://github.com/carstenartur/Taxonomy/actions/runs/40737095664)
was downloaded (artifact 12837992085). Its archive SHA-256 was
`77440b8723f8309459670b6bd7016ed057792f313fa8b31cc47259d936e1f232`;
all distribution manifest checksums also matched. It started successfully using
an isolated HSQLDB database, mock provider, and disabled embeddings.

A personal browser walkthrough was **not completed**. The local browser could
not create its Unix socket; escalation was rejected by the environment's
approval policy. The provided cloud browser rejected the local URL. No browser
success, real-model quality result, or complete product acceptance is claimed.
The documented public demo at `https://taxonomy-analyzer.onrender.com/` was also
opened in the cloud browser on this date; its visible page was **Service
Suspended**, with “This service has been suspended by its owner.” This is a
separate deployment blocker, not a successful application walkthrough.

Existing source-bound desktop architect screenshots and reports were inspected
from artifact 12838442322 of the same CI run. These are existing CI evidence,
not screenshots captured during this review.

## Findings

| Finding | Evidence | Corrective change / remaining work |
|---|---|---|
| The workbench's bare editor link changes artifact without explaining it | `architecture-workbench.html`; `ArchitectureWorkbenchService` projects immutable analysis evidence; editor operates on DSL revisions | Named working-state action, explicit explanation, recorded repository/workspace/branch and selected element |
| A saved architecture lacks a direct return to its requirement and clarification | Workbench header and projection contain the identity but do not connect it to navigation | Source context with exact analysis links and editor return navigation |
| Portfolio ignores an explicit project selection | `loadProjects` uses local storage or the first project | Explicit project target wins; inaccessible targets do not silently show another project |
| Standalone project calls lose explicit tab scope | Portfolio/workbench API paths omit the page's workspace pin | Scoped project transport/navigation, including explicit shared pins and export URLs |
| Editor shared context is not pinned in requests | `ArchitectureEditorApi.query` omits shared workspace query | Empty `workspaceId` is sent explicitly; live editor URLs track context changes |
| Long lists dominate the editor | Exact baseline CI editor capture shows 50 items and 50 operations as one long document | Bounded, labelled, keyboard-focusable element/history lists |
| Creating an editor working copy fails in the default configuration | Authenticated POST to `/api/repositories/{id}/workspaces` returns 404 with the default disabled catalogue API | Dedicated editor command; exact central source and CONTRIBUTOR checks; existing provisioning service |
| Import completion loses its workspace and project | The successful import assigns an unscoped URL directly to `location.href` | Shared destination helper retains project, language and explicit private/shared scope |
| Reopening an analysis from a requirement escapes mounted deployments | The page builds `/architecture/workbench` directly against the origin | Shared destination helper retains the application prefix, snapshot and scope |

## Domain scenario executed over HTTP

The test requirement was: “Die Organisation soll Lagebilder sicher mit Partnern
austauschen können. Das System muss auch bei Netzunterbrechungen funktionieren
und vorhandene Identitätsdienste nutzen.” All data was created in an isolated
local database. No production portfolio or remote model was used.

- Creating the requirement and requesting its analysis produced a SUCCESS job
  and an immutable SUCCESS snapshot using the mock analysis provider.
- An ACTIVE identity service with REUSE and a PLANNED exchange service with
  CREATE were explicitly linked to that exact requirement snapshot. Both links
  remained PROPOSED and were visible in the portfolio response. This establishes
  storage/identity behavior, not automatically discovered architecture coverage.
- Editor preview left the semantic revision unchanged. Acceptance advanced it
  from 0 to 1, checkpoint creation retained a read-only Git version, and undo
  advanced the working revision to 2 while the checkpoint remained readable.
  This was a separately created editor element, not an adopted analysis.
- Reformulation proposal creation succeeded, but its synthesis run ended with
  `PROVIDER_NOT_CONFIGURED`: the production service deliberately rejects mock
  execution. No supplier questions were generated or evaluated.

The first working-copy attempt exposed the default 404 described above. The
scenario was then repeated using the compiled dedicated editor controller with
the catalogue API explicitly **disabled**. Working-copy creation, analysis,
portfolio links and editor checkpoint/undo passed again. Reformulation remained
blocked as expected. This used the baseline distribution with changed classes
and resources on its classpath, not a newly packaged release.

## Acceptance matrix

| User task | This review establishes | Still required |
|---|---|---|
| Enter requirement and start analysis | HTTP scenario produces a saved SUCCESS analysis; PR #1334 covers additional next-step guidance | Fresh personal browser run with a domain scenario |
| Improve requirement and prepare supplier questions | Exact source links work; proposal creation succeeds; synthesis rejects mock execution explicitly | Configured-provider run and domain review of actual questions, text and adoption consequences |
| Understand existing/planned portfolio | HTTP scenario retains ACTIVE/REUSE and PLANNED/CREATE links to the exact snapshot; navigation is covered by regression tests | Browser evaluation of actual products and architecture coverage in one task |
| Refine architecture using mouse and AI | Default working-copy command works; HTTP preview/accept/undo succeeds; source/current-state boundary is explicit | Browser mouse/AI interaction; controlled snapshot-to-editor adoption workflow |
| Version and compare work | HTTP checkpoint remains readable after undo advances the working revision | End-to-end compare/branch/restore with original requirement and portfolio evidence |

## Verification

- Maven-owned JavaScript contracts: 1,579 executed, zero failures/errors/skips,
  including 37 new source/scope/navigation cases. Failures were reproduced before
  the corresponding fixes (20 initial cases, then project/shared pin, download,
  current-workspace navigation, default working-copy route and both review-found
  handoffs). The import and requirement tests execute the production page
  functions through the closure seam already used by this repository's tests.
- Four working-copy controller tests cover successful exact-source creation,
  repository/branch substitution, private-source substitution and missing
  contribution permission. The controller retains the primary repository's
  existing authenticated-user compatibility exception through the shared
  membership service.
- The 12-test `ApiWriteDenyByDefaultSecurityTest` passes through the application's
  real security filters. Its two added cases verify that USER retains access to
  working-copy source validation and an invalid CSRF token is rejected. The
  USER compatibility test first failed with 403 before adding the narrow route
  rule; other editor mutations retain their ARCHITECT/ADMIN gate.
- Runtime HTTP template smoke: exact baseline application with changed resources
  on the classpath; authenticated German editor, workbench, projects and
  requirement-detail pages returned HTTP 200. New fragment, script and translated
  labels rendered. This checks server integration, **not** browser behavior.
- The required `./mvnw -B verify -Pci -DrunOnnxTests=true` has **not passed**
  locally. After correcting proxy propagation for Maven fixture subprocesses,
  it reached the workspace module but Mockito could not self-attach its agent
  under the local process restrictions. The focused backend run uses Mockito's
  supported startup agent instead. A later selected `test` run also encountered
  incomplete Maven build-cache artifacts; subsequent selected lifecycle runs
  disable that cache. None of these focused runs replaces full reactor, browser,
  external-database or release acceptance.
- No live paid/shared provider endpoint was called; no merge or release was made.

An independent code review identified the import redirect and mounted workbench
link gaps. Both were reproduced by failing tests and corrected. It did not
establish real DOM observer behavior or visual/keyboard usability; those remain
part of browser acceptance.

Focused Maven entries (with local proxy settings and Mockito's cached jar loaded
as a startup Java agent for the backend tests):

```sh
./mvnw -B -pl taxonomy-tooling -am test -Dtest=JavascriptContractTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -pl taxonomy-app -am test -Dmaven.build.cache.enabled=false -Dtest=ApiWriteDenyByDefaultSecurityTest -Dsurefire.failIfNoSpecifiedTests=false
```

The working-copy controller's four passing cases were selected in the preceding
Maven application reactor runs. Their passing result is separate from those
runs' initially failing security assertion. Final security run Maven exit: 0;
Surefire: 12 tests, zero failures/errors. No assertions, security checks or
required profiles were removed to obtain a green result.

## Remaining product work

The first correction does not make the separate artifacts one editable model.
A complete workflow needs an explicit, previewed adoption from immutable analysis
to a writable architecture revision, retained source mappings, consistent task
navigation, and a real user-task acceptance run. See the
[design](../superpowers/specs/2026-10-28-architecture-workflow-design.md).
Coordinate with [PR #1334](https://github.com/carstenartur/Taxonomy/pull/1334);
its guidance/selection work is intentionally not duplicated here.

# Copilot diagnostics and workspace polling — QA

Original investigation base: `90a87b37233b9d56919e36a4529de6169167d730`.
Final integration base: `3dd5d8d98eefe5be2248c9b2ffd66cbe18930b00`.

## Reproduced failures

- The three workspace suites produced 8 expected assertion failures among 36
  tests before the fix: missing lifecycle codes and early HTTP 409 responses
  blocking a legitimate synchronization metadata observation.
- The three global-handler suites produced 6 expected failures among 44 tests:
  missing safe diagnostic fields, missing typed response metadata and warning-level
  classification of expected readiness conflicts.
- The initial polling regression run produced 17 failures among 59 cases, including
  constant retry intervals, duplicate startup reads and loss of the conflict reason.
- Prompt regressions failed on the missing readable structured paragraphs and the
  missing unchanged original disclosure before the shared renderer was introduced.

## Verified locally

The following focused Java selection passed **80 tests**, with no failures,
errors or skipped tests:

```sh
./mvnw -B -Dmaven.build.cache.enabled=false -pl taxonomy-app -am test \
  -Dtest=GlobalExceptionHandlerTest,GlobalExceptionHandlerDiagnosticPrivacyTest,GlobalExceptionHandlerDisconnectTest,WorkspaceContextResolverRequestPinningTest,WorkspaceDefaultBootstrapReadinessTest,GitNativeSyncIntegrationServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The run used Java 25, the environment's trusted network proxy/CA and an explicit
Mockito startup agent because the host forbids dynamic JVM self-attachment.
These were local runner settings; project dependencies, build gates and JaCoCo
configuration were not weakened. The build cache was disabled for a real rerun
and to avoid restoring test-only reactor outputs as unavailable dependency JARs.

The combined targeted JavaScript run passed **775 tests**, with no failures or
skips, covering the diagnostic log, live progress, startup, readiness, queue,
cluster recovery, recovery viewport and partial results, product score streaming,
polling lifecycle, canonical API transport and workspace account workflows.

```sh
node --test .github/scripts/analysis-llm-log.test.mjs \
  .github/scripts/analysis-live-progress.test.mjs \
  .github/scripts/analysis-session-startup.test.mjs \
  .github/scripts/analysis-readiness.test.mjs \
  .github/scripts/analysis-queue.test.mjs \
  .github/scripts/analysis-cluster-recovery.test.mjs \
  .github/scripts/analysis-recovery.test.mjs \
  .github/scripts/analysis-recovery-viewport.test.mjs \
  .github/scripts/analysis-partial-results.test.mjs \
  .github/scripts/product-score-streaming.test.mjs \
  .github/scripts/navigation-poll-lifecycle.test.mjs \
  .github/scripts/test-taxonomy-api-client.mjs \
  .github/scripts/ui-primary-account-workflows.test.mjs
```

The production renderer is exercised with complete protocol JSON, real paragraphs,
literal escapes, CRLF originals, hostile markup, malformed/truncated JSON and
retained original lengths. Tests cover all three rendering entry points and
verify the exact value sent to the shared clipboard boundary.

`ArchitectureContextDependencyRatchetTest` also passed all 36 checks. The new
workspace exception and safe handler logging introduce no unrecorded cross-context
dependency.

## Native browser boundary

Local Chromium startup failed before rendering because the execution host denied
the browser's Unix socket operation. The provided browser also rejected local
`file:` navigation under its URL security policy. No screenshot or native visual
pass is claimed from this environment, and the browser restriction was not bypassed.
The normal hosted browser gates remain required; focused VM tests are not a full
application/browser or database-matrix result.

`copilot-prompt.browser.test.mjs` adds three Maven-owned native Chromium cases.
They load the actual progress UI, prompt renderer, clipboard helper and styles
with authored API results. The 1280-pixel and 390-pixel cases check readable text,
raw disclosure/copy, inert markup and wrapping, and capture screenshots. A third
case checks plain and truncated evidence. Syntax and JUnit discovery succeeded;
native execution is pending a host that permits the browser. Existing native Git
and synchronization tests retain their assertions with one-shot timer fixtures.

In a full ready-for-review PR run, these cases belong to the `CI / CD` job
`UI shard / forced-colors-special-and-primary-user`. Its
`special-modes/text-spacing-and-offline` scenario invokes the JUnit owner with
`-Dtaxonomy.javascript.mode=browser`. The artifact
`ui-verification-forced-colors-special-and-primary-user` contains the
`junit-browser-reports` directory and, when captured, the two
`copilot-prompt-browser/structured-prompt-{1280,390}.png` screenshots. The
separate `UI contract verification` job runs the non-browser inventory. The
required `Maven verification` aggregation includes the browser shard result.

For an allowed local desktop browser, a reproducible manual fixture uses the
production progress renderer, prompt helper and stylesheet with authored API
results:

```sh
node .github/scripts/copilot-prompt-fixture.mjs
```

It writes `target/copilot-prompt-browser/index.html` and a 390-pixel iframe preview
in `narrow.html`. Check visible paragraphs, the unchanged disclosure, exact copied
text, narrow wrapping and the malformed/truncated fallback. The fixture is not a
live provider request or a production-session acceptance result.

## Independent review and integrated races

The independent review identified interactions beyond the initial component
fixtures. Regression tests now execute the Git event subscriber and actual
session/draft modules together. They cover first-context source switches after
403, preservation of 409 backoff through passive startup confirmation, and
retaining a Git conflict explanation without issuing a draft-status probe.

Deferred-response regressions cover a newer conflict arriving before an older
draft-status response, full draft restore or committed write receipt. A queued
reset behind a save cannot issue its POST after a newer conflict; the healthy
queued reset remains supported. Authentication failures supersede Git rendering
that was waiting for translations. The final independent draft/startup selection
passed all 137 cases, and the reviewer reported no remaining code blockers.

The final pre-integration Maven-owned UI contract run passed **3,604 tests**, with
no failures, errors or skipped tests. Unlike the focused direct Node selections,
this executes the complete registered non-browser contract inventory through its
normal JUnit owner:

```sh
./mvnw -B -Dmaven.build.cache.enabled=false -pl taxonomy-tooling -am test \
  -Dtest=JavascriptContractTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Final integrated verification

The reviewed changes were rebased without conflicts onto the final integration
base above. The combined Maven run then passed **3,723 tests**: 3,607 registered
non-browser JavaScript contracts, 36 workspace tests, 44 global-handler tests and
36 architecture checks. There were no failures, errors or skipped tests, and the
reactor reported `BUILD SUCCESS`. The additional JavaScript cases compared with
the pre-integration run came from the updated main branch.

```sh
./mvnw -B -Dmaven.build.cache.enabled=false -pl taxonomy-app -am test \
  -Dtest=JavascriptContractTest,GlobalExceptionHandlerTest,GlobalExceptionHandlerDiagnosticPrivacyTest,GlobalExceptionHandlerDisconnectTest,WorkspaceContextResolverRequestPinningTest,WorkspaceDefaultBootstrapReadinessTest,GitNativeSyncIntegrationServiceTest,ArchitectureContextDependencyRatchetTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

This verifies the final integrated application source. Native browser execution
and the complete hosted CI matrix remain separate required checks; they are not
included in the 3,723-test result.

## Deployment boundary

The historical production 409 source is not proven by a status-only log sample.
The changes preserve real conflicts and expose safe identifiers for the next
diagnostic observation. Applying these source changes does not itself update a
running deployment; deployment and complete hosted checks must be reported
separately from the focused test results above.

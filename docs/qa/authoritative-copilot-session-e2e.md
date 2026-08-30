# Complete AI-assisted session acceptance

Issue #922 defines a release-floor test for one coherent user session. The purpose is not to prove isolated methods or mock-score arithmetic. It proves that a user can start with an entered requirement, understand what Taxonomy is doing, recover from interruption, inspect the result and receive valid exports.

## Authoritative test

The Maven/Failsafe-owned test is:

```text
taxonomy-app/src/test/java/com/taxonomy/CompleteCopilotSessionIT.java
```

It runs the packaged application with:

- PostgreSQL in Testcontainers;
- Selenium in a browser container;
- the real project and requirement pages;
- `LLM_MOCK=true` for deterministic scoring;
- no injected result HTML and no fabricated successful or failed terminal state.

A focused run is:

```bash
./mvnw -B -ntp verify -Pci \
  -Dfailsafe.includes='**/CompleteCopilotSessionIT.java'
```

## User journey covered

1. Authenticate through the real login form.
2. Create a project through the project dialog.
3. Enter a requirement through the requirement dialog.
4. Open the saved requirement and select the exhaustive Copilot profile.
5. Start the full mocked evaluation and immediately observe an operation identity and visible status.
6. Cancel the active operation and require an authoritative `CANCELLED` terminal state.
7. Force a new run and require a different operation identity.
8. Inject one status-transport failure at the public browser API boundary.
9. Require `RECONNECTING`; the test fails if the UI declares the server analysis failed.
10. Reload the page and recover the same persisted operation.
11. Wait for `SUCCESS` or an explicitly retained `PARTIAL` result and open the selected snapshot.
12. Exercise all requirement result tabs and the new-version dialog.
13. Trigger DOCX, HTML and JSON report exports through their real controls.
14. Require a visible export operation and a non-empty response with a valid content type and filename.
15. Open the DOCX as OOXML and compare common requirement evidence across DOCX, HTML and JSON.
16. Check responsive overflow, keyboard reachability, accessible names and control obstruction.
17. Create a new immutable requirement version above the configured prompt budget through the real dialog.
18. Start a new persisted Copilot operation and require its authoritative `FAILED` state.
19. Require the server-owned `PROMPT_BUDGET_EXCEEDED` explanation, the affected AI target, exactly one visible alert and an enabled new-run control.

## Software-ergonomic assertions

### Suitability for the task

The session is tested as one user goal, not as unrelated pages. A passing result requires usable output, not only successful HTTP status codes.

### Self-descriptiveness

Every long operation exposes its type, identity, phase and current state. The UI distinguishes a server failure from a reconnecting browser.

### Controllability

Cancel remains available while the operation is cancellable. Restart creates a new operation rather than silently reviving a terminal attempt.

For a multi-pass operation cancellation is operation-wide, not tied only to the job that happens to be active at one instant. If a request arrives between verification passes, Taxonomy persists a cancelled marker for the next missing pass. A recorded cancellation dominates any late `PENDING` or `RUNNING` pass and prevents the coordinator from starting further work. A genuinely completed all-success operation is not rewritten retroactively as cancelled.

### Conformity with expectations

A progress percentage is used only for completed measurable units. An active opaque LLM call is shown as indeterminate.

### Error tolerance

One lost poll leaves the operation recoverable. Reload resumes observation. Partial evidence is not discarded merely because a later step failed.

### Accessibility and discoverability

Session controls require:

```text
data-session-control
data-session-test-outcome
```

Visible controls must have an accessible name. The acceptance checks that actionable session controls are not covered by an overlay or another element.

## Export equivalence

For one immutable snapshot the test requires:

- DOCX is a readable ZIP and contains `[Content_Types].xml` and `word/document.xml`;
- HTML has an HTML media type and document structure;
- JSON has a JSON media type and object structure;
- all formats are non-empty;
- all formats contain the same distinctive requirement evidence.

This is the first semantic equivalence floor. Later work should normalize additional export formats into a common architecture model containing nodes, scores, relations, product decisions and provenance.

## Failure evidence

A failed run must retain enough evidence to distinguish:

- application startup or authentication failure;
- operation not created;
- cancellation requested between passes without a durable terminal marker;
- a late pass starting or remaining active after operation-wide cancellation;
- reconnect state not rendered;
- persisted operation not recovered after reload;
- missing snapshot;
- no-op export;
- wrong content type or filename;
- invalid OOXML;
- cross-format semantic divergence;
- inaccessible or obscured controls;
- oversized input not rejected by the server before provider dispatch;
- loss of the concrete prompt-budget/AI-target explanation;
- multiple competing top-level errors.

Screenshots may supplement this evidence, but screenshot generation is not the authority and must not inject fallback result markup.

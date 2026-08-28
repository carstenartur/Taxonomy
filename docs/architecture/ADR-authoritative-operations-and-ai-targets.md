# ADR: Authoritative operations and addressable AI targets

- **Status:** Proposed; first browser and acceptance slice in PR #923
- **Issue:** #922
- **Related:** #621, #805, #808

## Context

Taxonomy currently exposes several independently implemented long-running workflows: ad-hoc analysis, saved-requirement analysis jobs, Copilot verification passes, secondary gap/pattern/recommendation calls, and exports. Their server and browser states are not yet represented by one common authority.

That permits contradictory user-visible states. A browser timer or one failed status poll can report failure while server work continues. A partially completed analysis can produce several unrelated error surfaces. Some progress bars remain at zero during an opaque LLM call, and some export controls provide no observable evidence that a request or download occurred.

A provider name alone is also insufficient to identify the AI endpoint that accepted a request. Reproducibility and diagnostics require a stable target description including provider, model, mode, limits and configuration identity.

## Decision

### 1. One operation lifecycle

Every user-visible operation expected to exceed two seconds is represented by an operation identity and an attempt identity. The shared presentation vocabulary is:

```text
PENDING
RUNNING
RECONNECTING        browser transport state; not a server terminal state
CANCELLING          cancellation requested, terminal response pending
CANCELLED
SUCCESS
PARTIAL
FAILED
STALE
```

The durable server record remains authoritative for server states. `RECONNECTING` is explicitly local transport information and must never overwrite or imply a durable failure.

A terminal attempt cannot return to a non-terminal state. Retry, resume and restart create a new attempt with an explicit relation to the prior attempt.

### 2. Truthful progress

A percentage is shown only when the denominator is real and stable. During an opaque LLM request, the UI shows:

- current phase;
- indeterminate progress;
- completed measurable units, such as verification passes;
- operation identity;
- AI target;
- last confirmed server contact;
- cancellation availability.

A completed-pass fraction may be shown as evidence, but it must not imply progress inside the active LLM request.

### 3. Addressable AI target

The long-term API introduces an `AiTargetDescriptor` containing at least:

```text
logicalTargetId
provider
model
mode                  REMOTE | LOCAL | MOCK | REPLAY
capabilities          streaming, cancellation, structured output
contextWindow
maxPromptTokens
maxResponseTokens
maxRequestBytes
healthState
configurationFingerprint
```

Every operation records both the requested and effective target. Fallbacks are visible and auditable; they are never silent.

### 4. Prompt-budget decision before dispatch

Before a provider call, Taxonomy calculates the request budget. It may deterministically reduce nonessential context or split work only where the result remains semantically well-defined. Otherwise it returns one bounded problem such as `PROMPT_BUDGET_EXCEEDED`, preserving already completed evidence as a partial result.

Provider retries and internal child failures are diagnostic entries of the same operation. They do not become competing top-level alerts.

### 5. Export is an operation

An export action is successful only after Taxonomy has received a non-empty body with the expected content type and filename. Long-running exports use the same operation presentation. The successful operation records artifact identity, media type, size and checksum where available.

Formats generated from one snapshot must preserve the same core requirement, snapshot/provenance and decision facts.

## First implementation slice

PR #923 introduces the following bounded foundation without claiming that the complete server migration is finished:

- typed lifecycle events around the real ad-hoc `/api/analyze` request;
- removal of the Analyze-tab Copilot's dependency on the historical 60-second score-map timer;
- reconnecting semantics for persisted Copilot status polling;
- indeterminate progress during active verification passes;
- explicit export running/success/failure evidence for saved-requirement decision reports;
- a PostgreSQL/Testcontainers/Selenium complete-session release-floor test.

The existing persisted requirement Copilot jobs remain the server authority for that workflow. Ad-hoc analysis still needs the durable shared operation work tracked by #805 and #808.

## Consequences

### Positive

- A lost status request no longer falsely declares the analysis failed.
- Cancellation, restart and reload recovery become testable user contracts.
- Progress is honest rather than cosmetically precise.
- Export buttons cannot pass while silently doing nothing.
- Provider/prompt problems can converge on one actionable error.

### Costs and follow-up

- Analysis, Copilot and export code must migrate incrementally to the shared server model.
- A server-owned mock fault vocabulary is needed for slow calls, transient transport loss and prompt-budget rejection.
- Existing text- and timer-based regression tests must be replaced by operation-state assertions.
- Export normalizers are required for broader semantic comparisons across Visio, ArchiMate, Mermaid, Structurizr, JSON and Office formats.

## Rejected alternatives

### Increase the Copilot timeout

Rejected because it preserves the false assumption that absence of a browser-side score map proves server failure.

### Add more independent spinners and error boxes

Rejected because it increases contradictory state and does not create an authority.

### Treat every polling error as terminal

Rejected because a transport failure does not reveal the server operation's terminal state.

### Fabricate percentages from elapsed time

Rejected because elapsed time is not a reliable measure of LLM completion.

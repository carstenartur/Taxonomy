# Root relevance without false exclusions

The user asked on 2026-10-27 to select and implement the best remedy for CO
receiving zero relevance, including an explanation of alternatives. The selected
approach is an implementation decision under that authorization.

## Intent

A root score of zero means assessed irrelevance. Missing deployment details or a
needed service already being available must not silently become zero. The
original catalogue, bounded provider usage, independent root scores and existing
partial-result workflow remain the authority.

## Approaches

1. Prompt wording alone is small but cannot represent an open decision.
2. A clearer prompt plus a typed open root assessment reuses existing coverage,
   continuation and partial-result handling. This is the selected approach.
3. A mandatory second model call before every exclusion adds latency, cost and
   another possible failure while retaining the ambiguous result type.

No minimum CO score, unconditional network assumption or extra automatic model
call is introduced. The rule applies to all eight roots.

## Contract

New root replies use `status`, `score`, `reason`, and `question` per exact offered
root. `RELEVANT` requires a finite score in (0,100]; `IRRELEVANT` requires zero;
`UNRESOLVED` requires a null score and a nonblank clarification question. A reason
is mandatory. Contradictory or malformed combinations fail validation. Existing
numeric and score/reason replies remain supported without inventing missing prose.

`RootAssessment` and an additive `rootAssessments` map on `LlmCallDetail` preserve
the typed evidence. Unresolved roots are absent from numeric score maps. The
explicit `ROOT_UNRESOLVED:` diagnostic marks a semantic clarification for the
existing error/warning transport; it must be presented as clarification, not as a
provider failure. Successful transport still consumes exactly its normal call.

The existing journal stores valid unresolved root responses as `CLARIFICATION`,
replays their evidence without another call and supports explicit retry/skip.
Coverage is `UNKNOWN` with null scores and a `CLARIFICATION:` reason, with
descendants explicitly blocked. Other roots and retained architecture evidence
continue. Local, streaming and distributed paths must preserve this distinction.
Changed prompt policy invalidates incompatible answer reuse.

Prompts distinguish relevance to the requested function from a requirement to
build a new service. Necessary reused dependencies are relevant. A vague
time-recording requirement may leave connectivity unresolved; explicit online
booking supports connectivity; an explicitly isolated offline implementation
supports a genuine zero. Neither protocol nor exact implementation is invented.

The product demo remains the same application with authored provider replies.
Its vague R0 CO response becomes unresolved; online R1 has supported CO relevance
and real catalogue descendants. The fixture is not advertised as live-model
quality evidence.

## Compatibility found in the complete workflow

Partial demo snapshots retain their explicit coverage in the reference comparison.
Missing relations from an unfinished search are unassessed. A versioned demo reply
cannot replace the provider binding of an older queued run; retry admission checks
the exact target versions before mutating the job.

The complete R1 reformulation exposed three identical copies of the selected
original text in one reconciliation prompt. When it reduces both enforced input
measures, the prompt may reference `baseline.originalText` for an exactly matching
ORIGINAL/ADOPTED_SOURCE wording and a matching full source span. The original stays
inline once. Persisted/domain data, historical decisions, span offsets and catalogue
context remain unchanged. The instruction defines the references explicitly and
the input encoding version changes; the provider limits remain unchanged.

## User-visible result

The CO root container shows a clarification and its reason/question without 0%.
The existing recovery details distinguish semantic clarifications from failed
provider requests. German and English strings and keyboard operation are kept.
Scored roots, numerical exports and the relationship search retain their existing
meaning; a zero root still does not exclude it as a later relationship target.

## Verification

Use real parser, orchestration, journal and catalogue paths with deterministic
provider replies. Cover online/reused, offline and unresolved cases; contradictory
payloads; decimal positives; legacy replies; replay/retry; partial-result retention;
root-container display; demo execution. Observe focused regressions fail before
implementation. Then run Maven-owned broader checks and available browser checks.
Do not label deterministic inference as a live provider quality measurement.

No new dependencies, workflow, database schema, queue or blanket retries.

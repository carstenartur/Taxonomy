# Independent architecture-quality corpus

The versioned [architecture reference](../../taxonomy-app/src/test/resources/scenarios/architecture-quality-v1.json)
adds six bounded cases to the existing scenario evaluators. Its expectations are
authored separately from the [observation fixtures](../../taxonomy-app/src/test/resources/scenarios/architecture-quality-observations-v1.json).
They are not extracted from a generated answer and are not supplied to a model by
the evaluator. The reference is an explicit test design, not expert-certified
ground truth or a claim that one architecture is the only possible implementation.

`ScenarioArchitectureQuality` composes the existing `ObligationEvaluator` and
`ScenarioRelationQuality`/`DirectedRelationMetrics`. The original Flood scenario,
its local textual-evidence checks and its export acceptance remain in place.
No new runtime rule engine, provider runner, Maven profile or CI selector is added.

## Cases and source binding

| Case | Independent expectation | Negative behavior detected |
|---|---|---|
| `clear-read-only` | The application consumes the published observation product. | Reversed direction, a write relation, or a missing required read. |
| `one-notification-channel` | Exactly one of the permitted text or email channels is selected. | Requiring both alternatives, selecting none or accepting both, or using only a proposed relation as accepted evidence. |
| `unknown-observation-source` | Source identity remains an explicit question. | Inventing an accepted product identity or calling absent source evidence a confirmed gap. |
| `contradictory-retention` | The conflicting clauses remain visible and retention is unresolved. | Dropping the question or arbitrarily accepting either policy. |
| `rename-existing-view` | Update the same existing element and retain its other declared property and read relation. | Replacement identity, a copied application, or loss of retained data. |
| `already-satisfied` | Keep the sufficient existing model without additions. | An unnecessary element or relation despite perfect required-relation recall. |

The channel-choice case expressly requires one channel; the original Flood case
expressly requires three available channel capabilities. Their different
obligations are intentional and source-bound. Relation legality alone creates
neither requirement.

Each case retains its original requirement, exact clauses, quote identifiers,
rationale, required/allowed elements, directed relation classifications, explicit
obligation profile, initial model where relevant and unresolved questions. A
loader rejects missing bindings, overlapping classifications, duplicate identities
and dangling reference endpoints. A JUnit test checks every referenced element
against the actual checked-in C3 workbook and its sheet/root membership. The
reference pins that workbook's SHA-256; loading fails if its bytes have changed
without an explicit reference update.
Changing expectations requires a reviewed reference revision; observations cannot
change the reference or declare its incomplete source evidence complete.

## Results and limits

The report separates these results instead of combining them into one score:

- `missingRequiredRelations`: exact required directed relations absent from a
  complete accepted observation. An incomplete observation uses
  `notObservedRequiredRelations`; it does not prove absence.
- `missingObligations` and `excessObligations`: explicit profile cardinality
  violations, with rule identity and observed count. Alternatives share one
  cardinality. `inconclusiveObligations` remains separate.
- `unjustifiedRelations` and `unjustifiedElements`: additions outside the bounded
  authored reference, even when all required relations are present.
- `unsupportedAcceptedRelations`: a relation whose identity the reference leaves
  unresolved but the observation incorrectly labels accepted.
- `propertyMismatches`: wrong roots or different/missing values for properties
  expressly constrained by the reference. Other properties are outside this
  assessment; this is not a complete textual or behavioral equivalence check.
- `openQuestions`, `unacknowledgedQuestions` and `unrecognizedQuestions`: unresolved
  source questions, omitted known questions and additional unclassified questions.

Only `accepted` relations discharge obligations and required accepted relations.
`proposed` relations are still inspected for unjustified assertions. Relation
direction and type remain significant; duplicate predictions are rejected.
Known defects remain visible even in a partial observation. Source uncertainty
without a demonstrated defect is `INCONCLUSIVE`; an absent case is `NOT_RUN`.

The existing directed precision/recall measures remain conditional on this
authored reference. They are `null` for incomplete/unexecuted evidence and for an
empty denominator. Allowed alternatives do not count as individually required
relations. No overall percentage combines these conditional measures.

The JSON receipt retains reference version and SHA-256, verified catalogue SHA-256,
exact observation-file SHA-256, profile identity, per-case results and outcome counts. Model runtime,
model cost and human correction effort are always `NOT_MEASURED` with a `null`
value in this deterministic evaluator. A claimed measurement in an imported
observation cannot promote them to measured values. Test execution time is not
model runtime. The output is written atomically; an invalid new evaluation removes
the old receipt so a stale success file cannot stand in for the failed attempt.

## Maven-owned execution

Use Java 25 and the Maven Wrapper. The ordinary test suite discovers these JUnit
tests. The focused deterministic selection is:

```sh
./mvnw -B -ntp -pl taxonomy-app -am test \
  '-Dtest=ScenarioArchitectureQuality*Test,ScenarioRelationQualityTest,DirectedRelationMetricsTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The corpus test writes
`taxonomy-app/target/architecture-quality/corpus.json`. The authored observations
currently produce four bounded `PASS` results and two `INCONCLUSIVE` results.
Those two open cases are expected evidence outcomes, not failed JUnit contracts
and not successful model-quality assessments. Negative mutations separately prove
that missing obligations, unsupported additions, false acceptance and lost
uncertainty are detected. The original scenario and full CI gates remain required.

Retained architecture output can also be assessed without invoking a provider.
Normalize it into the documented observation shape: `schemaVersion: 1`,
`evidenceKind: "RECORDED_OUTPUT_WITHOUT_RUN_VERIFICATION"` and an `observations`
array. Each observation supplies `caseId`, `state`, `evidenceCompleteness`,
`elements` (`id`, `root`, text `properties`), `relations` (`source`, `type`, `target`,
`status`) and question IDs. Preserve the output's actual identities, statuses and
unknowns; do not fill missing output from the reference. Keep the original output
and its normalization evidence for independent review.

```sh
./mvnw -B -ntp -pl taxonomy-app -am test \
  '-Dtest=ScenarioArchitectureQualityCorpusTest#writesTheMavenOwnedCorpusReceipt' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtaxonomy.quality.observations=/absolute/path/recorded-architecture-observations.json
```

This command verifies receipt production, not an overall quality pass. Inspect
the receipt's individual verdicts: invalid input fails the command, missing cases
are `NOT_RUN`, and known defects are `FAIL`. The explicit evidence kind means that
provider execution, normalization fidelity, cost and human review have not been
verified by this import path. Inputs are bounded to 256 KiB; unknown/repeated cases
and unsupported schema/evidence kinds fail closed.

## Separate live and human assessment

Live calls are opt-in and require a configured provider plus an agreed budget.
For an architecture-generation assessment, freeze the six case inputs, initial
models, catalogue revision, application commit and provider/model configuration
before running. Use the existing application analysis path, record the selected
context and resulting snapshot, and retain failed/cancelled work. Set the existing
[relation-search limits](../en/CONFIGURATION_REFERENCE.md) explicitly, including
the cumulative limit and automatic-continuation policy; a relation-call limit is
not a cap on every other analysis stage or a monetary limit. Do not rerun only
unsuccessful cases and report the best result as the original run.

A human review should bind to the exact retained outputs and independently assess
missing business obligations, unsupported additions, legitimate alternatives,
contradiction handling, preserved identity and the effort needed to correct the
model. Record actual correction minutes and concrete changes. Record monetary
cost only with measured provider usage and a dated price basis; otherwise keep it
unavailable. The deterministic corpus does not supply those measurements.

The project already has a separately executable
[real-provider comparison and bound human-review workflow](requirement-reformulation.md#separate-real-provider-and-human-quality-gate)
for requirement reformulation:

```sh
./mvnw -B -ntp -pl taxonomy-app -am test -Preformulation-real-llm
./mvnw -B -ntp -pl taxonomy-app -am test -Preformulation-real-llm \
  -Dtest=ReformulationHumanReviewTest \
  -Dreformulation.review.run=/absolute/path/to/a/recorded/run
```

That workflow verifies real transport and binds the review to preserved outputs.
It evaluates reformulation, not the new architecture corpus. Its execution or
human review must not be presented as architecture-generation certification.

# Evidence-specific architecture quality

## Intent and evidence

Carsten requested implementation changes after the supervised training-portal QA:
an automatically verified `CI-1151 SUPPORTS BP-1000` relation contributed little
architecture information, reconciliation exceeded the configured model budget,
and reports and the bounded ADMBw delivery concealed important differences in
coverage. The starting source revision is
`2774f630a1d507bdc034c1adea3356bf84bf41cb`. The retained QA is diagnostic evidence,
not proof of expert-level output or a representative weak-model benchmark.

The goal is useful, evidence-specific proposals with truthful completeness and
readable deliveries. A model's agreement with itself does not establish quality.
Smaller model tasks and deterministic checks must protect the result when a
provider returns coarse, malformed, contradictory or overconfident answers.

## Approach and choices

Prompt-only changes cannot enforce hierarchy identity, budget bounds or delivery
coverage. Removing all non-leaf nodes would lose legitimate abstract concepts.
The chosen implementation combines retained catalogue structure, narrowly scoped
model questions, deterministic admission/projection checks and explicit unknowns.
These are engineering choices within the authorized improvement request.

## Required behavior

1. Derive navigation-only catalogue entries from actual original parent/child
   structure. Real top-level leaves and meaningful deeper non-leaves remain valid.
   A coarse automatic navigation match requests refinement, never counts as a
   concrete verified architecture relation. Preserve source evidence and open
   work when refinement cannot finish. Explicit reviewed model edits are retained.
2. Use useful assessed source/target scope to order relation work without treating
   unknown branches as negative, increasing provider quotas or hiding pending work.
   Incompatible old recovery policies must not silently reuse changed semantics.
3. Reconciliation must respect the active provider's character, byte and token
   budgets. Keep exact source wording, identifiers, answers and distinct conflicts.
   Bound any grouped fallback and report comparison gaps. Never silently truncate
   the original or archival evidence to claim complete semantic reconciliation.
4. Requirement rewriting preserves modality, quantifiers, exclusions, ownership
   and the distinction between an existing approval state and a new workflow.
   Unsupported additions remain reviewable proposals, never established source facts.
5. Database time is one absolute instant independent of the JVM default zone.
6. PDF/DOCX preserve paragraphs and supported Unicode, contain readable bounded
   pages and complete inventories. Unknown score, depth and provenance are displayed
   as unavailable. Canonical review state and retained analysis status stay visible.
7. ADMBw assessment accounts for every source element, relation, requirement and
   requirement mapping, including objects outside a selected C1-S1 view. Each
   omission has an identity and reason. JSON, UI and native-QEA notes preserve the
   delivery boundary; structural success of C1-S1/R7 never means full-model or
   full-method conformance. Selecting a view scopes C1-S1 only; R7 retains its
   existing requirement scope.

## Constraints and verification

Preserve official catalogue identities, source order, original evidence, model
versions and existing reviewed edits. Do not add provider calls to ordinary tests.
Use Maven-owned regression tests, including coarse and contradictory authored
provider replies; label those fixtures accurately. Keep raw execution evidence
under `target/`. Use the existing disk-backed HSQL/Lucene profile for memory-bounded
application QA. Keep verification limitations explicit, including native EA review
and any absent real weak-model or expert-comparison benchmark.

Passing these gates improves the product's behavior. It does not support a claim
that every resulting architecture outperforms a manual architecture; that claim
requires independent, representative expert evaluation.

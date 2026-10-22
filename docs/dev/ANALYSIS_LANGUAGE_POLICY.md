# Analysis and document language policy

The personal language controls are implemented independently of the interface language.
They extend the existing analysis and report contracts; they do not change numeric
score semantics or translate historical evidence.

## Controls

| Control | Options | Default and meaning |
|---|---|---|
| AI response language / KI-Antwortsprache | Follow UI, German, English | Follow UI, resolved once before starting or queuing a new operation. Controls generated natural-language reasons, summaries and questions. |
| Document language / Dokumentsprache | Follow stored analysis, follow UI, German, English | Follow stored analysis. An explicit selection overrides generated headings and labels for that export. |

The controls appear in the analysis workspace, project/requirement pages and import
confirmation. The decision-report dialog has its own document-language selector;
the architecture workbench offers it as **Word report language** beside the export actions. Preferences are
stored in the current browser, namespaced by application path and signed-in account.
They are not installation-wide administrator settings and do not synchronize across
devices. Without browser storage, the current page still supports explicit choices.

## Admission and execution

New requests carry the typed optional `analysisLanguage` value (`de` or `en`). The
request boundary resolves an omitted value from the request locale (German for `de`,
English otherwise) before queuing work. The resolved value travels with commands,
streaming workers, cluster work, continuation records, portfolio jobs, results and
immutable saved analyses. Personal/UI changes affect new operations; existing
operation IDs retain their original language. Requirement writes also carry the
personal choice in `X-Taxonomy-Analysis-Language` for optional after-save Autopilot
admission. The header is limited to same-origin requirement writes.

Prompts append the `analysis-language-v1` contract for generated prose. Technical
instructions, identifiers, JSON keys, enum values, source requirements, quotations
and numeric protocol remain unchanged. The language and prompt policy participate
in new operation identity. Legacy operations without recorded language retain their
original prompt/fingerprint contract instead of inheriting a new preference during
resume. Reformulation proposals inherit a selected snapshot's recorded language
unless an explicit language is supplied; their baseline freezes that choice.

The local ONNX embedding provider remains a similarity provider. These controls do
not introduce generative capabilities or change the embedding model. See
[pinned model provisioning](../testing/multilingual-model-provisioning.md).

## Documents and historical provenance

Document language resolves in this order: explicit export selection, recorded
analysis language, request locale. Live and frozen decision and architecture reports
use their existing localized report labels. The report-options response supplies
recorded `analysisLanguage` separately from the resolved document `language`.

This localizes Word, HTML and Markdown report labels. SVG, PDF and Visio diagram
exports retain their existing diagram labels; their original analysis reasons use
the language recorded when those reasons were generated.

Saved reasons are copied in their original wording. Changing document language
localizes generated headings and labels; it does not invoke a model, recompute
scores, or rewrite source evidence. The export dialog explains this distinction.
An English historical analysis can therefore have German headings and English
original reasons. For fully German new results, select German AI output before
analysis, then export using the analysis language.

Missing historical language remains null/unknown. Mixed interactive evidence is not
assigned a single language when its contributing batches disagree. Drafts retain
recorded language separately from the language of an active interactive operation.
Review-only backups preserve recorded provenance and exclude transient active state.
PostgreSQL and restore-schema migrations add a nullable job field without backfilling
invented historical language.

Explicit historical translation remains a separate follow-up. Any such feature must
preserve the original snapshot, identify its source and translation provenance, and
avoid silently invoking remote providers in offline deployments.

## Readable evidence

Tree and Decision print preserve numeric scores and complete recorded explanations.
Compact cards link to visible numbered evidence; one-large-sheet mode arranges
logical sections across the sheet instead of reproducing a tall narrow strip.
Word tree reports include linked original reasons even with the compact profile.
Visio exports retain compact native graph shapes and add visible, wrapped evidence
pages linked by stable node/relationship keys. Existing export limits remain
explicit: an export must not silently drop required evidence to fit a budget.

## Verification

Existing Maven/JUnit suites cover language selection and account isolation, prompt
contracts, streaming/continuation/cluster propagation, persisted jobs and backups,
report defaults and legacy-null behavior. Existing browser fixtures exercise Tree
expansion/filtering, tall and wide print layouts, fractional percentages and unique
long-text endings. No external provider call is needed for these regression checks.

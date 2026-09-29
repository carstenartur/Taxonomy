# Analysis and document language policy (proposal)

Status: investigated during the 1.4.0 release audit; the settings below are **not implemented**.
This document records the proposed product contract separately from the scoring and
provider corrections. A UI-language switch alone cannot deliver consistent translated
analysis and historical documents with the current implementation.

## Current behavior

| Surface | Implemented behavior |
|---|---|
| UI | The `lang` parameter/cookie selects the request locale; the default is English. |
| Scoring and leaf reasons | English prompt templates contain no binding output-language instruction. GUI locale therefore does not guarantee the generated prose language. |
| Streaming | The request locale is copied into the worker, but the scoring prompts do not consume it. |
| Durable analysis jobs | The general analysis command/job/result does not freeze a separate content-language field. |
| Reformulation | A `de`/`en` baseline language is already frozen and explicitly used in prompts and deterministic text. |
| Decision/snapshot reports | A language parameter localizes labels/catalogue text. Existing AI reasons are copied unchanged. |
| Older architecture/portfolio exports | Many report labels and sections remain fixed English. |
| Preferences | The existing Preferences page changes system-wide administrator settings, not personal user preferences. |
| Local ONNX | The default `bge-small-en-v1.5` model uses English catalogue text. An output-language setting cannot make its retrieval multilingual or add text generation. |

Relevant implementation: `WebMvcConfig`, `PromptTemplateService`,
`StreamRequirementAnalysisUseCase`, `AnalyzeRequirementCommand`, the portfolio job
service, `ReformulationBaseline`, `ReformulationPromptBuilder`,
`DecisionRationaleReportService`, `PreferencesService` and `LocalEmbeddingService`.

## Recommended controls

| Control | Options | Default and meaning |
|---|---|---|
| AI response language / KI-Antwortsprache | Follow UI, German, English | Follow UI, resolved **once before starting/queuing a new run**. Controls generated natural-language reasons, summaries and questions. |
| Document language / Dokumentsprache | Follow stored analysis, follow UI, German, English | Follow stored analysis. An explicit export override is resolved once for that export. |

Keep technical prompt instructions and protocol keys in their tested language
(currently English); add an explicit language instruction for natural-language
response fields. A separate prompt-translation switch adds substantial template
and evaluation work with little direct user benefit. Do not translate original
requirements, quotations, node IDs, JSON keys or numeric evidence implicitly.

System-wide defaults may be administered through Preferences, but personal overrides
need their own user-scoped storage. A user's desired report language must not silently
change every other user's analysis language.

## Persistence, recovery and historical exports

Resolve typed language values at the request boundary and persist them on the queued
command/job, result and immutable snapshot. Include the resolved language and prompt
policy version in run/checkpoint identity so a resumed run cannot combine different
language contracts. A later GUI switch or preference change affects new operations.

Report labels and stored AI prose must be treated separately. If the export language
differs from the saved reason language, preserve the original evidence and require an
explicit translation operation. Store that derived translation with its source
snapshot, target language, translator/model identity and version; never overwrite
the original reasons or recompute scores merely to export. Offline deployments must
not silently call a remote provider for translation. Legacy mixed/unknown-language
results should be identified honestly rather than retroactively assigned a language.

## Acceptance criteria for a later implementation

- German UI plus fixed English output produces English reasons in normal, streaming
  and durable job paths; the inverse setting produces German reasons.
- All generated free-text fields, including relation evidence and reformulation
  questions, follow the same resolved run contract; IDs and source text remain intact.
- Preference/UI changes during a paused run do not alter its saved language or reuse
  checkpoints created for another language contract.
- Reports in the analysis language preserve saved reasons byte-for-byte. Explicit
  translations retain their source and never mutate the original snapshot or scores.
- Unknown legacy language and unsupported local-provider translation remain visible.
- User overrides cannot change another user's defaults; administrators can set the
  installation default without masquerading as a personal setting.

The existing release defects should be fixed first. These language controls are a
bounded follow-up with persistence and export implications, not a cosmetic GUI toggle.

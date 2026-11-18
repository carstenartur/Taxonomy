# Incremental taxonomy analysis

## Intent
For an unchanged requirement, users can select additional or all sub-taxonomies and start analysis again. Preserve accepted assessments and explanations; evaluate only new or unfinished work in the requested selection. Both Copilot and “Analyze with AI” use the same start decision.

The user approved this behavior with “Mach weiter” after the diagnosis and four-step proposal on 2026-11-18. Implementation details below are engineering decisions.

## Approaches and decision
- A fresh ordinary run already exists, but replaces the active result and repeats accepted calls.
- Browser-side score merging cannot establish durable provenance or survive recovery correctly.
- **Selected:** create an authorized durable extension, seed it from a saved source revision, and replay accepted node-question checkpoints through the existing evaluator. This retains one evaluator, the existing task DAG, and the normal recovery paths.

The result scope is the union of the retained source and requested roots. Requested work is a separate frozen selection: old unfinished BP work must not run when only CP was newly requested.

## Public and stored contract
- Public extension inputs: `taxonomySourceContinuationId`, `taxonomySourceVersion`, a new `continuationId`, `resumable=true`, and the desired `analysisScope`.
- The source and target IDs differ. Taxonomy and relations source references are mutually exclusive.
- The source must be owned in the same repository/workspace/branch and match the requested revision.
- Initially accept only COMPLETED or COMPLETED_WITH_GAPS sources with original durable node evidence. Running, unresolved uncertain, paused, or stopped operations use their existing recovery. An explicit SKIP resolves the uncertainty decision while preserving the diagnostic gap. A confirmed cancelled operation may start a fresh analysis through an explicit action; it cannot authorize an extension.
- Text, provider, language, model/catalogue generation and policies must remain compatible. Explicit changed expectations are not silently ignored.
- Store server-derived `taxonomyRequestedScope` (the requested intent, including mode) separately from the effective union in `analysisScope`. Clear untrusted server metadata at HTTP admission. Bind source ID/version and requested scope into the target signature.
- On restore, send the stored requested scope as the next continuation POST’s `analysisScope`; never reinterpret the effective union as a broader work request.
- Repeated POSTs for one target preserve its identity and frozen request; conflicting source/version/intent is rejected.
- A small `AnalysisModelBinding.withScope(AnalysisScope)` helper may change scope while preserving every other captured field.

## Journal and result semantics
- Preserve accepted SUCCESS details exactly, including raw scores, explanations, prompt/provider identity and typed scoring metadata. Replaying them makes no new provider request.
- Do not promote diagnostic scores in UNASSESSED, FAILED or other unsuccessful answers to accepted evidence.
- Preserve partial successful groups. Copy open diagnostics separately; selected unfinished work becomes eligible for a new attempt, unselected unfinished work remains unassessed.
- Enforce the requested node scope before ATTEMPT and before any provider grant. Use actual catalogue membership locally and existing shard ownership in distributed sessions; do not infer roots from code prefixes.
- Reuse existing question/payload bounds. Initialize and copy source evidence atomically with source authorization/version checks, without provider I/O under a lock.
- Resolve server-stored relations-source ancestry when its node checkpoints live in the original node-only run. Detect missing/conflicting history and cycles. Chained taxonomy extensions carry their own copied node journal.
- Seed old accepted results before new provider work. Cancellation, a failed new root or a lost response must retain those assessments.
- Rebuild coverage for the effective union from real accepted and open evidence. A completely assessed zero branch remains complete; an unselected open branch remains open.
- `retainedAnalysis` keeps its existing relations-only meaning; do not repurpose it to skip all taxonomy tasks.
- TAXONOMIES_ONLY clears old relation-dependent reports in the new target. FULL searches relations against the accumulated node result; an old report is not evidence of completeness for a broader selection.
- Existing per-operation call and storage limits remain enforced. Reused answers are not new calls. This change does not introduce a new cross-operation budget policy.
- Same selection already complete with unchanged projection options should show/replay the existing result without new calls. An automatic mode-only node-to-relations transition uses the existing relations-extension path only with an explicit provider and enabled architecture projection. A blank Server Default uses taxonomy extension so new admission checks its current identity; ordinary Analyze with architecture disabled also uses taxonomy extension to preserve that choice. The explicit Add relationship search action keeps its established provider and projection behavior.
- Source history stays unchanged. Only the active target result changes after confirmed acceptance.

## User flow
- Read current selection before deciding whether to reopen a completed Copilot run.
- Keep `inScope` tied to the already-started operation, not mutable checkboxes.
- Route all automatic starts through the durable shared start path. Preserve the ordinary architecture-checkbox choice and Copilot follow-up behavior.
- For the same source basis and unchanged visible evidence, start an extension. For a changed requirement/provider/language/model, clearly show a new analysis.
- Compare concrete provider/model identities across refreshed requests, including nullable model expectations. A blank Server Default is checked against the current default at new server admission before binding the retained source; existing target receipt reads remain independent of later default changes.
- If visible scores or explanations were manually edited, preserve them and use the existing evidence-conflict / load-saved-result action. Do not silently erase them.
- Preserve the source until admission is confirmed. On a definitive pre-admission rejection, restore it. On an uncertain network outcome, retain and observe the new target ID rather than issuing another POST.
- Bind a rejected attempt to its exact source revision and intent; another valid selection is not blocked by the previous rejection. Keep the explicit fresh-analysis escape for hidden basis conflicts.
- Final responses contain the complete accumulated result. The browser does not invent a merged authoritative result.
- Distinguish requested selection from accepted result scope in persistence, hints and subsequent starts.
- Detect unavailable selected roots, including mixed valid/missing selections; do not silently normalize them to “all”.
- Add concise German and English status/help copy within existing controls, with no second routine start button.

## Verification
Count actual provider calls and compare old scores/reasons exactly in these cases:
1. Completed BP followed by CP, then all: old accepted work is free, result scope grows.
2. Partly completed BP followed by CP only: old BP gaps remain open with no BP call.
3. Partly completed BP followed by all: accepted groups replay; only open/new groups call the provider.
4. Completed zero branches, partial product batches, node-extension chains, and relations-source ancestry retain valid evidence.
5. Wrong owner/workspace/branch, stale source version, changed basis and ambiguous provider outcomes fail before new calls.
6. Retry, restart, cancellation and duplicate submission preserve identities and accepted values in local and distributed execution.
7. Both start buttons, architecture opt-out, changed settings, manual edits, unavailable roots, no-op, rejected admission and reload use the intended path.
8. Run the normal repository Maven/CI and security gates before an exact-head squash merge.

## Scope boundaries
No new database schema, provider retry algorithm, task DAG, independent browser merge engine, or release/deployment. Preserve existing branch rules and reviews. One cohesive PR for this behavior.

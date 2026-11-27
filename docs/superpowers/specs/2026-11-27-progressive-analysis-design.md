# Optional progressive analysis presentation

Carsten wants to see DecisionTree and architecture change close to real time,
then see a more careful analysis refine the displayed result while he examines
it. PR #1610 already provides a scoped, cancellable local preview after a 300 ms
debounce. The managed full analysis already streams assessed Decision Map scores.
This increment connects those capabilities to a visible provisional architecture
presentation and an explicit background-analysis action.

## Selected approach

Keep the existing opt-in preview panel and add a provisional architecture view of
its candidate components, grouped by their actual taxonomy. Use stable keyed DOM
elements and deterministic source-order positions so scores can update without
moving focused components. The preview is read-only: never assign it to saved
scores, the canonical AnalysisResult or the architecture editor's working model.
Mark lexical/embedding predictions and provider-assessed values separately.
Navigation-only catalogue wrappers cannot become architecture components; derive
eligibility from the full admitted retained hierarchy on the server. Vocabulary
associations are never architecture relationships.

Provide a clearly labeled action to run the careful analysis for the current
input using the existing managed admission/recovery flow. This explicit action
reuses provider/mode/permissions/manual-evidence checks and the current catalogue
selection. Typing and preview refresh never invoke a generative provider. Keep
the preview visible while the existing asynchronous full analysis runs. Publish
a narrow presentation event only after effective scores are accepted by the
managed scorer, then update the matching candidate boxes and tree in place.
When complete/partial/cancelled/failed, preserve the correct status and offer the
existing full architecture view; do not claim complete relations before the
canonical result provides them. Detailed relationship streaming is not part of
this increment because the existing progress contract only carries work counts.

Every input, provider/mode, source, revision or permission change invalidates the
preview overlay generation. An edit-and-revert must not reaccept an obsolete run.
Bind all presentation events to the exact input/source/operation generation. If a
run started by this preview action becomes stale, cancel only its own pinned
monitor through existing cancellation APIs; protect new runs and saved/manual
decisions. Do not use whole-session invalidation to clear independent evidence.
Guard progress and terminal application, including late local and Artemis results.
Surface progress/cancellation errors in the existing managed flow.

## Alternatives

1. Run a full model analysis after each edit: excessive provider work, races and
   interference with manual decisions. Not selected.
2. Add a new streaming service and message transport: duplicates existing local
   and Artemis coordination. Not selected.
3. Reuse local preview plus explicit managed background refinement and scoped
   presentation hooks: selected. A future automatic mode can add an explicit
   budget and idle policy after this contract is proven.

## Verification

Real catalogue eligibility checks, no provider invocation on typing, preserved
manual state, unavailable and manual provider modes, same-text edit-and-revert,
source switching, late/out-of-order events, cancellation before registration,
stable focus/layout and DE/EN mobile rendering. Tests must distinguish actual
model evidence from deterministic transport fixtures. No hard latency promise:
measure local warm preview and explain cold initialization separately.

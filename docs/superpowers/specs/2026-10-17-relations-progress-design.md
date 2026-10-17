# Supplementary relations and graphical analysis progress

User request (17 October 2026): implement the missing analysis features and display progress bars for every sub-taxonomy and relations so completed and remaining work are visible.

## Behavior
- A completed node-only assessment can start a relations-only extension. Existing scores, explanations, requirement text, authorized provider decision and exact catalogue authority are retained. Node scoring is not executed again. Existing continuation lifecycle, capacity, cancellation, retries and durable evidence remain authoritative.
- The extension is explicit, owner/workspace scoped, version checked and idempotent; stale/foreign/running sources cannot be reused silently. No client-supplied scores are treated as trusted server analysis evidence. Partial accepted source results remain partial and unassessed nodes are not invented as zeroes.
- Local and Artemis progress use the existing AnalysisNodeProgress and RelationSearchProgress DTOs. Distributed progress is recorded at valid worker/claim boundaries, survives reload and does not count stale or duplicate deliveries twice. Existing failure, capacity and wait reasons remain visible.
- Every participating sub-taxonomy has a labelled progress bar plus completed/open counts. Node coverage distinguishes direct assessments from exclusions by parent decision. Relations have a total bar and target-taxonomy bars. Relations count search work, not the number of found relations.
- Unknown or still-expanding work is described honestly; do not fabricate a fixed percentage or mark errors/cancellation as successfully completed. Keyboard/screen-reader access, forced colors, mobile widths, reduced motion and DE/EN remain usable. Keep the existing status dismissal and reopening behavior.
- Stabilize PR #1249's migration and restore regressions without discarding its explanation-length feature or another worker's changes.

## Constraints and acceptance
Java 21, existing Maven/JUnit owners, no new Actions workflows or relaxed coverage/test policies. Add meaningful JUnit regressions and browser-driven DOM assertions under the existing Maven owner. Do not use a paid/live provider. Verify retained scores are byte/value equivalent, unauthorized extension fails, a node-only result extends without scoring calls, cancellation retains partial relations, and local/distributed progress is truthful. PRs and current-head CI are checked before merge; do not claim deployment or live Gemma quality without evidence.

# Resumable evaluation and visible progress

Approved scope: preserve valid individual assessments; on failure allow retry, leave this question unknown and continue independent work, or cancel without erasing evidence. A scored parent remains scored even when descendants are unknown. Unknown is never a numeric zero. The current progress and any decision remain in the visual viewport, including scrolling, narrow layouts and reduced viewport height.

Reuse the existing top-down evaluator and provider gateways. A durable, owner/workspace/branch/repository-bound journal caches complete validated questions, not HTTP response fragments. Replaying traversal reuses successful questions without contacting the provider. Category questions are atomic sibling sets; product questions retain their independent batches. Changed catalogue, prompts, policy, provider or request must not reuse incompatible evidence. Persist before publishing results. Never hold a worker, HTTP request, transaction or lock while awaiting a user's decision. Double decisions are rejected by version/claim checks. A process crash leaves an explicitly uncertain in-flight attempt; only an explicit decision after its bounded claim expires can retry it.

A result separates local assessment (relevant, not relevant, unknown) from descendant coverage. Failed calls and descendants blocked by them carry different reasons. Exports and saved drafts keep coverage; unknown branches must not create false coverage gaps, full-completeness claims or invented taxonomy entries.

The failure dialog has retry, leave-open, and cancel actions. Closing the dialog keeps the run paused. A compact visual-viewport status surface remains available, without heartbeat scrolling or focus stealing. The original requirement is never silently edited or shortened.

No new microservice, no remote real-LLM testing, no weakened quality gate. Delivery is a reviewable patch with exact verification evidence; remote publication is claimed only after an actual successful write.


## Delivered contract and explicit boundaries

The journal lives in the existing analysis module and existing database. Managed category questions are atomic complete sibling sets; managed product batches are independently checkpointed. A saved exact-input result is reused without provider I/O. A failed call's latest diagnostic answer and attempt count are retained; this is not a complete immutable history of every attempt.

An interrupted run with no unresolved in-flight question can be explicitly continued even while earlier skipped areas remain open. A memory/time guard interruption must remain STOPPED, not COMPLETED_WITH_GAPS. The 35-minute execution claim is longer than the default 30-minute evaluator limit; deployments changing the evaluator limit must keep those bounds consistent. Crash recovery never automatically reissues an uncertain provider request.

Own assessment labels describe the existing scoring contract, not necessity or calibrated probability. Existing scores and catalogue identities are not redesigned. Missing/error assessments have no numeric value. Global gap/pattern/recommendation algorithms are not fed incomplete inputs: they remain explicitly open, while valid local evidence and a qualified architecture are available. Successful subsequent Copilot stages are cached in the existing durable draft.

JSON exports carrying coverage use format version 3 and retain raw/effective evidence. Existing version 1/2 files remain readable; older clients reject version 3 rather than silently discard its unknown-state contract. CSV adds state/completeness/reason columns. Full-model diagram titles and decision-report warnings qualify partial results; bounded graphical annotations do not replace the full JSON coverage record. The existing relation-search engine is not made independently resumable at every internal query in this change.

The optional loopback browser acceptance entry point is `.github/scripts/ui-analysis-recovery-acceptance.mjs`. The normal UI contract commands execute the new recovery/viewport unit regressions. Actual Chromium component tests and separate real-Spring HTTP tests are supplementary evidence, not a substitute for the full Maven, database and browser matrix. No adaptive prompt shortening, provider switching, new background retry, new microservice, or catalogue restructuring is included.

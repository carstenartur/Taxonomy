# Requirement reformulation implementation

Approved by the user's full implementation request, 20 September 2026. The
authoritative product specification is the German plan supplied in that request;
this execution copy preserves its requirements and eight sequential packages.
Baseline: `b45711981d8e1bdaf38642b4c3469ae28bd76a12`.

## Global constraints

- Saving a proposal, answering a question, and synthesis NEVER change source text,
  requirement version count/current pointer, active architecture or review status.
- Adoption is a separate preview and explicit confirmation. Preserve all historic
  versions. Answering every question is neither adoption nor expert approval.
- One source requirement version per offer. Reference project context without
  silently mixing independent requirements. Freeze the EXACT selected version,
  its text/hash, snapshot and relevant context CONTENT, not just hashes.
- Keep provenance independent of review status. Original, catalogue inspiration,
  architecture hypothesis, model addition and human decision remain distinguishable.
  Repeated model assertions never become independent confirmation/user intent.
- Every question retains discovery location, rationale, source/node/edge links,
  affected statements, answer contract, dependencies and consequences. Carry IDs
  upward; do not lose questions or duplicate them at every ancestor.
- Every wording call receives the FULL original text, relevant source anchors,
  parent/children/direct contributions, child wording and IDs, boundary edges,
  scoped human answers/open decisions, and the strict preservation contract.
- Original text, answers, catalogue descriptions and model output are untrusted
  DATA, never instructions/permissions. Validate IDs and spans, escape UI/exports.
- Existing authenticated repository/workspace/branch/project/requirement scope
  applies to reads, writes, exports, cache, resume and diagnostics. Actor and scope
  come from authentication/context, never unchecked JSON.
- Pure shared contracts in taxonomy-domain; JPA/repositories/lifecycle in
  taxonomy-portfolio; formulation in taxonomy-analysis via existing LlmGateway,
  Registry and budget policies; catalogue in taxonomy-knowledge; frozen architecture
  in taxonomy-architecture; wiring/controllers in taxonomy-app. No new microservice,
  scoring engine, Maven hierarchy or reverse workspace dependency.
- Semantic decisions/revisions/audit persist independently of Git. Only existing
  deliberate checkpoint boundaries commit to Git; portable evidence joins the
  existing atomic checkpoint, never a separate commit per answer/evidence file.
- TDD: write and run tests before implementation, record meaningful RED and GREEN.
  Independent spec and quality review per package; no merge before applicable gates.
  Full CI (DB/security/packaging/export) still applies. Do not inflate architecture
  baselines to hide dependencies. Positive test counts, not failIfNoSpecifiedTests,
  demonstrate execution. Real model quality is separate from playback test success.

## Product specification

### Workflow and UI

At the saved requirement's existing detail page, select an existing analysis
snapshot and invoke “Neuformulierung vorschlagen”. This must not trigger an
architecture analysis. Unapproved/incomplete architecture contributions stay
labelled accordingly. Present “Neuformulierungsangebot” with immutable original,
source version, continuous structured proposed text, short parent summaries plus
details, inline questions and shared question list, additions/assumptions/conflicts,
uncovered original spans, taxonomy/edge/derivation references. Desktop side by side;
narrow view labelled Original / Vorschlag / Fragen without required horizontal
scrolling or loss of unsaved edits on switching. Keyboard/focus/live status; DE/EN.

Actions: propose -> new run/revision; save draft -> new immutable revision; answer
or defer -> decision event/revision; synthesize affected -> candidate revisions;
save variant -> separately addressable proposal variant; compare/copy/export -> exact
saved revision; adopt -> confirmed new/reused version; analyze adopted version ->
explicit new analysis, never overwrite old snapshot or automatically approve it.
Never label version switch with ambiguous “Speichern”. Adoption dialog shows old
active version, exact proposal revision, final text, open questions and target.

“Arbeitszeiterfassung” should yield useful coherent wording with marked additions
and concrete capture-channel/correction questions, NOT invented legal obligations.
Capture options can include browser, electronic card+terminal, paper card+later
entry, combination, other, open; model explicit single/multiple choice. Offline
question only if grounded in actual selected design/variant. “No correction needed”
is valid absent a confirmed contradictory source requirement. “Ausschließlich
Terminal, keine Browseroberfläche” and small low-score deadlines must survive.

### Contracts, persistence and states

ReformulationBaseline: repository/workspace/branch/project/requirement identity,
source version/text/hash, snapshot, workspace semantic revision and dirty/conflict
state when applicable, frozen catalogue/relations/prompt context, language,
algorithm version. Proposal: stable ID/baseline/creator/current proposal revision,
no authority over active requirement pointer. Revision: immutable text/structure,
statements, questions, answers, validation, predecessor/actor/time/rationale.
Statement: stable application ID, wording, source spans, provenance, architecture
links, question dependencies, conditional validity, editing origin/review state.
Section: taxonomy layout, brief parent summary, children, shared statement refs.
Question: stable ID/key(subject+dimension+scope), wording, context/reasons/discovery,
source/node/edge refs, affected statements, answer schema/options/unit/range,
prerequisites/dependent questions/impact. Answer: human author/time/rationale,
proposal context; separate from model recommendations. Coverage findings distinguish
structural loss/unmapped source/conflicts from semantic review suggestions.
Run/tasks: persisted status/fingerprint/attempt/lease/cancel/usage/results.
Adoption: command ID/confirmed preview hash/source+target version/actor/rationale.

Independent axes: runs QUEUED/RUNNING/PAUSED/COMPLETED/PARTIAL/FAILED/CANCELLED;
questions OPEN/ANSWERED/DEFERRED/NOT_APPLICABLE/CONFLICT; proposals saved/superseded/
discarded/adopted to a specific version. COMPLETED does not imply approved/ready.
Original-answered questions are explained as such, not asked again. If current
requirement changes during synthesis, retain selected baseline and show it as older.
Persist explicit saves, not every keystroke. Corrections append evidence/events.

### Bottom-up and reconciliation

Freeze selected snapshot and all used node descriptions, direct/propagated mappings,
typed/directed/reviewed edges. Build source anchors that separately preserve
conditions/exceptions/numbers+units/negations/modality. Unmapped originals remain in
visible remainder. No fabricated source attribution for architecture-only ideas.
The stopped analysis front is not necessarily catalogue leaves; positive terminal
intermediate nodes contribute. Score-zero explicit restrictions are retained.
Deterministic DAG scheduler: deepest relevant parents consider relevant children
together; no mandatory call per leaf. Include direct parent contribution separately.
Handle shared statements/multiple parents without duplicate copies. Cycles fail with
involved IDs; no matches -> limited source-based proposal/questions, no invented path.
Synthetic document root is not a catalogue element.

Structured model response: summary, statementProposals, preservedStatementIds,
questionProposals, preservedQuestionIds, uncoveredSourceRefs, conflictCandidates.
Application assigns IDs. Check references, allowed edges, spans, preservation,
schema and truncation. One bounded repair attempt with same input plus errors,
then FAILED/PARTIAL. Mark plausible additions and decisions. Unfounded numeric
targets cannot claim original provenance. Parents summarize, never replace details.

Phase A freezes independent per-taxonomy drafts. Phase B groups cross-taxonomy
relationships/shared statements/decisions and proposes contradictions, missing
interface requirements and duplicate corrections. Maximum TWO reconciliation
rounds; reword only affected sections/ancestor summaries. Residual differences
remain conflicts/questions. No changing peer outputs mid-round.
Same semantic key + compatible answer schemas may merge, preserving ALL discovery
locations/reasons. Different scopes stay separate. Similar wording alone never
auto-merges answered questions. Conflicting answers -> CONFLICT, never last-writer.
Default process/capability-led layout when present; otherwise suitable existing
taxonomy, with applications/services/roles/information supplementing sections.
Cross-cutting, interface and unmapped sections; alternate views reference same IDs.

### Editing, cost and recovery

Answers create revision only; user can preview affected statements/sections,
ancestors, question dependencies and boundary interfaces before rewording.
Independent branches remain byte/stably unchanged; global decisions invalidate
globally. Human edits remain marked/protected. Contradictory/new/late model output
creates candidate+diff/visible conflict, never overwrites user wording. Explicitly
rejected additions do not reappear. Reject/defer/not-applicable differ. Show predicted
architecture impact but do not delete/deactivate architecture automatically.

Keep FULL original in every call. Group wide child sets, then aggregate while
preserving every statement/question/boundary edge. If original + minimal instruction
and output budget cannot fit provider context, fail start with
INPUT_TOO_LARGE_FOR_PROVIDER and explain; never silently truncate/summarize source.
Persist deterministic tasks, fingerprints, result revision/status/attempt/usage,
bounded branch parallelism. Scope-separated cache includes source version/hash,
catalogue/relations/node/boundary inputs, child revisions, relevant answers/manual
edits, model/prompt/schema/algorithm versions/language. Irrelevant local changes do
not invalidate all branches; global changes do. No LLM inside open DB transaction.
Short transaction state transitions and lease/attempt fencing prevent stale publish.
Resume after restart; cancellation and exhausted budget preserve labelled partial
results/missing sections/questions. Separate provider/429/timeout/invalid JSON/
context/cancel failures; reuse provider retry/budget policy. Metrics: actual calls,
retries/tokens when known/cache hits/groups/rounds/duration. No fabricated costs.
Default logs have technical IDs/usage, never full requirements/answers.

### Adoption and portability

Immutable preview includes EXACT final text, baseline and current versions, proposal
revision, included additions/removed source/answers/open questions/manual edits/
provenance and repository/branch context. Hash these data. Confirmation supplies
preview hash, expected active source version, expected proposal revision, unique
commandId. Recheck authorization/scope/context/version under aggregate lock.
Same ID+same command returns identical result, same ID+different payload -> conflict.
Use addRequirementVersion ONLY after confirmed preview; identical existing text may
be reused explicitly (it also moves current pointer). SOURCE_VERSION_CHANGED for
stale source, new comparison required; old proposal stays readable. Stale If-Match
->412; baseline/preview/business conflict ->409; invalid answers/structurally invalid
adoption ->422. Never automatic merge.
Allow explicit DRAFT adoption with visible acknowledged open questions and neutral
wording, retain structured question evidence on adopted version. Changed text resets
current status/review to DRAFT/PROPOSED, keeps old review evidence on old stand, old
analysis stale for new text. Separate existing expert review path must not accept
blocking conflicts/invalid results. “All questions answered” is not approval.

Historical report model contains exact immutable baseline/revision; renderers do no
DB reads/LLM calls. JSON/Markdown/HTML/DOCX show original/proposal/questions/answers/
provenance/links and “Neuformulierungsangebot – nicht übernommen” or exact adoption
state. Safe HTML. DOCX actual parser+independent renderer QA, snapshot-consistent
architecture images/links. Deliberate checkpoint includes content-addressed portable
versioned evidence with stable business IDs/texts/decisions/hash/provenance in SAME
atomic portfolio Git checkpoint. Roundtrip separate repo, old formats readable,
unknown schemas rejected. Unfinished jobs need not be in Git.

### API and existing seams

Base /api/projects/{projectId}/requirements/{requirementId}/reformulations:
POST / ->202; GET /{proposalId}; POST /{proposalId}/revisions;
POST /{proposalId}/synthesis-runs; POST /{proposalId}/cancel-run;
POST /{proposalId}/adoption-previews; POST /{proposalId}/adoptions;
GET /{proposalId}/revisions/{revision}/export (JSON/Markdown/HTML/DOCX).
Use If-Match for optimistic revisions. Existing API remains compatible.
Reuse ProjectRequirement/Version, RequirementAnalysisSnapshot, ProjectPortfolioService,
LlmGateway/Registry/budget policies, PortfolioScope/WorkspaceContext, existing detail
template/JS, ScenarioLlmPlayback, PortablePortfolioGitService. Additive Flyway migration
number chosen from current files. No broad unrelated refactor.

## Task 1: Independent offer and immutable baseline

Delivery: persistent proposal space, frozen baseline, revisions, read-only requirement
detail area. No generation/adoption writes to original. New framework-free common
contracts (ReformulationBaseline/Statement/Section/DecisionQuestion/DecisionAnswer/
NodeSynthesisInput/Result/ValidationReport), JPA proposal/revision/run/node task/
adoption scaffolding only as needed, repositories/services in portfolio,
ReformulationBaselineAdapter and reading controller in app; additive migration;
read-only UI fragment. Existing versioning must be untouched on proposal creation.

- Write ReformulationContractTest for source version/frozen text/invalid scope and
  snapshot combinations. Write Spring ReformulationIsolationTest: create offer and
  two draft revisions, assert source text/count/current version/architecture unchanged.
- Execute RED before implementation. Implement immutable content and real scope checks.
- Test foreign workspace, requirement and manipulated snapshot through real API/DB.
- Demonstrate persisted reload/restart and no new active version. Focused tests,
  architecture boundary checks, small truthful documentation, commit.

## Task 2: Bottom-up text and questions within a taxonomy

Implement analysis/reformulation/WalkUpPlanner, NodeReformulationService,
ReformulationPromptBuilder, ReformulationResponseParser, prompts/reformulation-node.txt
and narrow connection to proposal execution. Reuse Task 1 contracts.
- First tests WalkUpReformulationTest and ReformulationResponseParserTest: 2 parents,
  3 terminal contributions, direct parent contribution; every call full original.
- Countercases: score zero explicit restriction, stopped front, no match, malformed
  hierarchy/cycle/DAG, truncated JSON, invented IDs. Execute RED.
- Deterministic plan and real existing provider transport, strict response validation
  and bounded repair, preserve child wording/statement and question IDs in parents.
- “Arbeitszeiterfassung” coherent useful proposed text/concrete open questions;
  precise no-browser restriction preserved; original unchanged. Document prompt/
  schema versions; tests and commit.

## Task 3: Cross-taxonomy decisions and coverage

Implement CrossTaxonomyReconciler, ReformulationCoverageValidator, reconcile prompt,
extend existing contracts only. Test CrossTaxonomyReconciliationTest FIRST:
- Process/application/service depend on capture method -> ONE question with three
  derivations; equal wording/different scope separate; conflicting answers conflict.
- Directed edges/shared information/cross-cutting constraints/unmatched originals.
- Frozen rounds, exact semantic identity, <=2 rounds, affected ancestor synthesis.
- Separate structural preservation from semantic suggestions; suppressed restrictions
  and unfounded additions visible. Repeated origin isn't multiple evidence.
- Test residual conflicts after two rounds; original test statements preserved or
  visibly unresolved. Commit.

## Task 4: Interactive text/questions/answers/revisions

Implement ReformulationQuestionService, proposal operations, requirement-reformulation.js
and fragment, existing detail wiring and DE/EN translations.
- FIRST ReformulationQuestionWorkflowTest and ReformulationBrowserTest: views/focus/
  saving without version switch; all answer types, open/other/multiple/follow-up,
  visible additions. Human edited paragraph + answer + late synthesis protected.
- UI answers/defer/not-applicable/edit/compare/copy/save variant/targeted synthesis;
  question links to text/graph details, permanently accessible original.
- Scoped invalidation statements/ancestors/dependencies/boundary edges; independent
  branch stable; global answer broad; rejected addition stays rejected; stale output
  yields candidate/diff/conflict. Real browser narrow+wide/keyboard/live status and
  screenshots. Commit. Terminal answer still leaves active text Arbeitszeiterfassung.

## Task 5: Persistent execution, recovery and bounded cost

Implement ReformulationExecutionService/run+task repositories/grouping/cache using
existing portfolio infrastructure/provider policies, not a new general job platform.
- FIRST ReformulationRecoveryTest: restart after child result, no duplicate publish;
  429/timeout/invalid output/cancel/stale attempt; partial draft retains questions.
- FIRST ReformulationBudgetTest: wide fanout with boundary edges, original too large.
- Persistent scheduler, bounded concurrency, child groups+aggregation without loss,
  scope-separated complete fingerprints; unchanged cached run uses zero new calls.
- Short DB transactions, no LLM transaction, leases/attempt/revision fencing; local
  vs global invalidation and human-edit preservation. Usage report and real restart
  evidence. Tests and commit.

## Task 6: Separate conflict-safe adoption

Implement ReformulationAdoptionService, adoption record, preview/confirm API and UI,
narrow locked/expected-version adapter around addRequirementVersion.
- FIRST ReformulationAdoptionTest: generate/answer/save never switch, only adoption.
- Stale preview/source, foreign scope, duplicate command, same ID different payload;
  identical old text reused only via explicit adoption without artificial duplicate.
- Exact preview hash/expected revision/authorization/atomic adoption-version update.
- Open questions allowed with explicit acknowledgement; invalid structure refused;
  changed text DRAFT/PROPOSED not old approval; old analysis stale and evidence kept.
- Second full app startup against same DB reconstructs adoption/source unchanged.
  Tests, commit.

## Task 7: Snapshot reports and portable adoption evidence

New export/reformulation/ReformulationReportModel, MarkdownRenderer,
ReformulationEvidenceDocument; app report controller/HTML+DOCX adapters, portfolio
ReformulationEvidenceCodec; existing PortablePortfolioGitService checkpoint seam.
- FIRST ReformulationExportTest: source/revision/status/refs same saved stand;
  changing current requirement leaves old exported result unchanged, ZERO LLM calls.
- JSON/MD/HTML/DOCX with explicit not-adopted/adoption labels and provenance.
- FIRST ReformulationEvidenceRoundTripTest: stable content-addressed schema evidence
  joins SAME atomic checkpoint, separate repo import/export, old schema compatibility,
  unknown schema fails. No workspace -> portfolio dependency or local-ID-only evidence.
- Actual DOCX parser + independent renderer verification and snapshot-consistent
  diagrams/question links. Tests, commit.

## Task 8: Full civilian acceptance and real quality comparison

Extend ScenarioLlmPlayback and civilian-flood scenario with reformulation tasks,
preserve real catalogue links/provenance. Add authored time-recording and precise
negative scenarios (clearly labelled authored, no fake external source), including
reformulation-time-recording.json and reformulation-cross-taxonomy.json.
- Match playback semantically by task/requirement/node/child references/answer state,
  order independent, unknown request MUST fail. Mock ONLY remote LLM responses.
- ReformulationCivilianAcceptanceTest: requirement -> REAL analysis -> saved snapshot
  -> walk-up -> questions -> answer -> revision -> compare -> explicit adoption ->
  new analysis -> export -> restart. No prebuilt architecture/HTML/DB/render states.
- Add new tests explicitly to civilian-acceptance profile and verification-suites.json.
- Real remote model runs separate from deterministic playback, compare one complete
  prompt vs walk-up on terse/precise/contradictory/multilevel/incomplete inputs.
  Report condition loss/unmarked additions/question usefulness/duplicates/conflicts/
  readability and actual calls/tokens/work, not JSON success as language intelligence.
  Use existing real-llm suite, no hidden regular CI network dependency. If provider
  credentials unavailable, report that honestly and leave acceptance pending.
- docs/testing/requirement-reformulation.md, docs/en+de/REQUIREMENT_REFORMULATION.md,
  README short note + architecture description on integrated stand; real screenshots.
- Full tests below and existing CI gates, commit. No blanket quality guarantee.

## Verification commands

Use Java 21 target. Local available JDK25 can compile --release 21; disclose runtime.
Local Maven command prefix currently:
`JAVA_HOME=/tmp/taxonomy-toolchain/jdk-25.0.2+10 PATH=/tmp/taxonomy-toolchain/jdk-25.0.2+10/bin:$PATH ./mvnw -B -ntp -Dmaven.repo.local=/workspace/scratch/dae1667028d4/m2`.
Use -o only if cache sufficient; no product dependency changes for environment.

```
./mvnw -B -ntp -pl taxonomy-domain,taxonomy-analysis -am test -Dtest='*Reformulation*Test,CrossTaxonomyReconciliationTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl taxonomy-app -am test -Dtest='ReformulationIsolationTest,ReformulationQuestionWorkflowTest,ReformulationRecoveryTest,ReformulationAdoptionTest,ReformulationExportTest,ReformulationEvidenceRoundTripTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp test -Parchitecture-tests -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp -pl taxonomy-app -am test -Pcivilian-acceptance -DgenerateScreenshots=false
./mvnw -B -ntp -pl taxonomy-app -am test -Pcivilian-acceptance
./mvnw -B -ntp verify -DexcludedGroups='real-llm'
```

Existing baseline's .mvn config enables container Keycloak tests. Environment
limitations must be documented, never misreported as executed tests. Verify positive
test counts and correct selected classes. Controller coordinates independent review
and full-suite gates; workers report focused RED/GREEN evidence without redundant
parallel full reactors.

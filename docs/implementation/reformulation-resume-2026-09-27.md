# Resume after execution-runtime outage — 2026-09-27

Status: blocked by unavailable execution environment, NOT complete or merge-ready.

## Durable source checkpoint

- Draft PR: https://github.com/carstenartur/Taxonomy/pull/1135
- Branch: `feature/reformulation-durable-completion`.
- Last source/test/report checkpoint fully matched to local and remote:
  `4f1a238122a7369b7122ab9c95ebd85bc5e6bbc3`, tree `79c2b365a5a94cdeeed61a800fdc0b91b5a0f615`.
- At the outage, implementer `frozen_docx` explicitly confirmed **no local source
  edits since that checkpoint**. No uncommitted source work is missing.
- Execution returned `exec-server transport closed`, then HTTP 409
  `environment_offline: Environment is not connected`. Root and implementer
  paused new edits. The in-flight diagnostic test outcome is UNKNOWN.
- This recovery note and the progress update were subsequently saved directly
  through the configured GitHub connector. They change documentation only; local
  checkout alignment could not be performed while the environment was offline.

## Completed work: do not repeat

Tasks 1, 2a and 2b are independently accepted. See their task reports and reviews.
Task 2b final code was reviewed at `b885b24724da54b8d32186b953997f4380745eab`.
Its last correction has 16/16 focused GREEN tests; earlier 41/41 was a distinct
pre-second-correction covering run.

## Task 3: current evidence and next defect

Read `reformulation-task3.md` and `reformulation-task3-report.md`.
DOCX revision/receipt output, binary hashes, controls, frozen graph, directed
parallel relations, canonical edge references, internal Word navigation, gap
availability and frozen coordinate checks are implemented, but NOT accepted yet.

Latest completed covering command:

```bash
python3 .superpowers/sdd/reformulation-completion/run-maven.py \
  -pl taxonomy-app -am \
  -Dreformulation.docx.qa.dir=/workspace/scratch/38625e9262ff/reformulation-task3-qa \
  -Dtest=ReformulationReportTest,ReformulationReportHistoryTest,ArchitectureContextDependencyRatchetTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: **31 tests, 1 failure, 0 errors**. Architecture ratchet 22/22 and report
tests 7/7 passed. The JS control test passed; the historical restart test failed
because parsed DOCX proposal text differed in the fresh process. No blanket GREEN.

A diagnostic comparison naming the first differing paragraph is already committed.
The following diagnostic run was interrupted; DO NOT treat it as completed:

```bash
python3 .superpowers/sdd/reformulation-completion/run-maven.py \
  -pl taxonomy-app -am \
  -Dtest=ReformulationReportHistoryTest#exactHistoricalReportsSurviveEditsAdoptionAndRestart \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Root identified a concrete likely cause to verify: export-owned
`FrozenReformulationArchitecture` changes the assembler's TreeMap to
`Map.copyOf(identity)`. Renderer uses identity.forEach and identity.toString;
iteration order can differ across JVMs. No production correction for this has
been made yet. Verify the paragraph difference, add/fix a deterministic regression,
then use an immutable ordered copy if confirmed. Do not repeat already observed
REDs or the whole broad suite unnecessarily.

## Independent document rendering — partial inspection only

App-produced files (temporary execution storage, availability after reconnect
unknown; reproducible with the covering command):

- `reformulation-task3-qa/reformulation-de-adoption.docx`
- `reformulation-task3-qa/reformulation-en-graph-answered.docx`

Root successfully rendered both using the documents skill's
`/root/.codex/skills/builtins/documents/render_docx.py` with
`CODEX_PRIMARY_RUNTIME_PYTHON`, LibreOffice and Poppler. DE has 3 pages; EN has
6 pages. PNG/PDF outputs were in `render-de` and `render-en` beside the DOCX.

Root inspected **DE pages 1–3 and EN pages 1–3 only** before transport failed on
EN page 4. Do NOT claim all-page visual approval. Observations to resolve/check:

- Draft versus adoption labels are clear; malicious-looking test strings remain
  literal; source/answer/provenance information is visible.
- Raw Java `Discovery[...]` / record-style dumps and mixed English metadata remain
  in the DE human-readable report. Replace structured dumps with readable labelled
  fields, preserving evidence and avoiding broad existing-report refactoring.
- Later rendered pages start very near the top edge; inspect actual page/section
  margins and confirm no clipping. This is an observation, not yet a diagnosed bug.
- EN graph report and DE no-graph report show different body font treatment;
  inspect duplicate style initialization in the composed architecture section
  before claiming consistent layout. Do not make speculative global style edits.
- Inspect EN pages 4–6, graph readability, internal targets and final pagination.
  Re-render changed outputs and inspect every page before document acceptance.

These are authored report/architecture input fixtures persisted through actual
application services, followed by the real exporter/POI renderer. They are NOT
a remote-only real-analysis E2E or actual-language-quality proof.

## Resume safely

1. Reconnect/provide the execution environment. Inspect local status before writes.
2. Read the remote branch and this note. If the old clean checkout survives, fetch
   then fast-forward it to the remote branch; if it does not, restore from the
   remote branch into an isolated worktree. Never reset away user changes.
3. Restore the test runtime only if absent: Java21, Maven3.9.16, task-local m2 and
   the ignored `run-maven.py` helper. Prior locations:
   `/workspace/scratch/38625e9262ff/toolchain/jdk-21.0.12.1+1`,
   `/workspace/scratch/38625e9262ff/toolchain/apache-maven-3.9.16`,
   `/workspace/scratch/38625e9262ff/m2`.
   Helper used per-command current HTTPS proxy settings, system Java truststore,
   disabled build cache and the configured Mockito5.23.0 startup Java agent.
   Never extract credentials or change product code for runtime limitations.
4. Continue the single Task3 implementer; next targeted test is above. Then complete
   visual QA and obtain a fresh independent Task3 review. Task4 has not started.
5. Task4 brief is already stored: civilian application/browser/restart acceptance,
   docs and explicit opt-in real-model comparison. No actual provider credentials
   were found in the earlier presence-only preflight; real-language and human
   quality gates remain separate and open.
6. Preserve the checkpoint protocol: before long tests / at most10min editing,
   commit WIP, root publishes and verifies the complete remote tree, fetches and
   aligns local ref, then resumes. No automatic merge or force push.

Publisher recovery detail: expand renamed paths with `git diff --no-renames`
so both deletion and addition are uploaded. The initial rename attempt failed
the exact-tree check and DID NOT advance the remote branch; corrected publication
succeeded. Retain exact blob/tree SHA verification, scope/ref checks, binary-safe
blob transport and non-forced ref updates. Do not replace these with unchecked
file-by-file publication.

The current task is blocked by execution availability, not permission to alter
main. Main remains untouched.

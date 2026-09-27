# PR #1135 — isolate real reanalysis from the reactor test heap

## Confirmed RED and scope

Head `a571e779c25c23fa1fe9fff321bb45dab0232e40` fails the normal Surefire suite in
Database Compatibility run `36341091078`: PostgreSQL job `108681190939` and
SQL Server job `108681191118`. Each reports 1,962 tests with one failure:
`AdoptedLineageRealReanalysisTest` expected SUCCESS and received PARTIAL with
MEMORY_PRESSURE. In PostgreSQL the test began after 1,230 seconds in the shared
application test JVM, and its analysis stopped within 316 ms. This is not a SQL
assertion or a coverage failure. The 26/26 focused guard tests did not cover it.

## Decision and implementation

Ruling: use the existing CivilianAcceptanceProcess fresh-JVM pattern for this
real analysis scenario. Repeated Spring application contexts in the reactor share
a JVM; the scenario must exercise a fresh application heap. Preserve the parent's
maximum heap, production memory policy, full assertion set, Maven selector and
JaCoCo agent. A real memory-pressure failure in the child still fails the test.
Do not mock memory admission, accept PARTIAL, raise thresholds, or disable tests.
Only test support changes. The inference that shared-JVM history triggers the
failure will be checked by the unchanged scenario in its own process and fresh CI.

The JUnit entry point retains its class/method identity. The child executes real
Spring lifecycle/security callbacks, the existing fixture and the complete
adoption -> real reanalysis -> new frozen offer -> Git checkpoint assertions.
Nonzero exit, timeout, absent completion marker and cleanup failures remain fatal.
The child checks its distinct PID and inherited heap ceiling. Full child output
is retained under surefire-reports/adopted-lineage/application-process.log.

## Focused verification and scoped review

WIP was secured remotely at `28a62c585618b0885d198c4b3f836e9cf3902fdf`, full tree
`1c8f79c946d86d57e9c18765fe7c9c0b6e7ce6f5`, before testing. The following command
completed on Java 21 at 2026-09-27 19:38:53 UTC with BUILD SUCCESS: the actual
reanalysis scenario 1/1 and AnalysisMemoryGuardTest 7/7, no failures/errors/skips.
Child diagnostics: 322,116,312 used bytes, 2,147,483,648 maximum bytes; success marker
after Spring shutdown. These are focused results, not a full-suite success.

Independent scoped review of the three-file delta approved it for full CI: no
Critical/Important findings; original scenario body and assertions identical after
whitespace normalization; fixture/security/cleanup and real resource guard retained.
Review did not prove the shared-JVM-history inference or certify production memory
behavior. That requires fresh full CI; neither is claimed from focused success.
Minor deferred: DB workflow does not upload the child log on success/timeout;
nonzero child exit includes its log in the Maven assertion output. The artifact
collection improvement belongs with Task 4, keeping this correction narrowly scoped.
The final merge gate remains full CI at the resulting exact PR head.

Command:
`python /workspace/scratch/38625e9262ff/toolchain/run-guard-maven.py -pl taxonomy-app -am test -Dtest=AdoptedLineageRealReanalysisTest,AnalysisMemoryGuardTest -Dsurefire.failIfNoSpecifiedTests=false`

Task 4 stays in the separate continuation branch; this is not completion of the
original eight-package product specification.

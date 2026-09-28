# Database and evaluation log review, 2026-09-28

Evidence: Database Compatibility run 36414706950, Microsoft SQL Server job
108903047156, source head 934d3cbe695ebed7f435663eff4495849174965c.
The downloaded database-database-mssql artifact 10968467363 has SHA-256
f7303e6cca66f032363789d74f0903ff94f5159ade86704881ee666532b66a94.

## Failure and bounded correction

All 15 DiagnosticsMssqlContainerIT tests passed. SeleniumMssqlContainerIT failed
in AbstractSeleniumContainerIT.startContainers, waiting 30 seconds for taxonomyTree
after form login. The database and application containers had already passed
readiness. PostgreSQL and Oracle jobs passed. This is not evidence that all SQL
Server functionality failed, nor that increasing database or browser timeouts is
appropriate.

The shared browser setup immediately navigated to `/` after submitting the login
form. The correction observes the protected page returned by the authentication
flow without an intervening navigation. It preserves all timeouts, authentication,
role-surface, rendered-tree and REST assertions. Failure evidence is now saved in
`target/failsafe-reports/<test-class>-startup.json`, collected by the existing
workflow. Only page classification, structural booleans and exception class names
are recorded; no HTML, URL queries, passwords, cookie values or CSRF values.

The old artifact lacks browser URL/DOM/application-request evidence. A cancelled
login submission is a plausible race, not a proven reconstruction of that run.
The exact-head database suite must confirm the correction; persistent failures
must be investigated using the new evidence rather than retried into a green run.

## Warnings and exceptions

* Testcontainers SQL Server prelogin connection resets occurred while the database
  was starting; successful JDBC readiness followed. They did not terminate this run.
* Database unavailable, invalid LLM JSON, indexing failures and credential-file
  cleanup exceptions also appear inside successful negative tests. The surrounding
  test names and assertions, not log severity alone, determine whether they are expected.
* Missing API keys/model files in credential-free tests do not prove a provider ran.
  In particular, `Error in KNN vector scoring; returning zero scores` in the passing
  ArchitectureIntelligenceTests is a warning against interpreting those tests as
  real ONNX quality evidence. Real-inference evaluation must assert model/index
  readiness and keep technical errors distinct from quality misses.
* The Log4j-provider message in VisioGraphicalQualityTest, explicit HSQLDialect
  warnings and the 317 MB Docker build-context warning are separate maintenance
  observations, not the failing assertion. The Docker context already contains
  only the application JAR and Dockerfile; .dockerignore cannot remove that required
  JAR. No global log suppression or arbitrary dependency changes are introduced.

## Completeness review correction

A real saved scenario snapshot from run 36414707020 was mutated with a top-level
analysis warning. The old quality helper incorrectly returned PASS. The corrected
helper requires the warnings array and marks such unreviewed evidence INCONCLUSIVE,
retaining the observed relation counts without declaring a relation wrong. Reports
add warning counts and stop-reason presence, not diagnostic text. Five JUnit methods
cover global/relation warnings, stop reasons, malformed metadata and terminal states.

Supplementary Java 21 compilation and 20 warning/state/privacy checks passed using
the actual helper and Jackson 3.1.5 from the exact source-tree application artifact.
They post-process a recorded snapshot; they are not a new model execution. The new
JUnit methods and Docker/Selenium integration have not run locally: the working
source is an exact recovered subset, Maven/dependency downloads are unavailable,
and the local Chromium policy blocks the browser probe. Exact-head CI is required.

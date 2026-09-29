# Multi-user admission verification — 2026-09-29

Base: `4113cd86ea94752a4c22a6e4a0db2781f49be2aa` (main). Release PR #1143 is not modified.

## Executed checks

- 29 dependency-free Java policy checks: 10 analysis-admission, 10 provider-admission and nine retry-policy cases.
- 10 actual Spring/loopback-HTTP checks: queued cancellation, cross-user admission, reversed replies and isolated telemetry, retry RPM accounting, transient 429, permanent quota, queued expiry, HTTP concurrency, Spring startup validation and durable-caller backpressure.
- Six Node queue/monitor/rendering checks. Against original UI blob `a155f18c90e7fe9ee0609ed9434944d9cf02cc14`, four fail and two pass. All six pass after the change.
- Changed production Java and executable check drivers compile using `javac --release 21 -Xlint:all -Werror` with OpenJDK 21.0.11. Node execution used 22.16.0; canonical CI uses its declared Node 24 and remains required.

The new policy types started with missing-class compile failures; those are not claimed
as reproduced old application bugs. Runtime baseline checks do reproduce the old
premature RUNNING state, unthrottled HTTP retry and lack of transient-429 continuation.
Reversed HTTP replies remain correctly isolated both before and after the change.

## Dependency and baseline provenance

The local environment had no Maven installation/dependency cache and no GitHub/Maven
network access. Its source workspace contains only the files under inspection.
Actual dependency JARs and baseline classes came from existing CI artifact `11028475035`
rather than newly generated stubs:

- ZIP SHA-256: `a0a7451e88aef18b594dcfd55e4eec2083261e36b79a7ccfacdc0bb4488ec33d`.
- Application JAR SHA-256: `c88050ac06b4bcbfc1520d0a8228b3b95998751bea7609c1b06e49cbe62c67b9`.
- Embedded source commit: `922d6c259fcfff5d6bf6dfff2fa97edc8cf2659b`.
- Embedded tree: `c274c7e1986eb9f9d040c362d94b00f25cdf0235`.

The application JAR digest matches the downloaded artifact manifest; the ZIP digest
was calculated locally and is not an independent authenticity check. This is the
release candidate's dependency set, not a complete rebuild of this feature's source
tree. The irrelevant Oracle driver was omitted from the focused compile classpath
because its manifest references an unpackaged optional `oraclepki.jar`; production
code warnings remain errors. No build file or CI gate was weakened.

## Canonical acceptance still required

The executable Java checks are registered by `AnalysisAdmissionPolicyTest`; the
Node cases are added to the existing `test:analysis-session-startup` command.
Existing tests which saturate four reservations now configure an explicit four-place
waiting queue instead of assuming a reservation consumes an active worker slot.
Their backpressure, persistence and cancellation assertions are retained.

The full `./mvnw verify -DexcludedGroups="real-llm"` reactor, existing JUnit suites,
browser checks, security/architecture gates, database integrations and platform matrix
were **not** executed locally. They are mandatory PR gates. Focused compilation and
45 successful local checks are not evidence that the complete build is green.

No external provider was called. Loopback replies are transport/concurrency fixtures,
not a measurement of LLM semantic quality, external quotas or production capacity.

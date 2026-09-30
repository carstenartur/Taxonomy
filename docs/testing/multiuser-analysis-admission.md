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

## CI contract repair after the first PR run

The commit-bound reports for head `c390f1b9838d33ccbe92548ed6f1683e648fabb7`
show four failures in `taxonomy-analysis`: three legacy SSE admission expectations
and one Gemini usage-meter expectation. The Oracle lane independently reports the
same four failures (654 tests, four failures, zero errors). These stop the upstream
reactor before later persistence suites; they are not four separate database bugs.

- Duplicate SSE admission must preserve the original **QUEUED** run, not claim it
  is RUNNING. The test also requires no execution timestamp and no provider calls.
- Saturation is tested with an explicit four-place waiting queue, independently of
  the worker limit. A separate test distinguishes the per-owner HTTP 429 from the
  global HTTP 503 without scheduling excess work.
- Cancelling queued work finishes immediately as CANCELLED. Its slot is reused
  before the original worker executes; that late worker must not call the provider,
  damage the replacement reservation or leak thread-local state.
- Gemini metering covers retry budgets 0, 1 and 2, expecting respectively 1, 2 and 3
  physical 429 responses. It checks bounded termination, one logical invocation,
  monotonically increasing retry indices, error outcomes, unknown token counts and
  transcript/credential privacy. Retry-After: 0 avoids an artificial test delay.

No production source, admission limit, workflow, timeout, dependency, exclusion or
failure gate is changed for this repair. No tests are removed or skipped.

Verification before publication:

- Re-executed all 39 existing Java policy/real-loopback checks against the exact
  candidate artifact: all pass, compiled with Java 21 and `-Xlint:all -Werror`.
- Executed six supplementary probes against that same unchanged artifact: global
  and owner rejection, immediate queued cancellation/late-worker cleanup, and
  Gemini 429 accounting for all three retry budgets: all pass. These probes are
  not represented as execution of the edited MockMvc/JUnit classes.
- Executed `node --test .github/scripts/analysis-queue.test.mjs
  .github/scripts/analysis-live-progress.test.mjs`: 52 pass, zero skips/failures.
- `./mvnw verify -DexcludedGroups="real-llm"` was attempted locally but stopped
  before compilation because the Maven distribution download was unavailable.
  The complete edited JUnit suites and full reactor therefore remain CI gates.

Evidence was downloaded through the GitHub connector and independently hashed:

| Evidence | Artifact ID | ZIP SHA-256 |
| --- | --- | --- |
| Core reports | 11033758344 | `799ea5e4e46dcb9aebb9097511851e5e70186d91eea16663a782127d0fd4c9b6` |
| Source and usage reports | 11034350510 | `21918d1fa3e363c1d65eb6c321b0029de6704458ab5744d2ddca18296c2b300a` |
| Candidate application | 11033871887 | `eb91dbd6c2c9c11d2fa987401a229d7be17b927e9c95e2cffbe48fcb5cd869c1` |

The candidate JAR has SHA-256
`58079d0cd53854db4dac1bba7fb44e90b940d4939069645346c018633a841f00`,
matching its manifest; the embedded source tree is
`76cc8b3c2cac28a9714965c55bc9789e4f3a0514`. All local network test requests
were confined to loopback; no external LLM or paid quota was used.

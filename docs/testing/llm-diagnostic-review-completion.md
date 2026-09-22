# LLM diagnostic and execution review follow-up

## Prepared prompts on provider failures

The shared final-prompt gateway records preparation before budget validation or
HTTP transport. Recording is keyed by the currently executing analysis call, not
by a global last prompt. Nested calls restore the preceding identity, and leaving
a call clears its association before later unobserved requests can run.

The existing scoped progress registry stores only its bounded preview and original
length. A budget rejection or escaping provider exception therefore retains the
prepared prompt even though no response exists. Preparation is not proof of
network delivery. API keys are not supplied to this observation callback, and
exception messages are not added to its diagnostic failure payload. This is not
a claim that all pre-existing application logging has been redesigned.

Cancellation still takes precedence over a simultaneous provider failure. A
completed provider reply still supplies the final evidence. Existing owner,
workspace, branch and repository access checks, 32-call retention, 8192-character
prompt/reply bounds and ten-minute terminal retention are unchanged. The live
buffer is not a durable, complete transcript.

`LlmPreparedPromptTest` exercises the real registry, run control and final-prompt
boundary. Only the external provider and budget decision are fixtures. It covers
budget/transport failures, cancellation, bounds, scoping, nested calls, absent
responses, normal completion and the actual service's constructed prompt.

## Browser departure observation

The browser-session acceptance test allows the one expected validation when a
hidden editor is revealed. That allowance now ends when the response body has
finished, before navigation away starts. Requests emitted during departure or
while awaiting the hidden-editor checkpoint count as unexpected again.

`browser-validation-departure.test.mjs` executes that section of the actual
acceptance source with a controlled page boundary. It is included in the existing
`test:dsl-validation` command and hence the normal UI contract chain. These Node
checks verify the observer logic; actual Playwright acceptance remains a separate
required check. No console filter, hidden-request assertion or timeout is relaxed.

## Committed claims rejected by local admission

A shutdown can retire a local delivery after its database claim transaction commits
but before the claim is attached to that delivery. The worker releases this
unadmitted claim in a short transaction using the existing scoped lock order.
The exact owner and epoch must still be valid. Release clears owner/deadline but
keeps recovery active and never resets the fencing epoch or attempt limit.

A superseding worker's lease cannot be released or finalized by the old delivery.
No proposal revision or terminal failure is published for work that never started.
Already admitted work retains the existing shutdown, usage-receipt and finalization
protocol. Process loss or unavailable cleanup still falls back to lease expiry;
release is not a promise that a failed database call can complete.

The existing fresh-application `ReformulationLocalRecoveryTest` now also exercises
retirement immediately after a real committed claim and a separate superseding-
owner case. Both use real transactional persistence. The established capacity,
shutdown and finalization-order checks are retained.

## Civilian acceptance and real heap isolation

The SQL Server verification lane at head `ca27cebb` failed in the ordinary civilian
application test before database-specific integration tests. The recorded reason
was `MEMORY_PRESSURE` in the second analysis pass, not an SQL assertion. The
application correctly kept the first completed pass and reported a partial result.
A successful standalone scenario is not proof of the whole database matrix.

The complete civilian scenario now runs in a fresh JVM instead of sharing the
accumulated heap of earlier reactor test classes. Its normal JUnit entry point
requires a successful child exit and completion marker. The child executes Spring's
real test lifecycle, imports, bean overrides and cleanup. Its maximum heap cannot
exceed the parent's ceiling; explicit test properties, JaCoCo and the existing
browser opt-in are preserved.

The scenario body is unchanged: real HTTP/authentication, two verification passes,
persistence, graph quality, exports, reopening, frozen-source mutation checks and
optional browser inspection still execute. The LLM response boundary remains the
fixture. The memory sensor and admission policy are not mocked or disabled, and
`PARTIAL` is not accepted in place of the required `SUCCESS`. Existing memory-
pressure and cooperative-stop tests continue to test resource rejection separately.

## Evidence boundaries

Exact commits, red/green runs and current full-build status are recorded in PR
#1108. The isolated verification workflow is not part of the product change.
Focused Maven/JUnit and Node execution is not a substitute for the final commit's
complete CI, database matrix, security review and actual browser acceptance.
The user's original live model response has not been recovered, so its particular
reason for returning prose is still unverified.

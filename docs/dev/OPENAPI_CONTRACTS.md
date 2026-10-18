# REST/OpenAPI documentation contracts

The controller annotations own operation semantics. Springdoc publishes their
runtime representation at `/v3/api-docs`; merely returning HTTP 200 from that
endpoint does not establish documentation completeness.

## Required checks

Run from the repository root with Java 21:

```sh
./mvnw -B -pl taxonomy-app -am test \
  -Dtest=RestApiDocumentationTest,OpenApiGeneratedContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

`RestApiDocumentationTest` discovers production controllers across the complete
application classpath, including conditional Artemis, backup and local-account
adapters. It covers `@RestController`, `@Controller` methods with `@ResponseBody`
or an HTTP entity return type, and explicit servlet-streaming responses. MVC page
names and exception handlers are not REST operations. Explicit `@Hidden` or
`@Operation(hidden=true)` exclusions remain intentional; there is no debt allowlist.
Every discovered REST handler must have a nonblank operation summary and description.
Method discovery includes non-public MVC handlers. Dedicated fixture profiles
prevent the test configuration from registering in unrelated application contexts;
a separate regression checks both isolation and explicit activation.

`OpenApiGeneratedContractTest` starts the real Spring MVC, embedded servlet server
and Springdoc generator with production controllers and inert service dependencies.
It calls `/v3/api-docs`, checks operation/parameter documentation and compares the
generated paths with the actual registered MVC mappings in both directions,
including HEAD/OPTIONS/TRACE when mapped. It rejects unresolved local schema
references, duplicate operation identifiers and undocumented responses. Seven
mutations of an actual generated document verify that these guards and the account
field/response rules reject the specific broken contracts.
Its focused contracts include:

- Artemis dispatch inspection and explicit bounded repair.
- Continuation reads/cancellation without accidental resumption.
- Raw DOTX uploads, binary downloads, ETags and actual template-error bodies.
- Required quoted proposal revisions, adoption acknowledgements, rationale and
  the distinct 400/409/412/428 outcomes.
- Diagram-export request fields, provider-work semantics and generation failures.
- Durable analysis SSE, original-input and result recovery.
- Mixed MVC/REST editor, integration and backup adapters, including the strict
  backup wire request, streamed archive and semantic editor preconditions.
- Local password-change fields and non-echoed password formats, success and all
  five domain rejection codes, authentication/CSRF responses and actual feature
  conditions. The real account controller is imported, rather than constructed
  through a bean that would bypass its profile/property conditions.

The fixture checks ten production controllers. It starts three isolated contexts:
normal local-account support, password change disabled, and Keycloak. The last two
must not advertise the local password endpoint. Each HTTP client and context is
closed. Domain password errors have their own schema; framework binding failures
can use the separately allowed generic error object. The existing service counts
password length in Java UTF-16 code units, so a JSON Schema `minLength: 12` would
incorrectly reject accepted supplementary-character input. This cross-field/length
rule is described without changing password validation or open-map wire bindings.

The classpath-wide inventory checks basic descriptions, not every response body or
parameter of every endpoint. Do not equate a passing inventory with a complete
semantic schema audit of the entire API.

These checks do not execute an LLM, use production credentials, contact a broker
or require Docker. They do not replace authentication, authorization, business
behavior, external-database, browser or broker integration tests. The shared
`*Cases` classes also expose direct Java entry points for restricted diagnostic
environments; that execution is not a Maven/JUnit or full-reactor result.

## Changes must preserve wire behavior

Document `If-Match` as required in OpenAPI where the domain requires it, even
when Spring's `@RequestHeader(required=false)` is deliberately used to emit 428
rather than Spring's default missing-header response. Describe existing errors,
not hypothetical statuses. Prefer annotations on the actual request DTO over a
second schema-only copy of the same DTO. Open-ended legacy export maps retain
their existing Java/JSON binding while their required fields are documented.

A description must explain scope, side effects, preconditions and asynchronous
acceptance where relevant. A method name expanded into words is not enough.
Schema/content assertions should accompany any new nontrivial API contract.

The canonical verification remains:

```sh
./mvnw -B verify -Pci -DrunOnnxTests=true
```

## Scope and current status

The original REST-documentation change (#1188) did not implement distributed
continuation, call diagnostics or shared rate quotas. Those features subsequently
landed through #1201 and must not be reported as entirely absent based on that
historical scope. Local execution remains supported deliberately.

See [JMS/API acceptance boundaries](JMS_API_ACCEPTANCE.md) for the remaining
cross-pod, provider-loss and capacity evidence. Documentation and contract-test
changes do not establish new broker-HA or production-capacity guarantees, nor do
they make the existing concurrency permits a universal hard token-rate limiter.

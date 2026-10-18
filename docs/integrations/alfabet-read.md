# Alfabet: read-only adapter preparation

## Delivered boundary

`taxonomy-interop` now contains an explicitly invoked Alfabet report reader:
`com.taxonomy.interop.alfabet.AlfabetReadClient`. It returns a bounded external
observation using the existing `IntegrationContracts.ExternalScope` and error
contract. It reuses the connection-time network policy of the OSLC transport.

This is a **library preparation**, not an activated application integration.
There is no new controller, UI button, Spring auto-registration, scheduled job,
database table, canonical model operation or remote publication capability.
The adapter is intentionally not advertised in `ExchangeConnectorRegistry` as
an architecture import: an Alfabet inventory object is not automatically a
Taxonomy element, taxonomy entry, requirement or confirmed reusable component.
The existing reviewed identity/portfolio machinery remains the intended next
consumer. Do not introduce a second authoring or synchronization store.

## Wire contract and compatibility

The implemented contract is the documented API-v2 **report read**:

- Optional `POST <application-base>/api/token`, form-encoded password or client
  credentials grant. Alternatively resolve an already issued bearer token.
- `POST <application-base>/api/v2/objects` with a configured `Report`,
  `ReportResult: "DataSet"`, `CurrentProfile`, `EmptyValues: true`, `Limit` and
  `Offset`. POST here reads a report; it is not a write permission.
- The response must identify the requested `Name` and contain an `Objects`
  array of `ClassName`, `RefStr` and string-valued `Values` report columns.
  Null columns remain distinct from absent and empty-string columns.
- A relation report can repeat the base object's `RefStr`. All distinct rows
  are retained. That base reference is **not** relabelled as a relationship ID.
  Configure evidence fields `sourceRef` and `targetRef` for relation reports.

Only these two fixed endpoints can be reached. There is no method accepting an
arbitrary target URL, HTTP verb, query, SQL expression or write command. Update,
delete, password-reset and anonymization endpoints are absent. Redirects,
automatic retries, cookies and compressed responses are not accepted.

References (manufacturer documentation, consulted 18 October 2026):

- [API-v2 report export](https://documentation.alfabet.com/11-7/en/AFLREST_AFL_RESTService_DataExport.html)
- [Report export, 11-9 documentation](https://documentation.alfabet.com/11-9/en/AFLREST_AFL_RESTService_DataExport.html)
- [Authentication](https://documentation.alfabet.com/en/AFLREST_RESTService_Authentication.html)
- [Alfabet 11 API-server setup](https://documentation.alfabet.com/11-10/en/Instal_SetUpAPIServer.html)

The public documentation differs by release/edition; some current pages are
FastLane-specific. **Real Alfabet 10/11 execution: NOT_EXECUTED.** A valid
synthetic response is not product acceptance. No report name, column name,
permission, edition or installation-specific API availability is inferred.

## Administrator preparation

Use [the example profile](alfabet-read-profile.example.json) as a template,
not as working installation configuration. It is **not automatically loaded**
by Spring. The report names and column names must be replaced with those of
approved tabular reports in the actual installation. A Native-SQL-based report
can be the server-side source when exposed through this documented report API;
Taxonomy does not receive or execute that SQL itself. Verify the report is
read-only and deterministically ordered before enabling repeated paging.

For the later application binding, an administrator must supply the exact API
application base, stable instance identity, an approved user profile, selected
reports, their class/column mappings and server-side credential references.
Keep actual secrets out of configuration JSON, source control, browser input,
wire-debug logging and support captures. Use a dedicated account and enable
only the necessary report-read endpoint/permissions on the Alfabet side.
Whether that least-privilege arrangement is supported must be checked locally.

`BEARER` uses only `secretEnvironmentVariable`; `CLIENT_CREDENTIALS` and
`PASSWORD` also use `principalEnvironmentVariable`. Tokens are obtained for
one explicit read, not stored, logged or shared between profiles. Expiration
can shorten the total read deadline; there is no silent reauthentication or
retry. A secret resolver error produces a sanitized integration failure.

Private networks require an explicit administrator opt-in. HTTPS is the default;
`allowInsecureHttp` exists for isolated development environments, must remain
false in production, and must never be selectable by a browser caller.
DNS is checked during connection establishment, not just in a preflight lookup.

Programmatic composition (only after normal repository/organization access
checks and trusted profile selection):

```java
AlfabetReadProfile profile = mapper.readValue(approvedProfileFile, AlfabetReadProfile.class);
AlfabetSnapshot observation = new AlfabetReadClient(environment::getProperty).read(profile);
```

Do not expose this library directly as an arbitrary-profile REST proxy. A future
application endpoint must bind each profile to the authorized repository and
organization, reusing workspace access checks; then feed observations into the
existing reviewed portfolio workflow. This preparation grants no such access.

## Evidence, completeness and limits

Budgets apply across **all reports**, not once per report: pages, rows, response
bytes, total bytes and an overall deadline. Default: 250 rows/page, 40 pages,
5,000 rows, 2 MiB/response, 16 MiB total, 60 seconds. These are limits, not measured
runtime/memory requirements. They can be reduced within the validated bounds.
No full response transcript or generated QA dump is committed.

A short page terminates the selected report; `Count` is not interpreted as an
atomic total or global inventory count. Duplicate object references, repeated
identical relation rows, unexpected classes, nonempty nested objects and invalid
JSON fail explicitly. Nonempty nested data is not silently discarded.

A failure or exhausted budget returns **no successful partial snapshot**. The
caller must retain any earlier accepted observation separately and show that
refresh failed. Do not replace it with an empty inventory. Successful observations
still declare `authoritativeInventory=false` and
`REPORTS_EXHAUSTED_NOT_AN_ATOMIC_SNAPSHOT`: report exhaustion proves neither global
completeness nor a point-in-time snapshot. Missing entries never authorize deletion
or establish nonexistence. Lifecycle/status/date strings remain evidence, not a
confirmed availability or suitability verdict. Original taxonomy entries,
requirements, portfolio decisions and local architecture are untouched.

## Verification

The two new `*Test` classes are ordinary JUnit Jupiter tests in the existing
Maven-owned module suite. They use a local JDK HTTP server, no Spring application,
Docker, database, LLM or real Alfabet account. Fixtures are deliberately synthetic.

Focused iteration from a complete checkout:

```sh
./mvnw -B -pl taxonomy-interop -am test
```

Required repository verification remains unchanged:

```sh
./mvnw -B verify -Pci -DrunOnnxTests=true
```

A focused pass is not full-reactor/browser/database acceptance. Live acceptance
requires recording the exact Alfabet edition/version, report definitions and
scope; verify authentication, a multi-page object report, a relation report,
restricted access and a failed refresh before advertising product compatibility.

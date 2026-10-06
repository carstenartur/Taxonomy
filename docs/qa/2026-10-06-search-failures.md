# Search failure, index readiness and repository isolation QA — 2026-10-06

Base: `74b3d55082ddbb5b38c0c7309349e371bdc016ab` (`main`, including the
externally merged draft-reload PR #1176). This follow-up preserves those changes.

## Findings and corrections

Full-text, semantic and similar-node services previously converted backend errors
into empty results. Graph inference failure became an empty graph. Their HTTP
callers returned 200, conflating an outage with a completed query that found no
matches. Search execution failures now propagate a sanitized typed exception and
become localized HTTP 503 responses. Model unavailability and native linkage
failures cannot fabricate successful results, and interrupted inference preserves
cancellation. Empty completed searches, missing similar-node sources and direct
nonpositive result limits retain their empty-result behavior. Hybrid search keeps
its documented full-text fallback when embeddings are unavailable before a query.
This includes a model already known to have failed before the new request; the
request executes full-text search only. A failure observed during a search attempt
still fails that request. Both unavailable-before-query cases are covered explicitly.

The graph controller previously replaced denied, failed or missing workspace
resolution with `WorkspaceContext.SHARED`. It now preserves access denial as 403
and stops retrieval on resolution failure. Explicitly resolved shared contexts
remain supported. The graph relation query also lacked a repository predicate.
Real Lucene fixtures demonstrated statistics from a foreign repository, including
for a repository with no relations. Model identity, repository identity and
workspace visibility now filter the KNN candidate set together. Closer foreign
vectors therefore cannot consume the allowed candidate budget. Catalogue nodes
remain global; relation statistics belong to the selected repository/workspace.

A successful mass-indexer run did not prove that every document had an embedding:
the bridge can skip a vector after inference fails. Startup now compares database
counts with indexed documents tagged with the active model identity after each
node/relation rebuild. Missing node vectors prevent semantic readiness; missing
relation vectors prevent graph readiness while leaving complete node search usable.
An empty relation catalogue is valid. Vectors from another model do not satisfy
the coverage check.

Automatic indexing after startup could also silently remove vectors while status
remained READY. Observed node/relation bridge failures now latch a process-local
health failure. This includes native linkage failures and interruptions. Healthy
updates preserve readiness. The latch is deliberately not reset during a rebuild:
a failed transaction that began earlier may still finish its index write later.
Recovery requires resolving the cause and restarting to perform a complete rebuild.
Request-level readiness is checked again after retrieval so a failure observed
during a request cannot become a completed response. Nonpositive limits and the
known-unavailable hybrid fallback retain their existing behavior.

Hybrid success logging no longer records the business query. Search failures,
similar-node misses, lazy model loading and index initialization use stable codes
and safe stage/count information rather than query text, node input, private model
paths, backend messages or nested throwables. Search error responses are localized
in English and German.

An additional GitHub review reproduced a diagnostic race during a healthy state
transition. Failure details now require an observed health failure rather than
inferring one from two separate reads of the initialization state.

The status API exposes `graphReady` separately from node readiness. The browser
uses that field for graph mode; failed status requests clear both stale options
and the availability badge. The existing status-refresh entry point is retained;
this change does not add background polling. Native graph REST acceptance waits
for relation readiness within the existing timeout rather than starting after
only the node index is ready.

## Regression evidence

The [machine-readable record](evidence/2026-10-06/search-failures.json) records the
final focused commands, test counts and source digests. The final local Java run
passed all 376 selected tests, including the mandatory architecture and build-policy
checks; the Maven-owned UI contract phase passed 851 tests,
including five new search-mode regressions. Both completed with zero failures,
errors or skipped tests. These focused results do not replace canonical CI.

Before correction:

- The first service/workspace/privacy run had 20 tests: 14 failed, six passed,
  with no test errors or skips. The five real-service HTTP fixtures all failed
  because expected 403/503 responses were 200.
- Real HSQLDB/Lucene startup coverage fixtures reproduced READY with missing node
  or relation vectors. The tightened eight-case isolation suite had three
  failures: foreign repository statistics, workspace candidate displacement and
  an empty repository receiving foreign statistics.
- Real ORM updates after startup reproduced READY after both node and relation
  vector failures. Native linkage failure escaped the bridge, and an interrupted
  write lost its interrupt flag. Preserving cancellation correctly makes Hibernate
  discard that index submission; the test verifies the cancellation, retained flag,
  previous vector and revoked readiness instead of assuming a successful write.
- Four facade fixtures returned completed responses after readiness was invalidated
  during retrieval. Five browser-module fixtures reproduced graph availability and
  stale badge errors. Both sets were run through Maven before their fixes.

The fixtures use authored entities and controlled local inference boundaries;
they are not measurements of model quality on the real catalogue. Java service,
MVC, real Lucene/HSQLDB and browser-module checks supplement the repository's
required canonical, browser, native ONNX, security and database workflows.
No timeout, test-selection gate, coverage baseline or security threshold was relaxed.

## CI download repair

The first canonical run, [37487553062](https://github.com/carstenartur/Taxonomy/actions/runs/37487553062),
passed all six UI shards but failed its Archi product round trip before execution:
the release-download URL used tag `5.10_0` and returned 404. The
[official download page](https://www.archimatetool.com/download/) and
[release metadata](https://api.github.com/repos/archimatetool/archi.io/releases/tags/5.10)
identify tag `5.10`. The URL is corrected without changing the product version or
checksum. A fresh 182,262,774-byte download matches both the publisher checksum and
the existing pin:
`f9422455a00a22f5340dc28692ceafe0ad720c8cde839eaafb0fab1cea57287f`.
The real product round trip remains a required CI gate.

## Shared failure contract

The architecture ratchet in [run 37490847461](https://github.com/carstenartur/Taxonomy/actions/runs/37490847461)
detected one new dependency from the shared HTTP exception handler to the knowledge
implementation package. The 23-case architecture suite had one failing assertion;
the selected reformulation persistence cases passed. `SearchUnavailableException`
is a pure contract used by both providers and callers, so it now belongs to
`taxonomy-domain`. The separate physical-module guard in
[run 37494513835](https://github.com/carstenartur/Taxonomy/actions/runs/37494513835)
then correctly rejected retaining the `com.taxonomy.search` package, which belongs
to the knowledge implementation. The shared exception now uses
`com.taxonomy.error.SearchUnavailableException`, and the framework-free domain rule
also covers that package. Its safe error semantics are preserved. This removes the
implementation dependency without increasing the reviewed dependency baseline,
changing context ownership or exempting the handler from architecture checks.
The final focused command includes `taxonomy-build` so its mandatory physical
ownership, graph and selector checks execute with the current reactor outputs.

## Database fixture connection lifetime

In [database run 37496495201](https://github.com/carstenartur/Taxonomy/actions/runs/37496495201),
the Oracle browser cases passed, but the final diagnostics history-scope case
failed with `ORA-12516` while opening a JDBC connection inside its 55-row foreign
history setup. The listener refusal alone does not identify its resource limit.
The fixture used `DriverManagerDataSource`, which opens a physical connection on
every call. A local regression running the same history contract measured **458
physical opens** and failed the new connection-budget assertion.

The fixture now owns a Hikari pool limited to four connections. Reopened persistence
factories borrow that pool, and closing a borrowed factory leaves the original
fixture usable. Normal shutdown and failed schema validation close owned pools.
The HSQL contract covers the same pool lifecycle locally, including genuinely
concurrent completion transactions, rollback, scope filtering and persisted data
read by a second factory. The measured history workload must use at most eight
physical opens including initialization and leave no open connections afterward.
The original 55-row history case, concurrent insert barriers and timeouts remain
unchanged. Native Oracle acceptance still belongs to the required database lane.

The PostgreSQL job in that run failed before executing database tests because Maven
Central returned HTTP 500 for `org.eclipse.sisu.plexus:0.9.0.M4`. A later download
check returned HTTP 200. This infrastructure failure is not counted as a test pass.

## Verification boundaries and open investigations

The health latch observes failures in the embedding bridge. Backend indexing
failures occurring after a successful bridge write are outside that tracker;
this is not a continuous database/index consistency audit. Exceptions raised by
transaction interception before service entry retain the existing sanitized 500
response, rather than being claimed as covered 503 cases. Internal relation-proposal
and legacy scoring workflows are outside this search API correction.

The public `/actuator/info` and `/login` endpoints both returned HTTP 503 from
this environment at `2026-10-06T14:00:37Z` and `2026-10-06T14:00:45Z` respectively.
The inspected [Delivery run 37451795991](https://github.com/carstenartur/Taxonomy/actions/runs/37451795991)
belongs to `11d099b858cddbbaa15b6f0b2a69f04a56534ae2`. Docker and report publication
succeeded, `Render deployment disabled` (job `112232322265`) succeeded, and
`Deploy to Render` (job `112232324190`) was skipped. This does not identify the
application served by Render or resolve the original deployment-specific cause
in #1100. No deployment is enabled by this change.

The frozen supplemental German retrieval misses remain open. This correction does
not change model weights, ranking, catalogue content, result limits or the reference
fixtures. A successful six-anchor native gate does not establish general German
retrieval quality.

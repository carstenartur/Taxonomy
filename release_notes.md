# Taxonomy 1.5.3

Taxonomy 1.5.3 develops the path from retained requirements and analysis results
to reviewed, editable architecture. It adds scoped inventory comparison,
explicit snapshot adoption, stronger recovery and exchange evidence, and fixes
analysis, navigation and export behavior. These notes describe the source for
this release; publication is established by the published release assets and
their matching source and digest evidence.

## Requirements, decisions and architecture

A retained analysis snapshot can be reviewed and adopted into an editable
architecture working copy. The operation preserves the source requirement,
snapshot and provenance, checks the current workspace, and uses the existing
semantic command, undo and history mechanisms. Reopening the result does not
require rerunning the model. Snapshot adoption remains an explicit user action.

The requirement and architecture views now carry their project, repository and
working-copy context through the workflow. Next-step controls expose
reformulation, the focus lens and architecture editing where those actions are
available. Tree and graph selection stay synchronized, while navigation and
filter changes preserve diagram position. Printing supports A0 and A1 output.
Contextual help is available from the application views.

Rule weights and conflict scores are presented as heuristic evaluation inputs.
Missing evidence and unresolved root relevance remain visible. They do not
become exclusions, fulfillment percentages or probabilities of correctness.
The Copilot finalizes and retains its reports on the server; opening a second
browser tab no longer causes the previously observed analysis conflict or
unintended cancellation.

See [the user guide](https://github.com/carstenartur/Taxonomy/blob/v1.5.3/docs/en/USER_GUIDE.md),
[the project portfolio guide](https://github.com/carstenartur/Taxonomy/blob/v1.5.3/docs/en/PROJECT_REQUIREMENT_PORTFOLIO.md) and
[the German portfolio guide](https://github.com/carstenartur/Taxonomy/blob/v1.5.3/docs/de/PROJECT_REQUIREMENT_PORTFOLIO.md).

Reviewed workspace synchronization now transfers the original Git objects and
preserves their ancestry. Integration uses an expected-head fast-forward or a
real two-parent merge after semantic review. This completes the bounded
synchronization scope in [#747](https://github.com/carstenartur/Taxonomy/issues/747).
Initial workspace/fork provisioning still creates a content-derived seed commit;
retaining the original source commit and independent initial history remains
required in [#1356](https://github.com/carstenartur/Taxonomy/issues/1356).

The durable observation and saved-result recovery slice is now recorded as
completed in [#808](https://github.com/carstenartur/Taxonomy/issues/808). Its
persisted full-snapshot checkpoint supports reconnect and explicit result
retrieval; the second-tab correction belongs to this release. Remaining adapter
identity, stored-event/status retention and expired-recovery work is tracked in
[#1354](https://github.com/carstenartur/Taxonomy/issues/1354). This closure does not
claim that every lifecycle adapter or retention case is complete.

## Scoped inventory evidence

Saved requirement analyses can be compared with curated product evidence and
inventory observations from operator-approved source profiles. Findings keep
their source,
identity mapping, context, time and evidence. Availability and capabilities
remain distinct, and an absence of evidence is not a positive or negative
inventory assertion. Retained comparisons and deliberate review decisions
remain available after reopening the requirement.

Relationship requirements distinguish directed interfaces, dependencies,
alternatives and hard exclusions. Evidence is matched by endpoint identities,
relationship meaning, contract or version qualifier, context and time. Expired or
incomplete evidence remains an
open question. Existing project solutions serve as domain variants: comparison
retains their prerequisites, risks, cost assumptions, evidence and open points.
Selection is deliberate and reasoned; a hard exclusion prevents selection.
These decisions use the existing portfolio, history and review mechanisms.
Coverage remains an explicit reviewed assessment; the comparison does not rank
variants or declare a requirement fulfilled.

Alfabet is available as an optional inventory-source plugin alongside the
existing supported adapters. Access depends on an explicitly configured and
authorized source profile; an imported or restored observation does not itself
activate permission to contact that source.

See [inventory comparison](https://github.com/carstenartur/Taxonomy/blob/v1.5.3/docs/features/inventory-comparison.md) for the
supported evidence and review contracts.

## Recovery and exchange

Native cold installation recovery captures a stopped, file-backed HSQL
installation and restores it to a distinct data root for the same application
version. The application rehearsal checks retained data, Git-backed history,
reopening and semantic undo/redo. A cold backup requires all writers to stop;
copying a live database directory is not an equivalent operation.

The same-version native recovery tooling also supports PostgreSQL 16 with a
custom-format logical dump and the stopped application's filesystem content.
It requires a separately created empty target database and an absent target data
root, checks their physical identities, and applies database content and its
restore receipt in one transaction before publishing the files. Native recovery
preserves stored application identities and grants; it requires operator control
of external access and pending work before the restored application starts.
The generic portable cross-database/owner restore registry remains closed where
the required importers are incomplete. See
[PostgreSQL recovery](https://github.com/carstenartur/Taxonomy/blob/v1.5.3/docs/testing/native-postgresql-recovery.md).

The installed-product acceptance lane now passes the actual architecture
workbench ArchiMate export to Archi and checks the returned model, separately
from the integration exchange profile. Evidence identifies the product,
profile, file hashes and accepted transformations. Visio exports now open on
native editable drawings, preserve their element and relationship identities,
and place supporting text on appendices. Requirement context is exported
through the bounded supported fields.

An explicit Archi 5.10 return workflow accepts the strict original export and a
content-preserving returned XML file. It checks identities, values, provenance,
geometry and view order, lists every supported representation loss, and requires
review before applying relations between existing catalogue elements. The strict
ordinary reader
is unchanged. Semantic edits in Archi, restoration of a whole architecture
snapshot, and transfer of review authority are outside this return profile.

See [native installation recovery](https://github.com/carstenartur/Taxonomy/blob/v1.5.3/docs/testing/native-installation-recovery.md)
and [scenario acceptance](https://github.com/carstenartur/Taxonomy/blob/v1.5.3/docs/testing/scenario-acceptance.md) for the tested
operations and exact compatibility boundaries. The
[Archi return guide](https://github.com/carstenartur/Taxonomy/blob/v1.5.3/docs/features/standards-interoperability.md#bounded-archi-return-to-catalogue-relations)
describes the reviewed import, while the
[exchange-profile guide](https://github.com/carstenartur/Taxonomy/blob/v1.5.3/docs/dev/ARCHIMATE_EXCHANGE_PROFILE.md#consumer-evidence-and-remaining-acceptance)
records installed-consumer evidence and its limits.

## Model evaluation and verification

The existing browser suite now verifies anonymous German and English local
sign-in: accessible labels, visible keyboard focus, the skip link, generic
failure feedback, saved-request login and logout with the selected locale
retained. A separate application context exercises the same forms without
JavaScript under `/taxonomy`; both anonymous pages also enter the existing
accessibility audit. This completes the focused verification follow-up
[#1355](https://github.com/carstenartur/Taxonomy/issues/1355).

Model-comparison evidence and manual review are bound to a validated run and its
original and reformulated requirements, source revision, provider/model
identity, output hashes and actual execution results. Mismatched or incomplete
evidence is rejected. Authored scenario responses remain deterministic
functional test inputs, not live-model quality evidence.

Full live-provider quality assessment still requires an actual configured
provider and the specified human review. Live LeanIX/Alfabet installation
acceptance and licensed Microsoft Visio acceptance likewise require their
respective environments. Installed Archi checks, independent document rendering
and local XML/package checks establish their documented scopes only.

See [reformulation verification](https://github.com/carstenartur/Taxonomy/blob/v1.5.3/docs/testing/requirement-reformulation.md).

## Runtime and operation

The build and application runtime now use **Java 25**. Source deployments must
update their JDK; packaged images and their runtime metadata use the same
version. Local ONNX search initialization works with generative LLM provider
configurations. Deployment documentation includes the Artemis Kubernetes setup.

PostgreSQL remains the external production database profile with its managed
migration contract. SQL Server and Oracle retain their documented evaluation
and compatibility scope. Database migration, native recovery and portable
cross-database restoration are separate operations; changing a Spring profile
does not migrate stored data.

## Build verification

CI distributes 10 measured runtime suites and 45 domain-integration suites into
explicit Maven groups while retaining the complete test inventory, ONNX and
PostgreSQL checks. Coverage aggregation reuses verified producer bytecode and
probes without recompilation or repeated ordinary tests, then executes its
coverage policy freshly. Two service fixtures avoid unused embedded servers;
real persistence, security, provider HTTP and process isolation remain.

Ready PRs containing only allowlisted internal/developer prose can verify the
actual packaged documentation with original JUnit and source/digest evidence.
That result is explicitly scoped and carries no full-product coverage claim.
Draft PRs avoid expensive PR verification and cannot pass the required gate;
ready-for-review resumes verification. Independent CodeQL push analysis remains
an explicit exception because push events lack draft context.

Dependency keys now include standalone Maven descriptors, application cache
archives are written only by successful daily main pushes, and image builds can
reuse persistent Docker layers. The early application package avoids test
compilation and duplicate aggregate SBOM work while retaining its fresh runtime
SBOM. Full verification remains required for main, tags, manual runs and mixed
or unrecognized changes. These changes still need a complete fresh CI timing;
see [CI performance](docs/dev/CI_PERFORMANCE.md) for the measured baseline and
limits of the local evidence.

## Issue completion and remaining work

The 31 October backlog review separates implemented delivery from unfinished
acceptance and optional expansion. Closing an old issue now does not make its
implementation new in 1.5.3. The following earlier deliveries are recorded for
traceability alongside the new synchronization and second-tab work above.

| Completed issue | Earlier delivered scope | Remaining work |
|---|---|---|
| [#630](https://github.com/carstenartur/Taxonomy/issues/630) | Bounded search-result windows, orientation controls and measured Chromium result budgets; merged #819 in August | Additional real-corpus/browser evidence and graph-readability verification: [#1353](https://github.com/carstenartur/Taxonomy/issues/1353) |
| [#638](https://github.com/carstenartur/Taxonomy/issues/638) | Constrained Kubernetes/Artemis recovery foundation; merged #815/#1201/#1273 | Selected workload/resource, provider-HTTP/TLS failure and deployment evidence: [#1351](https://github.com/carstenartur/Taxonomy/issues/1351) |
| [#793](https://github.com/carstenartur/Taxonomy/issues/793) | Branch-scoped hypotheses/evidence and data-retaining migrations; merged #1296 before v1.5.2 | Broader indexing, recovery and identity keep their existing owners |
| [#836](https://github.com/carstenartur/Taxonomy/issues/836) | Revision-pinned browser editing and immutable template comparison; merged #1012/#1022 in September | Rich layouts and staged candidate preflight: [#832](https://github.com/carstenartur/Taxonomy/issues/832) |
| [#1179](https://github.com/carstenartur/Taxonomy/issues/1179) | German/English server-rendered local login with framework security; merged #1244 on 17 October | Focused bilingual anonymous-login browser acceptance: [#1355](https://github.com/carstenartur/Taxonomy/issues/1355) |
| [#1184](https://github.com/carstenartur/Taxonomy/issues/1184) | Guided replay demo inside the application, explicit review/adoption and labelled Markdown report; merged #1203/#1269 | Transport-wide isolation, offered exports at both iterations and remaining integrated acceptance: [#1352](https://github.com/carstenartur/Taxonomy/issues/1352) |

The product-gap analysis and frozen-report foundation is implemented, while
project-wide aggregation and reviewer triage remain in
[#914](https://github.com/carstenartur/Taxonomy/issues/914). The original report
of a cleared workspace after saving Preferences remains unresolved. Its required
deployment evidence and reproduction input are tracked in the
[external-input TODO](https://github.com/carstenartur/Taxonomy/blob/v1.5.3/docs/internal/TODO-EXTERNAL-INPUT.md#ext-01),
linked from [#1100](https://github.com/carstenartur/Taxonomy/issues/1100). This
administrative transfer and the related race fixes do not establish the original
cause or constitute a completed repair.

Rich Word authoring, broad template capacity measurements and the in-app
Information Product classification queue are explicitly lower-priority
extensions. Existing validation, authorization, source identity and data
preservation remain required. The open follow-ups are not completed release
features, and this issue review is not a new runtime or capacity certification.

## Release evidence

Use only the assets of the published v1.5.3 release together with their source,
checksums and image digests. The normal release workflow verifies the reviewed
source, builds the immutable artifacts, checks the final development snapshot
and applies the existing security and delivery gates before publication.
Merged pull requests and selected local test results are not a substitute for
that final release evidence.

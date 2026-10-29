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

## Release evidence

Use only the assets of the published v1.5.3 release together with their source,
checksums and image digests. The normal release workflow verifies the reviewed
source, builds the immutable artifacts, checks the final development snapshot
and applies the existing security and delivery gates before publication.
Merged pull requests and selected local test results are not a substitute for
that final release evidence.

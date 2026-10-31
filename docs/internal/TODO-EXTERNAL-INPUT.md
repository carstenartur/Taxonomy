# TODO: External input and product acceptance

Reviewed **31 October 2026** against
[main b8a0745f](https://github.com/carstenartur/Taxonomy/tree/b8a0745f4d1c0ff0b80aac27eab3482f9d9b2fb3)
and all 19 then-open issues. This is the working list for missing external
evidence, access and human acceptance. Open GitHub issues retain implementable
software changes and reproducible tests that can be assigned to a contributor
or coding assistant without that input.

**Every item below is pending. Moving work here does not complete it, resolve a
reported defect or establish product compatibility.** An administrative issue
closure is not a completed release feature. The historical evidence and the
exact technical acceptance procedures linked below remain authoritative.

## How to use this list

- Supply the named input or arrange authorized access for the selected item.
  Keep credentials, customer identities and private source data out of this
  public document; retain shareable, non-secret evidence and its provenance.
- Once the prerequisite is available, a coding assistant can run the existing
  automated procedure, analyze evidence and implement a reproduced defect.
  Human approval or domain judgment must still come from the named role.
- Reopen the originating issue, or create a bounded implementation issue, when
  evidence identifies concrete code work. Link both records. Mark an external
  TODO complete only when its stated evidence and disposition are recorded.
- Product/deployment acceptance applies to the exact version and combination
  claimed. Optional products and broader certification matrices are not new
  blanket release gates. Existing security and data-integrity requirements
  remain mandatory for enabled behavior.

Long test duration, multiple application instances, controlled HTTP/TLS servers,
public browser engines and repository-owned scale fixtures are implementation
work. They do not require customer systems merely because they are substantial.

## Pending input at a glance

| Item | Input or acceptance needed | Effect on implementation |
|---|---|---|
| [EXT-01](#ext-01) | Original Preferences state-loss deployment and reproduction | A concrete new fix depends on identifying the failure |
| [EXT-02](#ext-02) | Authoritative legacy-owner identities and migration approval | Migration tooling in #785 can proceed now |
| [EXT-03](#ext-03) | Actual Microsoft Word and selected HTTPS/WebDAV/client environment | #828/#832 and automated export checks can proceed now |
| [EXT-04](#ext-04) | Licensed Microsoft Visio desktop acceptance | Exporter and independent format checks can proceed now |
| [EXT-05](#ext-05) | Approved live model execution and independent human quality review | Deterministic application and evaluation-tooling work can proceed now |
| [EXT-06](#ext-06) | Authorized LeanIX workspace and actual schema/data | Synthetic HTTP and packaged-plugin tests remain implementable |
| [EXT-07](#ext-07) | Authorized Alfabet installation and report contract | Synthetic HTTP and packaged-plugin tests remain implementable |
| [EXT-08](#ext-08) | Actual Sparx EA/PCS environment and optional proprietary SDK | Shared protocol and fail-closed adapter work remain implementable |
| [EXT-09](#ext-09) | Authorized Information Product classification decisions | The review queue in #920 can proceed now |

EXT-01 and the external parts of EXT-02/EXT-03 are transferred from current
issue scope. EXT-04 through EXT-09 consolidate already documented external
acceptance boundaries; they are not newly discovered implementation defects.

<a id="ext-01"></a>
## EXT-01: Original Preferences state-loss reproduction

- [ ] Establish a reproducible failure or record an explicit evidence-based
  disposition for [#1100](https://github.com/carstenartur/Taxonomy/issues/1100).

**Who supplies it:** The original reporter and operator of the affected
deployment; a maintainer records the final disposition.

**Needed:** The served application/build/source and frontend asset identities,
browser/version, and the complete sequence: completed architecture → Preferences
→ node limit 50 to 150 → Save → empty working UI and restore offer. Include the
relevant workspace, tabs, navigation, reload, restore, autosave and conflict
ordering. Provide a shareable minimal fixture, sanitized before/after and
console/network evidence, or authorized observations of the affected instance.
If the original instance is unavailable, distinguish a reproduction on an
identified current version from an unknown historical cause.

**After input:** Reuse the existing browser owner to expose the actual clearing
or reinitialization event with a failing regression, then fix that cause. Preserve
requirement text, raw/effective scores, reasons, relation hypotheses, architecture
and manual decisions after successful/failed saves and reload. Preferences must
not launch an LLM run, force restoration, silently select old/empty data or prune
source evidence when a view limit shrinks. Keep workspace isolation, optimistic
concurrency and stale-analysis safeguards.

**Completion:** A reproduced defect has its own verified implementation outcome,
or the reporter/maintainer records a justified disposition on an identified
version. Existing green tests and the separate #1174/#1175/#1176/#1326 corrections
do not identify this original cause. #1100 is transferred administratively with
the reason `not_planned`, not `completed`; the report remains unresolved here.

Sources: [Preferences investigation](../qa/2026-10-06-preferences-state.md),
[historical QA](../qa/2026-10-06-remaining-qa.md),
[existing browser workflow](../../.github/scripts/ui-primary-preferences-workflow.mjs)
and [unit regressions](../../taxonomy-app/src/test/js/preferences-analysis-workspace.cjs).

<a id="ext-02"></a>
## EXT-02: Verified identities for an existing OIDC installation

- [ ] Supply verified legacy-owner mappings and approve the selected
  installation's migration before switching it from LEGACY to STABLE.

**Who supplies it:** The installation owner/operator, authoritative OIDC/Keycloak
administrator and authorized ownership reviewer.

**Needed:** A reviewed mapping of each relevant existing owner/scope to its
authoritative issuer and immutable subject, with provenance and explicit
unresolved/conflicting dispositions. The operator supplies authorized rollout
access or executes the migration when its implementation is ready and retains
non-secret acceptance evidence.

**Implementation remains in [#785](https://github.com/carstenartur/Taxonomy/issues/785):**
Migration algorithms, bounded unresolved reports, audited linking/unlinking,
stable authority propagation and revocation can be implemented and tested with
controlled identity fixtures now. Do not infer identity from usernames, email or
display names. Existing installations stay LEGACY until the verified migration
is complete; unresolved ownership remains disabled/quarantined.

**Completion:** The authoritative mapping is approved, ambiguous ownership has a
safe recorded disposition, and the selected migration has been accepted. Passing
fixture tests does not assert that a real installation has been migrated.

Sources: [identity and restore contract](../architecture/backup-format-v1.md),
[configuration reference](../en/CONFIGURATION_REFERENCE.md) and #785.

<a id="ext-03"></a>
## EXT-03: Microsoft Word and real client/deployment acceptance

- [ ] Execute the claimed Word/OS/client combinations on their actual licensed
  installation, including the selected HTTPS/WebDAV/proxy path when advertised.

**Who supplies it:** A tester with the selected Microsoft Word/OS installation
and its deployment operator with authorized access to the relevant topology.

**Needed:** Exact candidate commit, document/template hashes, Word edition/build,
OS and non-secret authentication/proxy/topology information. Record opening,
rendering, navigation, editing, autosave, close/reopen, conflict and reconnect
results, as applicable to the claimed workflow. Retain original and resulting
files, screenshots and observed losses. Performance claims additionally need a
named workload, method and measured result. Unexecuted combinations remain so.

**Implementation remains assignable:** Shared locks or a safe topology gate,
durable saves and two-instance recovery belong to
[#828](https://github.com/carstenartur/Taxonomy/issues/828); rich authoring and
candidate activation belong to [#832](https://github.com/carstenartur/Taxonomy/issues/832).
Controlled proxy tests, OOXML checks, independent LibreOffice rendering and the
two-iteration export work in [#1352](https://github.com/carstenartur/Taxonomy/issues/1352)
do not wait for this external acceptance. Scale fixtures stay in
[#839](https://github.com/carstenartur/Taxonomy/issues/839).

**Completion:** Reviewed results cover the exact combinations claimed. Actual
Microsoft Word acceptance remains unverified until then; a general Word/OS/proxy
certification campaign is optional, not a new release blocker.

Sources: [Word templates](../en/DOCUMENT_TEMPLATES.md),
[local editing](../TEMPLATE_LOCAL_EDITING.md) and
[Word acceptance boundary](../qa/word-and-sparx-completion-review.md).

<a id="ext-04"></a>
## EXT-04: Microsoft Visio desktop acceptance

- [ ] Run the existing [desktop acceptance procedure](../dev/VISIO_HANDOFF_PROFILE.md#microsoft-visio-desktop-acceptance--pending)
  on a licensed Windows/Visio installation for the exact candidate SHA.

**Who supplies it:** A tester with the selected Visio edition/build and Windows
version; an authorized deployment operator for a real requirement-return link.

**Needed:** Source/profile/bundle/VSDX hashes, opening without repair, all pages
and Unicode, directions and endpoint attachment, typed identities/metadata,
moving/resizing/editing and stencil/container behavior, save/close/reopen, and
preservation or explicit loss of custom JSON parts. Keep the original and edited
VSDX, version screenshot, before/after screenshots and the existing result record.
Real requirement links must retain authentication and repository scope checks.

**Completion:** The exact procedure is executed and reviewed for the selected
profile/version. Until then Visio compatibility stays experimental. Linux POI
and LibreOffice checks establish their own scope only. This is the single
external continuation of the acceptance referenced by
[#965](https://github.com/carstenartur/Taxonomy/issues/965), not a second exporter
implementation issue.

<a id="ext-05"></a>
## EXT-05: Live-model execution and human quality review

- [ ] Supply an approved generative endpoint/model and execution budget, then
  retain a complete live run and the separately recorded human assessment.

**Who supplies it:** The project/provider operator authorizes the chosen hosted
or self-hosted model and any required credentials/resources/spend. A human
reviewer supplies the independent semantic judgments.

**Needed:** Run the existing
[real-provider and human-quality procedure](../testing/requirement-reformulation.md#separate-real-provider-and-human-quality-gate)
on clean, committed source. Retain its exact corpus, five complete comparison
pairs, source/model/configuration and artifact hashes, HTTP/usage/timing evidence
and failure outcomes. The human supplies the bound review JSON with reviewer/time,
all five criteria, evidence and quotations from both exact outputs. Validate and
retain the reviewed archive through the existing Maven owner.

**After input:** A coding assistant can execute the run, validate/assemble the
evidence and investigate failures. Deterministic tests and evaluation tooling
remain implementable before a live provider is available. Generated scores or
agent-written judgments are not the required independent human assessment.

**Completion:** Execution and human review are recorded separately for that run.
NOT_RUN/NOT_REVIEWED cannot be replaced by a synthetic pass. A recorded assessment
does not automatically establish superiority or a cost improvement.

<a id="ext-06"></a>
## EXT-06: Actual LeanIX installation acceptance

- [ ] Supply an authorized LeanIX workspace and execute its documented read-only
  [installation acceptance](../../plugins/taxonomy-leanix-plugin/README.md#verification-boundary-and-installation-acceptance).

**Who supplies it:** The workspace owner and administrator responsible for its
schema, approved data scope and bound read credentials.

**Needed:** Actual schema/source types, scalar/subtype fields, revisions,
lifecycle and relation-connection paging semantics; token permissions and
workspace binding; multi-page object and relation reads, known nonmatch,
permission error and failed refresh with authorized real data. Record source,
plugin/profile/configuration versions and sanitized results.

**Completion:** The selected workspace/profile has reviewed actual execution
evidence. Synthetic HTTPS and packaged-plugin tests remain separate;
NOT_EXECUTED stays until actual product acceptance exists. A coding assistant can
run the read checks after authorized access is supplied and fix evidenced gaps.

<a id="ext-07"></a>
## EXT-07: Actual Alfabet installation acceptance

- [ ] Supply an authorized Alfabet installation/report profile and execute the
  [plugin acceptance checklist](../../plugins/taxonomy-alfabet-plugin/README.md).

**Who supplies it:** The Alfabet installation owner and report/API administrator.

**Needed:** The actual version, API-v2 report definition, approved bounded data,
field/type/null/empty semantics, stable object/relationship identities, pagination
and permission behavior, plus bound read credentials. Retain the exact source,
plugin/profile and non-secret request/result evidence for the documented success
and failure cases. Missing records must not be interpreted as deletion.

**Completion:** Reviewed real-installation results support only the selected
profile. [Real execution](../integrations/alfabet-read.md) remains NOT_EXECUTED
until then. Synthetic and packaged-host checks, generic adapters and evidence
handling can be implemented without a customer installation.

<a id="ext-08"></a>
## EXT-08: Sparx EA/PCS and optional proprietary host acceptance

- [ ] Supply the selected licensed EA/PCS environment and, only if wanted, its
  proprietary host/SDK environment for actual product acceptance.

**Who supplies it:** The EA/PCS operator, license/SDK owner and authorized tester.

**Needed:** Exact product/API/SDK versions, supported profiles, authorized test
repository and credentials, actual representations/token behavior and round-trip
evidence. Before enabling writes, establish the required conditional-update,
atomic-creation and durable idempotency/receipt guarantees in the real adapter.
Compile/run an optional SBPI plugin only against its actual chosen host/SDK.

**Completion:** Actual versioned results establish the advertised subset and
losses, with writes still disabled where the required guarantees are unproved.
A preflight read followed by an unconditional write is not atomic acceptance.
Shared protocol tests and safe generic implementation remain assignable.

Sources: [Word/Sparx completion boundary](../qa/word-and-sparx-completion-review.md),
[Sparx validation](../qa/sparx-implementation-validation.md) and
[compatibility record](../qa/sparx-compatibility.json).

<a id="ext-09"></a>
## EXT-09: Information Product domain approval

- [ ] Obtain version-bound classification decisions from an authorized catalogue
  or Information Product domain reviewer.

**Who supplies it:** The responsible domain expert/catalogue authority.

**Needed:** Original product/source identity and catalogue/mapping version;
accepted primary/secondary parents, rationale and evidence for changing an
original parent; reviewer/authority/time; explicit unresolved cases. Preserve
meaningful original subtrees. Internal navigation groups do not acquire official
catalogue status through an internal technical review.

**Implementation remains in [#920](https://github.com/carstenartur/Taxonomy/issues/920):**
Queue/filtering, authorized versioned accept/change/reject, audit history and
reviewed Git promotion can be built with fixtures now. Reviewed Git artifacts
remain usable without waiting for that UI.

**Completion:** External approval is claimed only for mappings actually reviewed
by that authority. Provisional and deterministically accepted mappings remain
distinct from external semantic approval; changing a review flag proves neither.

Sources: [overlay contract](../dev/INFORMATION_PRODUCT_OVERLAY.md),
[Information Products](../en/taxonomies/IP.md) and
[scoring/authority](../en/TAXONOMY_SCORING.md).

## Implementation issues retained after the review

This records the reviewed scope, not a promise of a small change or a new
priority order. Optional work remains optional and can be commissioned directly.

| Issue | Assignable implementation remaining |
|---|---|
| [#626](https://github.com/carstenartur/Taxonomy/issues/626) | Named API clients and context-safe transport migration |
| [#629](https://github.com/carstenartur/Taxonomy/issues/629) | Remaining Python adapter migration and exact-source evidence manifest |
| [#741](https://github.com/carstenartur/Taxonomy/issues/741) | Exact-context readiness and branch-owned search/vector indexes |
| [#742](https://github.com/carstenartur/Taxonomy/issues/742) | Leased recovery, idempotency, provenance and isolation |
| [#746](https://github.com/carstenartur/Taxonomy/issues/746) | Atomic browser context switches and real backend journeys |
| [#785](https://github.com/carstenartur/Taxonomy/issues/785) | Stable-principal migration tooling and current revocation; real-owner input is EXT-02 |
| [#786](https://github.com/carstenartur/Taxonomy/issues/786) | Enabled authentication contracts and optional model Git/PAT implementation |
| [#787](https://github.com/carstenartur/Taxonomy/issues/787) | Versioned repository policy and current authorization |
| [#828](https://github.com/carstenartur/Taxonomy/issues/828) | Shared WebDAV authority and recoverable saves; actual client acceptance is EXT-03 |
| [#832](https://github.com/carstenartur/Taxonomy/issues/832) | Rich Word layouts and exact-candidate validation/activation |
| [#839](https://github.com/carstenartur/Taxonomy/issues/839) | Controlled template/revision scale fixtures and two-service measurements |
| [#914](https://github.com/carstenartur/Taxonomy/issues/914) | Project gap aggregation and reviewer tooling, not real procurement decisions |
| [#920](https://github.com/carstenartur/Taxonomy/issues/920) | Versioned IP review queue; real domain approval is EXT-09 |
| [#1351](https://github.com/carstenartur/Taxonomy/issues/1351) | Controlled cluster HTTP/TLS/egress failures and bounded resource measurements |
| [#1352](https://github.com/carstenartur/Taxonomy/issues/1352) | Demo isolation, two-iteration exports and automated/file acceptance |
| [#1353](https://github.com/carstenartur/Taxonomy/issues/1353) | Repository/public real-corpus browser budgets and reproducible graph readability |
| [#1354](https://github.com/carstenartur/Taxonomy/issues/1354) | Durable analysis adapters, replay identity, retention and expiry |
| [#1356](https://github.com/carstenartur/Taxonomy/issues/1356) | Original-commit provisioning, Git transfer and recovery |

The nineteenth reviewed issue, #1100, has no independently identified new code
gap after its existing regressions; its unresolved deployment/reproduction
requirement and future-fix safeguards are retained in EXT-01.

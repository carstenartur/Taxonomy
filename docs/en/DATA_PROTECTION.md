# Data Protection

**Scope: operator reference reviewed against source `3ec8a986` on 3 October 2026.**
This page describes data flows and limits relevant to a deployment assessment. It
is not a privacy notice for a particular operator, a legal opinion, or a statement
that installing Taxonomy establishes GDPR compliance. The operator must assess
its actual data, purposes, configuration, recipients and retention procedures.

## Table of Contents

1. [Purpose of Data Processing](#purpose-of-data-processing)
2. [Categories of Personal Data](#categories-of-personal-data)
3. [Data Storage Locations](#data-storage-locations)
4. [Legal Basis](#legal-basis)
5. [Data Retention and Deletion](#data-retention-and-deletion)
6. [Third-Party Data Transfers](#third-party-data-transfers)
7. [Technical and Organizational Measures](#technical-and-organizational-measures)
8. [Data Subject Rights](#data-subject-rights)
9. [Data Protection Impact Assessment](#data-protection-impact-assessment)
10. [BfDI Guidelines for AI in Federal Administration](#bfdi-guidelines-for-ai-in-federal-administration)

## Purpose of Data Processing

Taxonomy supports requirement clarification, architecture analysis, review,
versioning and exchange. It also processes account and security information.
Architecture use is not a guarantee of anonymous input: requirements, imported
source documents, free-text reasons, decisions and generated outputs may contain
information about employees, customers or citizens. Minimize such information
before import or submission; the application must not be assumed to remove it
automatically. Model output also requires review.

## Categories of Personal Data

### User Account Data

Local-user mode stores account identifiers, password hashes, roles and account
state, with optional profile information. Keycloak/OIDC has a different identity
provider boundary; document that provider and its configuration separately.
Account metadata returned by an administration endpoint is not a complete inventory
of information about that person elsewhere in the application.

### Audit Log Data

Unlike authentication/WebDAV brute-force detection, the incoming LLM quota does not use IP addresses. It counts admitted requests per stable authenticated identity and application instance. Peer-based login/WebDAV protections, their transient state and security logs have separate data flows and retention settings; do not infer IP-free authentication logs from the LLM quota mechanism. See [login protection](LOGIN_BRUTE_FORCE_PROTECTION.md) and [configuration](CONFIGURATION_REFERENCE.md).

Security and administration events can identify actors and network peers. Include
application logs, reverse-proxy logs, monitoring, diagnostics and provider-side
records in the deployment inventory. Diagnostic prompt/response content may contain
the same sensitive information as the input. Do not infer payload-free logs from a
single logging switch; inspect the enabled routes and log configuration.

### Workspace and Analysis Data

| Record | Potential identifying content | Persistence boundary |
|---|---|---|
| Project requirement versions and source provenance | Requirement text, original fragments, creator, reasons | Application database; saved versions survive the browser session |
| Analysis jobs, results and snapshots | Input references, generated explanations, actor, model and review evidence | Persisted jobs/snapshots and their tenant-bound mappings |
| Reformulation offers and decisions | Frozen original, architecture evidence, questions, answers and revisions | Separate durable proposal journal; adoption does not erase older evidence |
| Accepted editor operations | Actor, rationale, inverse data and working revision | Durable semantic journal, separate from Git checkpoints |
| Git checkpoints and exported reports | Model text, evidence and author metadata | Database-backed Git, configured remotes and downloaded copies |
| Saved ad-hoc drafts and continuation records | Working text and retained answers | Saved workspace/run records, not just browser memory |

Numeric scores alone are not an adequate description of the complete analysis
payload. Do not classify all DSL, results or indexes as non-personal merely because
they describe architecture. The [architecture](ARCHITECTURE.md) and
[portfolio guide](PROJECT_REQUIREMENT_PORTFOLIO.md) explain these distinct histories.

## Data Storage Locations

| Component | What the operator must inventory |
|---|---|
| Relational database | Accounts, provenance, portfolio records, snapshots, journals and database-backed JGit objects/refs; use the configured database and its actual persistence volumes |
| Search indexes and caches | Derived copies of indexed material; inspect the enabled indexes, locations and rebuilding/deletion procedures rather than assuming only taxonomy labels are indexed |
| Files and external storage | Imported/exported artifacts, templates, temporary staging, downloaded reports, infrastructure backups and configured external Git repositories |
| Logs and connected services | Application/proxy logs, observability, identity provider and configured model endpoints |

`jgit-storage-hibernate` stores the application's logical Git repositories through
the relational database adapter. A generic `/app/data/git` directory is not their
universal location. Nor does choosing an enterprise database enable encryption at
rest automatically. Verify database, volume, backup and key protection independently;
HTTPS protects transport, not all stored copies. See [database setup](DATABASE_SETUP.md),
[repository topology](REPOSITORY_TOPOLOGY.md) and [operations](OPERATIONS_GUIDE.md).

## Legal Basis

The responsible organization must determine the applicable legal basis for its
actual processing, including any relevant national rules. A software feature,
security standard or provider name is not itself that legal basis. This page does
not assign one universal GDPR Article 6 basis to every employer or authority.
The [official GDPR text](https://eur-lex.europa.eu/eli/reg/2016/679) is the reference,
not a product checklist.

## Data Retention and Deletion

### Retention Periods

Set and document purpose-specific retention and review periods. This product
reference does not establish a universal 90-day account period, one-year log period
or indefinite retention permission. In particular, **ending a browser session does
not delete saved requirements, analyses, proposals, decisions or Git history**.
Apply the [GDPR storage-limitation principle](https://eur-lex.europa.eu/eli/reg/2016/679)
to the deployment's real inventory, including recipient copies and backups.

### Deletion Procedure

**Disabling an account is not erasure.** `UserManagementService.disableUser` saves
`enabled=false`; it does not remove historical authorship, requirement text or
other retained records. Account changes also must not be assumed to terminate
every existing session or connected credential; verify the applicable revocation
workflow and identity-provider behavior.

Do not use direct database deletion or an ad-hoc Git history rewrite as a supported
whole-application erasure procedure. They can break foreign keys, commit references,
provenance, synchronization and evidence while leaving other copies untouched.
A new Git commit or new requirement version does not remove the previous content.

Before carrying out an erasure decision, identify affected records and copies,
applicable retention obligations, authority and dependencies. Define a reviewed,
testable procedure covering journals, snapshots, indexes, exports, backups and
recipients, then verify both removal and remaining data integrity. There is no
verified one-click, cross-store personal-data erasure workflow established by this
page. Do not deploy a use case that depends on it without resolving that gap.

## Third-Party Data Transfers

### External LLM Providers

Depending on the feature, a generative request can include requirement text,
source excerpts, catalogue context, relationships, existing decisions and prompt
instructions. Review the actual feature's request contract and configured endpoint,
not only the provider label. Logs or stored continuation evidence may retain copies.

Assess processing terms, recipients, retention, training use, access locations and
any applicable transfer requirements for the selected service and configuration.
A provider's nationality or EU address alone does not establish data residency,
absence of subprocessors or a guarantee that prompts are not used for training.
See [AI transparency](AI_TRANSPARENCY.md) and [AI providers](AI_PROVIDERS.md).

### Air-Gapped Operation

Local embeddings avoid remote inference for their supported search/scoring work;
they do not implement generative relationship assessment or reformulation. A
local model must already be available when downloads are disabled, for example:

```bash
LLM_PROVIDER=LOCAL_ONNX
TAXONOMY_EMBEDDING_ENABLED=true
TAXONOMY_EMBEDDING_MODEL_PROFILE=MULTILINGUAL_MINILM_L12
TAXONOMY_EMBEDDING_MODEL_DIR=/app/models/multilingual-minilm
TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false
```

Provision and verify the mounted bundle using the [pinned model provisioning guide](../testing/multilingual-model-provisioning.md). Selecting `LOCAL_ONNX` alone does not enable embeddings.

These settings alone do not prove a network-isolated installation. Inventory
identity services, remotes, telemetry, downloads and other enabled integrations;
verify deployment egress controls and actual traffic. Do not present an unsupported
or unassessed analysis phase as completed merely to retain local-only operation.

<a id="technical-and-organizational-measures"></a>
## Technical and Organizational Measures (TOMs)

### Technical Measures

Use the [security guide](SECURITY.md) for authentication, role/scope checks, browser
CSRF protection, credential handling and deployment requirements. Verify HTTPS,
storage protection, backup access, network restrictions and recovery in the actual
installation. A document or green CI run is not evidence that an operator enabled
all those controls. Browser/session-authenticated APIs must not be assumed stateless.

### Organizational Measures

Define approved inputs and model endpoints, minimum access rights, administrative
responsibilities, retention, incident handling and the procedure for rights requests.
Review changes to integrations and prompts as possible changes to data flow. Retain
configuration-bound evidence of these decisions rather than a universal “fulfilled”
label. Verify configuration keys against the [configuration reference](CONFIGURATION_REFERENCE.md)
instead of relying on undocumented password or administrator-separation switches.

## Data Subject Rights

Requests concerning access, rectification, erasure or restriction must be evaluated
by the responsible organization under the applicable rules. An account JSON export
is not automatically a complete access response; disabling login does not restrict
all processing of already stored information. Include historical records and
recipient copies as appropriate. Do not equate a generic JSON export with fulfillment
of every condition for data portability. See the [GDPR](https://eur-lex.europa.eu/eli/reg/2016/679),
Articles 12–22, and the reviewed deployment procedure.

## Data Protection Impact Assessment

Assess whether the planned processing is likely to create a high risk to people's
rights and freedoms, using GDPR Article 35 and applicable supervisory guidance.
The number of organizational units or use of a model API alone is not a universal
yes/no rule. Document the screening and involve the responsible privacy specialists;
this page does not replace a deployment-specific assessment.

## BfDI Guidelines for AI in Federal Administration

This heading is retained for existing links. It does **not** mean that BfDI has
certified Taxonomy or that the former product table was an authoritative list of
BfDI requirements. Remove that inference from procurement or approval materials.

The supervisory authorities' [DSK guidance on AI and data protection, May 2024](https://www.lfd.niedersachsen.de/startseite/infothek/presseinformationen/kunstliche-intelligenz-datenschutzkonform-einsetzen-orientierungshilfe-fur-unternehmen-und-behorden-231889.html)
addresses selection, implementation and use. The [October 2025 RAG guidance announcement](https://www.lfd.niedersachsen.de/startseite/infothek/aktuelles/datenschutzkonferenz-veroffentlicht-orientierungshilfe-zu-ki-systemen-mit-retrieval-augmented-generation-rag-245773.html)
likewise emphasizes case-specific assessment. These are dated references, not a
claim that this product implements every criterion or that this is a complete
inventory of current guidance.

### Recommendations for Government Operators

Record the actual purpose, allowed input, endpoint, data locations and responsible
parties before approval. Verify local-only restrictions when required, and keep
functional limitations visible. Establish retention and rights-handling procedures
for durable evidence, not only accounts. Reassess when the software, configuration,
model service or permitted data changes.

## Related Documentation

[Security](SECURITY.md) · [AI transparency](AI_TRANSPARENCY.md) ·
[Architecture](ARCHITECTURE.md) · [Project portfolio](PROJECT_REQUIREMENT_PORTFOLIO.md) ·
[Operations](OPERATIONS_GUIDE.md) · [Configuration](CONFIGURATION_REFERENCE.md)

Technical review references at the baseline source:
[requirement versions](https://github.com/carstenartur/Taxonomy/blob/3ec8a98610e6cb6d7fc4b4addee4c4f1bb87fb04/taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/model/ProjectRequirementVersion.java),
[analysis snapshots](https://github.com/carstenartur/Taxonomy/blob/3ec8a98610e6cb6d7fc4b4addee4c4f1bb87fb04/taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/model/RequirementAnalysisSnapshot.java),
[account disabling](https://github.com/carstenartur/Taxonomy/blob/3ec8a98610e6cb6d7fc4b4addee4c4f1bb87fb04/taxonomy-app/src/main/java/com/taxonomy/security/service/UserManagementService.java).

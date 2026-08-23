# Versioned Word templates

Taxonomy stores Word templates as unpacked OOXML package trees in the logical
JGit/Hibernate repository `taxonomy-document-templates`. WebDAV and downloads
materialize complete `.dotx` files from that canonical representation.

## User interface and automated download test

The administration page at `/admin/document-templates` shows the report template
seeded on first startup, its Git version, the complete WebDAV address, and the actions
**Edit template in Word**, **New document from template**, **Download**, **History**,
and **Compare and restore**. The screenshot below was captured by the automated
browser test and shows the `ms-word:ofe` action on the template that was actually
created during application startup.

[![Document-template administration page with the “Edit template in Word” link](../images/document-template-management.png)](../images/document-template-management.png)

The `Document Template Report E2E` GitHub workflow starts the packaged Taxonomy
application through Testcontainers. Playwright signs in as an administrator, waits for
the idempotently seeded default template, verifies the visible Word and WebDAV links,
opens the template detail page, and downloads a data-free DOCX test report. The test
requires HTTP 200, the DOCX media type, the expected filename in
`Content-Disposition`, a plausible ZIP/DOCX signature, and a minimum file size.
LibreOffice then renders the report that was actually downloaded to PDF, and
`pdftoppm` captures its first page as shown below.

[![First page of the Taxonomy test report that Playwright actually downloaded](../images/decision-rationale-template-test-report.png)](../images/decision-rationale-template-test-report.png)

The workflow artifact also contains the downloaded DOCX, the rendered PDF, both
screenshots, the Playwright log, and JSON evidence recording the URL, media type,
filename, file size, and SHA-256 digest. This test proves container startup, default
seeding, the browser surface, and the report-download path. It does not replace the
separate acceptance test with an installed Microsoft Word version through public HTTPS
and WebDAV.

## Default decision-rationale report template

The application ships the required template

```text
decision-rationale-report
```

inside the container/JAR. On the first successful startup it is imported
idempotently as an initial Git commit. If the template already exists, neither a
restart nor a software update overwrites it, so organisation-specific logos,
identity data, and layout changes remain intact.

The DOCX decision report is rendered from the current Git version of this template.
Taxonomy recognizes, among others, these tokens:

```text
{{taxonomy.report.title}}
{{taxonomy.report.subtitle}}
{{taxonomy.report.status}}
{{taxonomy.report.requirement}}
{{taxonomy.report.generatedAt}}
{{taxonomy.report.generatedBy}}
{{taxonomy.report.taxonomyVersion}}
{{taxonomy.report.applicationVersion}}
{{taxonomy.report.workspace}}
{{taxonomy.report.branch}}
{{taxonomy.template.id}}
{{taxonomy.template.commit}}
{{taxonomy.report.body}}
```

`{{taxonomy.report.body}}` must occur exactly once as its own paragraph and be the
last non-empty body block. Taxonomy removes it during export and appends the
executive summary, decision chapters, diagrams, and appendix. The cover, images,
headers and footers, page setup, and Word styles remain part of the editable DOTX
template.

Uploads and restores of the default template are checked against this semantic
contract in addition to the generic OOXML security validation. The
`decisionRationaleTemplate` Actuator health component reports `DOWN` when the
required template is missing or structurally invalid.

## Version and concurrency semantics

Each template exposes the last Git commit that changed its own OOXML subtree as its
version and HTTP ETag. A commit to another template therefore does not invalidate an
open editor or cause a false conflict. Creating a template is create-only; replacing
or restoring one requires the current template ETag. `If-Match` uses strong HTTP
entity-tag semantics, accepts comma-separated alternatives, and never creates a
missing resource.

WebDAV locks improve the Word editing workflow. They are intentionally process-local;
the supported Helm profile therefore remains single-replica. Git's atomic
per-template expected-version check is the durable lost-update guard after a process
restart or expired lock. A future multi-replica deployment needs a shared lock store
before it can promise uninterrupted Word lock sessions across replicas.

## Access and transport

Authenticated users may read complete templates and create a new document from them.
Only administrators may upload, restore, lock, or save a template. Direct desktop Word
links require HTTPS, except for loopback development addresses. Normal HTTPS download
and a copyable WebDAV URL remain available as fallbacks.

The Keycloak profile disables direct `ms-word:` links by default. Desktop Word cannot
reuse the browser OIDC session and does not receive Taxonomy's bearer token. Operators
must not re-enable the links until they provide and test a WebDAV-compatible credential
flow such as a scoped application password. Bearer-capable WebDAV clients can continue
to use the endpoint directly.

Taxonomy rejects macros, ActiveX, embedded OLE objects, signatures, unsafe ZIP paths,
case-colliding package parts, malformed XML, external non-hyperlink relationships,
missing internal relationship targets, packages without exactly one root
`officeDocument` relationship to `word/document.xml`, and OOXML parts named
`template.json`, which is reserved for Taxonomy's internal manifest.

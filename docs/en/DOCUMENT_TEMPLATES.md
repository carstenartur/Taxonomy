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

## Audit provenance

Every template-backed DOCX stores its exact Word-template identity independently of
visible cover tokens. The custom document properties are
`Taxonomy.Template.Id`, `Taxonomy.Template.Commit`,
`Taxonomy.Template.PackageSha256`, and `Taxonomy.Template.SchemaVersion`. The commit
is the complete 40-character Git object ID and the package digest is the canonical
64-character SHA-256 from the validated template manifest. Removing the optional
`{{taxonomy.template.*}}` presentation tokens therefore does not remove the audit
record.

The DOCX download response exposes the same values through the allow-listed headers
`X-Taxonomy-Template-Id`, `X-Taxonomy-Template-Commit`,
`X-Taxonomy-Template-SHA256`, and `X-Taxonomy-Template-Schema-Version`. Internally the
format-neutral renderer result carries the same four fields as immutable artifact
metadata; HTML and JSON outputs do not claim a Word-template identity.

Auditors can inspect the properties in Word under **File → Info → Properties →
Advanced Properties → Custom**, or without Word by reading `docProps/custom.xml` from
the DOCX ZIP package. Compare the full commit with the template history and the
SHA-256 with the corresponding `template.json` manifest.

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

Taxonomy application credentials have one exact 71-character ASCII format. The WebDAV
filter rejects oversized Basic headers, invalid UTF-8, overlong decoded usernames or
passwords, and malformed `taxdav_` candidates before repository lookup or BCrypt.
Ordinary account passwords that do not start with `taxdav_` continue to the normal
Spring Security HTTP-Basic flow.

Failed application-credential attempts are tracked by a fixed-size SHA-256 digest of
the framework-resolved peer and normalized supplied username; raw identities are not
retained in the lockout table. Ten failures within one minute lock that identity. The
regular table is capped at 10,000 keys per application instance, and identities that
cannot be admitted share a fail-closed overflow budget instead of clearing existing
lockouts. A blocked request receives UTF-8 JSON with HTTP 429, `Retry-After`, and
`Cache-Control: no-store`. Operators must configure forwarding-header processing only
behind a trusted ingress because the filter deliberately relies on the framework's
`getRemoteAddr()` result and never parses forwarding headers itself. The filter never
logs the supplied Basic Authorization header or its credential contents.

Taxonomy rejects macros, ActiveX, embedded OLE objects, signatures, unsafe ZIP paths,
case-colliding package parts, malformed XML, external non-hyperlink relationships,
missing internal relationship targets, packages without exactly one root
`officeDocument` relationship to `word/document.xml`, and OOXML parts named
`template.json`, which is reserved for Taxonomy's internal manifest.

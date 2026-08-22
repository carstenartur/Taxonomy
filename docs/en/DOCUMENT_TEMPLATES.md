# Versioned Word templates

Taxonomy stores Word templates as unpacked OOXML package trees in the logical
JGit/Hibernate repository `taxonomy-document-templates`. WebDAV and downloads
materialize complete `.dotx` files from that canonical representation.

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
or restoring one requires the current template ETag. The final branch update remains
atomic and is retried when another Taxonomy instance changed only an unrelated
template.

WebDAV locks improve the Word editing workflow. The Git expected-version check is the
durable lost-update guard even after a process restart or expired lock.

## Access and transport

Authenticated users may read complete templates and create a new document from them.
Only administrators may upload, restore, lock, or save a template. Direct desktop Word
links require HTTPS, except for loopback development addresses. Normal HTTPS download
and a copyable WebDAV URL remain available as fallbacks.

Taxonomy rejects macros, ActiveX, embedded OLE objects, signatures, unsafe ZIP paths,
case-colliding package parts, malformed XML, external non-hyperlink relationships,
missing internal relationship targets, and packages without exactly one root
`officeDocument` relationship to `word/document.xml`.

# Documentation and help QA — 3 October 2026

## Delivery and evidence correction

This is a continuing QA checkpoint, not completion of the comprehensive audit.
PR #1164 now supplements its README changes with the existing English/German
portfolio guides and feature matrices, plus the developer verification instructions.
The independent resource-lifecycle correction is in PR #1165; it is not hidden in
this documentation change. No workflow, security rule or test threshold changes.

**The earlier quantitative results for the unpublished broad help rewrite are
withdrawn as reproducible verification evidence.** Its exact tested classes,
resources and execution records could not be recovered and bound to an immutable
candidate. This responds to review comment `discussion_r4171969851`. The new hashes
below do not retroactively identify that old candidate. In particular, the former
claims of a zero-finding link audit, successful browser checks and 118 + 118 HTTP
checks must not be used as acceptance evidence for either pull request.

The fresh results in this revision refer only to the exact source files and
runtime identified in [the input manifest](2026-10-03-documentation-inputs.json).
The baseline is `3ec8a98610e6cb6d7fc4b4addee4c4f1bb87fb04`; the documentation
amendment builds on PR #1164 parent `497dbbebe955a275b1c217220ec6065b98521f94`.

## Documentation corrections in this amendment

The compact, already registered `PROJECT_REQUIREMENT_PORTFOLIO` guides now explain
the complete clarification loop before their existing numbered chapters: source
selection, separate offer, evidence-linked questions, human decisions, explicit
adoption and separately requested reanalysis. Both languages include a clearly
illustrative mobile time-capture example, not a claimed recorded model result.
The same guides explain selected roots, taxonomies-only analysis, unresolved work,
compatible continuation and the limits of local embeddings.

The general feature matrices separate delivered behavior from semantic-quality
claims and unfinished backup/restore integration. Four rows per language with no
linked screenshot evidence no longer claim an unqualified complete evidence set.
The portfolio matrices point to the actual guide sections for versions, mapping
review, products, conflicts, matrices, Git and reports. Existing export support
boundaries and earlier numbered guide sections are preserved.

The Copilot instructions, guardrails and change-type testing guide now distinguish
bounded developer verification, the CI profile, separate browser evidence and the
external-database matrix. The actual database workflow runs on pull requests to
main, version tags, its schedule and manual dispatch. SQL Server and Oracle are
not merely scheduled/manual checks, but neither are they selected implicitly by
an ordinary `verify` or every `-Pci` invocation. These are documentation corrections,
not changes to the executable verification policy.

## Implementation decisions: fix causes without a new subsystem

PR #1165 is bound to commit `81c6b5464191ee64a8a647d96174a9b9c6327fee` and tree
`7603671ff0ab3b197feb52c7a641cc19e2146aa8`.

| Reproduced problem | Implemented correction | Reason for this choice |
|---|---|---|
| Different unsupported locale tags cache the same English document repeatedly | Resolve the actual packaged resource before using its path as the cache key | Cache identity follows the content source. No new cache library, eviction scheduler or second translation registry is needed. |
| Image streams are not explicitly closed after success or I/O failure | Use try-with-resources at the opening/read boundary | Resource ownership remains local and the existing response policy is preserved. |

The document/image allowlists, Markdown renderer, URL-rewrite rules, frontend,
sanitizer and HTTP routes remain unchanged in this code correction. The separate
browser/Markdown navigation rewrite that was previously blocked is not published
through another mechanism. Link/anchor/navigation defects remain open below.

## Fresh executed verification

Java 21.0.11 compiled the actual changed controller and shared test assertions
against the exact baseline runtime with `-Xlint:all,-path -Werror`. Only optional
dependency-manifest path warnings are excluded from this supplementary compile.
`HelpResourceLifecycleTest` delegates to the same six assertion methods; locally,
those assertions ran through their Java `main`, **not through JUnit or Maven**.

| Shared real-controller assertion | Baseline | Candidate in #1165 |
|---|---|---|
| Ten unsupported locales share one English cache entry | FAIL: ten entries | PASS: one entry |
| German and English retain distinct content | PASS | PASS |
| Successful image read closes the stream | FAIL: one opened, none closed | PASS |
| Failed image read closes the stream | FAIL: one opened, none closed | PASS |
| Existing traversal, allowlist and missing-image behavior | PASS | PASS |
| A missing document does not poison a later read | PASS | PASS |

The tiny tracked image stream is read through the actual controller and
`ClassPathResource`. Its deliberate read exception produces the expected warning.
No network service or mocked controller result supplies the assertion outcomes.

All 55 registered help documents were rendered in English and German through the
actual controller and Flexmark dependencies. Scope is critical:

| Compared candidate | Fresh result | What it does not prove |
|---|---|---|
| Lifecycle-only controller change with unchanged documents | All 110 HTML responses are byte-identical to baseline | Existing links or navigation are not thereby correct. |
| Exactly the nine additional documentation files in the input manifest, with unchanged controller | 110 pages render; six translated guide/matrix pages change, 104 stay identical; four explicit portfolio workflow anchors exist | No browser clicking, deployment or full prose audit is implied. |
| Separate prepared seven-file documentation patch | First user-guide table changes from two to all five steps in both languages through the actual renderer | This separate patch is not included in this amendment. |

The packaged Markdown inventory contains 337 files. Inventory and hash verification
are not a claim that every sentence received semantic review. Manual review focused
on the workflow, versioning, scoring, provider boundaries, matrices and verification
instructions relevant to the corrected files.

## The navigation audit is still red

In the 110 pages rendered from this amendment, the static HTML audit records:

- **644 occurrences** of local fragment links without a matching rendered target;
- **620 occurrences** of relative Markdown links requiring browser routing;
- no missing local image file among the checked `/help/images/` references.

These are occurrence counts, not a count of independent root causes or proven
HTTP 404 responses. External destinations were not requested. The existing browser
script does not provide a general cross-document `.md` resolver, and generated
heading targets are absent from the current renderer. Source-wide regular-expression
replacement also cannot reliably distinguish links from literal Markdown examples.
The next implementation needs a tested document-aware resolution contract and
browser navigation, not more path-specific substitutions or a claimed zero-finding
pass. User-facing sanitization and context-path handling must remain protected.

## Remaining prepared changes and verification boundaries

The separate seven-file patch covers both general `USER_GUIDE` files, both
`GIT_INTEGRATION` files, both `WORKSPACE_VERSIONING` files and the reformulation
feature page. It is prepared against complete baseline files and passes forward
application and reverse-application checks, but is not in this amendment.
Its SHA-256 is recorded in the input manifest. Git branch reset and personal
semantic undo remain different real operations; documentation must explain both,
not silently change one into the other.

The real attempted full command was:

```text
./mvnw verify -Pci -DrunOnnxTests=true
```

It exited 127 before execution because the recovered local workspace has no full
checkout or Maven wrapper. A classpath overlay and standalone assertions are not
a freshly built application. There is no local new full-reactor, JUnit, browser,
HTTP acceptance, JaCoCo or complete Node-suite pass. Each PR still requires its
own exact-head GitHub CI and review; predecessor success does not transfer.

Further work remains on general help navigation, the prepared seven-file patch,
full API/installation/operations accuracy, active screenshot correspondence and
external references. The audit is not complete merely because this bounded
correction is available. No live model requests were made.

## Reproduction and provenance

The input manifest records the baseline application artifact, every candidate
source blob/SHA-256, the diagnostic source hashes, execution logs, rendered-output
manifest and remaining findings. Diagnostic sources and logs are also retained
in the separately delivered QA package; runtime binaries and credentials are not.

Baseline artifact: CI run `37082514184`, artifact `11258279358`.
ZIP SHA-256: `42be2dd943b548f434b795f6259356a76b7936b6f7be524b6158193845315c60`.
JAR SHA-256: `fdc1f3522e3ba763d57518f44c8d5bd1642b1d058673015642e14f63e3774d62`.

The diagnostic runner compiles the published controller/assertion sources and
renders the same named source overlays against those extracted dependencies.
Its candidate identity is the listed source set, not the old artifact's commit.
Normal Maven/JUnit/browser verification in a full checkout remains authoritative.

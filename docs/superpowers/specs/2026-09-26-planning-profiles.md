# Bounded planning-information profiles

Approved scope: the German design in this conversation; implementation requested explicitly.

## Authority and scope
Planning information belongs to a canonical requirement in the existing workspace DSL. It does not edit requirement text, taxonomy entries or claim operational/compliance status. Existing workspace command revision, review, journal, checkpoint and history remain authoritative. Portfolio contribution preserves this namespace on its canonical requirement. There is no second SQL metadata store.

Two profiles, version 1: `go-live` (YEAR or DATE, exact precision), and `standard-reference` (identifier, optional edition and clause; reference only). Standard references use existing canonical source/version/fragment/link blocks. Unknown editions remain unspecified. Unknown profile versions are bounded, retained and read-only, never evaluated. Provenance is retained separately from the operation actor in the existing journal.

## Boundaries
A pure Java profile registry controls validation and canonical projection. A small versioned transport envelope carries profile values; product adapters declare representation/fidelity/evaluation separately. ReqIF is the only exchange extended. Its reserved STRING attribute transports the envelope; no assertion of native product evaluation. Unknown entries survive; malformed known entries fail visibly. Missing incoming metadata never deletes local planning information; explicit local deletion is an editor command. A reviewed explicit empty envelope may not delete remote data automatically.

A small editor panel supports adding, editing, removing and viewing entries, uses existing preview/rationale/If-Match/accept. Read-only revisions remain read-only. A norm reference is not compliance and go-live is a plan, not actual availability. Source identifiers and profile entries have stable identities. No automatic normative propagation across architecture mappings.

## Limits and exclusions
At most 32 entries/requirement, 32 fields/entry, bounded strings and envelope size. No executable profile schema, remote schema loading, norms download, compliance engine, temporal coverage engine, vendor-specific connector, background sync or new issue. No new dependency or deployment.

## Acceptance
Save/reload/DSL/diff/undo; exact year/date validation; canonical source-link round trip; current canonical values override old exchange evidence; unknown profile versions preserved without interpretation; third test profile without generic orchestration change; rejected import not applied; identity/conflict handling unchanged; missing remote fields are not deletes; UI keyboard/readonly/plain-text output; real ReqIF XML schema and identity validation.

Product compatibility claims remain limited to executed product/version/profile tests. Local artifact-classpath tests are not a full Maven build.

# ArchiMate import safety and workspace contract

ArchiMate 3.x Model Exchange XML is parsed and matched before any relation is materialized. The HTTP boundary resolves one explicit `WorkspaceContext`, and the importer receives that context as an argument rather than consulting global state.

## Endpoints

- `POST /api/import/preview/archimate` — available to authenticated users; parses, matches, and evaluates visible duplicates without writing.
- `POST /api/import/archimate` — restricted to ARCHITECT or ADMIN; atomically creates eligible relations in the exact active workspace.

## Transaction contract

The materializing import is one Spring transaction. Relations are created through `TaxonomyRelationService`, not by direct repository writes. Unexpected persistence failures are wrapped in `ArchiMateImportException` and escape the transactional boundary, rolling back all relations created by that request.

A concurrent equivalent relation is treated as a duplicate only when it is visible after the failed insert. Other failures are not converted into a partial-success response.

## Workspace visibility

A personal workspace sees its own relations plus the shared baseline. Therefore an equivalent shared relation is reported as a duplicate and is not copied into the personal workspace. Equivalent relations may still exist independently in two distinct personal workspaces.

A null workspace means shared scope only; it never means an unrestricted query across all users.

## Preview and counters

The result distinguishes:

- parsed elements and relationships;
- matched and unmatched elements;
- created relations;
- visible duplicates skipped;
- relationships rejected because one or both endpoints were unmatched;
- preview versus materializing mode.

Preview executes the same parsing, matching, relation mapping, and duplicate policy as import but performs no mutation.

## XML security

DTD support and external entity resolution are disabled on the StAX factory. Malformed XML, DTD declarations, and unresolved entity input produce HTTP `422` with error code `INVALID_ARCHIMATE_XML`. Internal stack traces and parser implementation details are not returned.

## Verification

Focused tests cover preview non-mutation, two-workspace isolation, same-workspace duplicate handling, malformed XML, endpoint 422 behavior, XXE rejection, and explicit facade context. Full CI additionally runs reactor coverage, CodeQL, Trivy, immutable supply-chain validation, database compatibility, container restart, UI, accessibility, frontend architecture, documentation checks, and the strict bounded-context architecture gate.

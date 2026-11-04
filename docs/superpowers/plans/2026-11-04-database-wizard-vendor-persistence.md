# Database wizard expansion implementation plan

1. Extract shared preparation orchestration/contracts; adapt PostgreSQL tests without weakening assertions.
2. Add SQL Server and Oracle providers with read-only inspection, locks, reviewed actions, restricted resource preparation, honest partial/unknown handling and vendor unit/container tests.
3. Generalize existing endpoints and wizard labels/validation/review; show Oracle PDB/tablespace/quota scope and compatibility schema-initialization boundary. Preserve all secret/timeouts/accessibility behavior.
4. Add bilingual persistence runbook and existing chart Oracle service support, secure URL handling and Compose app user initialization. Link concise persistence guidance in wizard.
5. Extend existing runtime database information with bounded identity/start queries, EN/DE rendering and database-container assertions.
6. Run focused Java/JS/chart verification, inspect diffs and independent review. Resolve concrete failures only; do not weaken gates.
7. Commit coherent changes, publish tree-identical to the existing branch using expected-head update, update PR #1374 scope and monitor required CI. Report remaining compatibility limits and measured verification honestly.

Parallel ownership: root shared service/controller/wizard/API and integration; db_expansion_vendor concrete new provider classes/tests; db_persistence_guidance help/chart/Compose; db_runtime_information existing system-info code/UI/tests. Only root runs Maven. No edits or data operations in the user's live cluster.

# Production repository-history implementation

Design: `docs/superpowers/specs/2026-10-11-production-repository-history-design.md`.
Worktree: `Taxonomy-history-production`, base `4747262`. Root implements; other
owners work separately. One heavy Maven/application lane at a time. No Main move
during release publication. Root owns all remote writes.

## 1. Original document visitor

Files: `GitHistoryCapture.java`, `GitHistoryBackupContributor.java`, existing
workspace owner tests.

- Add tests for exact ancestor/database-only documents, bounded individual and
  aggregate bytes, shared repositories, callback cancellation and changed source.
- Introduce a synchronous typed visitor. Reuse the existing verified object reader
  and document proof; check the whole source once before/after the pass, with
  checkpoint checks during original body reads. Keep standalone `copyDocument`
  semantics unchanged.
- Run the focused real Git/Hibernate owner tests at a bounded heap; preserve
  before/after hashes, classpath, logs and actual exits.

## 2. Production history selection and closure

Files: `ProductionCurrentBackupSource.java`, new production history tests and the
existing `ProductionCurrentBackupIT.java` admission case.

- First demonstrate actual current-source rejection of the three valid history
  layouts. Then select the original-history owner based on the validated profile.
- Share exact inspect/documents/write composition without rewriting the current
  path or widening full-installation admission prematurely.
- Extend knowledge closure with original history documents and raw editor
  working/operation/checkpoint DSL bodies. Reuse inline provenance and catalogue
  reference checks and the existing profile-aware metadata owners.
- Keep unsupported-current-layout admission assertions; replace the obsolete
  repository-history rejection assertion with real authorization-negative cases.

## 3. Full job and native archive proof

- Use real application routing/principals/Git objects/editor rows, a fixture source
  file and at least one historical-only knowledge reference. Include a second
  foreign workspace and an original database-only commit.
- Run actual job capture/download/verify/native clone+fsck/show for BUNDLE, BARE and
  WORKTREE. Assert original IDs, raw saved state, metadata/source closure and scope.
- Exercise revoked history access and missing retained source bytes; neither may
  expose a downloadable complete archive.
- Run ordinary Maven for the focused owners/application cases when the heavy slot
  is available, then independently review source and exact architecture edges.
- Integrate only verified checkpoints, retaining all full issue acceptance gates.

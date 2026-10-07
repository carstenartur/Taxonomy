# QA continuation: integrate merged search fixes — 7 October 2026

## Source and scope

PR #1177 started this continuation at `2553b516cd86846b401264762e062f30e2f2a299`.
Its canonical run 37536716929 and the observed database, security, scenario,
document, Kubernetes and reformulation runs had completed successfully.
PR #1178 was then merged into main as `ce1e42d10c4beb465b28235e2c9583673ba508d1`,
leaving #1177 conflictful. Earlier green runs do not certify their combination.

The integration preserves both histories with a two-parent commit on the PR
branch. It retains the PR's German UI, report/diagram controls, JMS fixes and
reformulation assertion repair. Incoming knowledge/domain trees, search error
mapping, translations, database fixtures and search tests are reused by their
exact main Git object IDs, not rewritten. The identical Archi URL correction is
retained without a version or checksum change.

Two overlapping files require combined content:

- `.github/package.json` retains every command from both parents. Both UI gates
  execute localization, state recovery and search-mode readiness exactly once;
  the existing diagram, print and integration-startup tests remain registered.
- `taxonomy-search.js` keeps clear/cancel/stale-response handling and German
  feedback from #1177, plus graph readiness and failed-status invalidation from
  #1178. No background polling or alternate search path is introduced.

## Review finding: comment deletion in the POM test

The open CodeQL discussion identified `replace(/<!--[\s\S]*?-->/g, '')` in the
shard-plan test. Deleting a comment can join surrounding fragments into a new
XML delimiter or element name. This code reads the repository-owned POM for
assertions; it is not an application HTML rendering path.

Comments are now masked with spaces while preserving line breaks and source
positions. Five focused cases cover boundaries, adjacent comments, unchanged
non-comment text and fragments of opening/closing delimiters. Four fail with
the former deletion behavior; all five pass with masking. No scanner rule,
security threshold or branch protection is disabled or suppressed. The new
CodeQL run still has to confirm the scanner result.

## Fresh local verification

- Original shard-plan suite: **7/7 passed**. With the five comment cases before
  correction: **8 passed, 4 failed**. Afterwards: **12/12 passed**.
- Two additional gate-registration checks reject both a PR-only package and a
  main-only package. They pass with the combined command inventory.
- The exact five incoming search-mode tests fail against the old PR source and
  pass against the combined search source. Final combined repository-test run:
  **19/19 passed**, no failures or skips.
- Two standalone tests of the actual combined search script passed: clearing
  cancels the transport and rejects late results even during a readiness update;
  German failed-search feedback remains distinct from an empty successful result.
  These use controlled DOM/translation/HTTP boundaries, not a live browser.
- JavaScript syntax, JSON parsing, command preservation and changed-file
  whitespace checks passed. Published code blobs match the locally tested bytes:

| File | Git blob |
| --- | --- |
| `.github/package.json` | `bfa8ae01d04cb3bb7c97f286eaf7960db7c05215` |
| `.github/scripts/ui-shard-plan.test.mjs` | `90ab558bc77f19209e7367c7da28e76692f9d8c7` |
| `taxonomy-app/src/main/resources/static/js/shared/taxonomy-search.js` | `490d218168f38932a109c768651de42e247d34e2` |

Local Node is **22.16.0**. The repository clone failed at GitHub DNS resolution.
The focused workspace contains hash-checked source and previously supplied
artifacts, not the full checkout. The full `npm run verify:ui-contracts` attempt
stopped at missing `scripts/ui-localization-regression.test.mjs`. No new full
local Maven, browser, CodeQL or database acceptance is claimed. CI must validate
the integrated commit using its pinned toolchain before merge.

The separately recorded login-localization issue #1179, manual accessibility
acceptance and end-to-end performance measurements remain outside this repair.

# Confirming a pull-request review

The required **Maven verification** check accepts a complete, comment-free Copilot
approval automatically. A comment-free **Needs a closer look** review requires
human confirmation. If Copilot reports valid but partial file coverage, a human
can complete the review of the full change for either outcome. Every path still
requires every authoritative test lane, digest-bound UI evidence, all changed
files reviewed and no unresolved current review threads. A **Changes recommended**
outcome cannot be overridden.

For human confirmation, inspect the complete current change, Copilot's risk areas
and the CI evidence. Then choose one of these paths:

* A repository writer other than the PR author can submit GitHub's **Approve**
  review on the current commit after the latest Copilot review.
* A repository writer, including the PR author, can add a new comment in the
  PR's **Conversation** tab. When Copilot reviewed all changed files and only
  closer review remains, use exactly:

  ```text
  /confirm-review FULL_40_CHARACTER_HEAD_SHA COPILOT_REVIEW_ID
  ```

  When completing partial Copilot coverage, explicitly attest review of **every
  changed file** with the PR's exact total, for example:

  ```text
  /confirm-review FULL_40_CHARACTER_HEAD_SHA COPILOT_REVIEW_ID all-files=121
  ```

  This example supplements a review such as `113/121` only if the PR actually
  changes 121 files. The original command without `all-files` keeps its previous
  closer-review meaning and cannot supplement missing coverage. A native peer
  **Approve** review covers the complete current change and supports both cases.

Missing or invalid Copilot coverage metadata, a reported total different from
the PR's changed-file count, generated review comments and human **Changes
requested** remain blockers. A full-change confirmation cannot override them.

The failing **Maven verification** step summary prints the exact command for that
head and review. The review ID is the numeric suffix of its
`#pullrequestreview-…` link. Do not use a short SHA or add prose or code fences to
the actual comment. The command is an explicit maintainer attestation that the
closer review or the declared full-change review was performed. It does not create
a GitHub approval or claim an independent peer review. Automation must never post
it on a human's behalf.

GitHub deliberately prevents authors from approving their own PRs. The comment
path supports a solo maintainer while recording who confirmed which commit and
which Copilot review. The gate verifies current repository write/maintain/admin
permission using GitHub's collaborator API; author association alone is insufficient.
Bot and GitHub App submissions cannot provide human confirmation.

A new commit or a new Copilot review requires a new confirmation. Editing a
confirmation makes it invalid; delete it and post a new one. To withdraw a comment
confirmation, delete it (all valid confirmations must be withdrawn if several
people confirmed). Native approvals can be dismissed. A current human
**Changes requested** review remains a blocker until dismissed or superseded by
that reviewer's approval. Ordinary comments do not clear it.

The trusted **Refresh PR review gate** workflow reevaluates comment creation,
editing and deletion, and completed CI runs. A scheduled reconciliation also
detects later reviews, dismissals, permission changes and thread changes; GitHub
may delay scheduled runs. Maintainers can run that workflow manually when needed.
It only reruns the final **Maven verification** job if its review result changed
and the authoritative lane and UI evidence steps already passed. Technical failures
still need their own fixes. It never executes PR code with its Actions write token
and never publishes a synthetic success status.

The recorded quality evidence includes the confirmer, source comment/review,
timestamp, exact head SHA, Copilot review ID and confirmation scope. Full-change
confirmation also records the number of changed files. Copilot's original counts
remain unchanged; human completion is separate evidence. The merged-PR audit recognizes
only confirmations submitted before the merge. It conservatively rechecks current
permissions and currently available, unedited evidence.
An automated approval submitted after merge cannot erase findings about missing,
incomplete or non-approving evidence at merge time. Post-merge review and remediation
are reported separately.

## Installing or upgrading this policy

A policy upgrade must first reach the protected default branch through the
**previously trusted policy**. The pull request that introduces a new policy
version cannot use that new version to approve itself: CI deliberately loads the
gate from the run's original trusted **base SHA**.

For the version-3 metadata-mismatch recovery this means the rollout PR itself
must remain stable and satisfy version 2 normally: all technical gates must pass
and Copilot must submit a clean review that version 2 recognizes as bound to the
rollout PR's exact head. Do not force-push or amend that reviewed rollout head.
The version-3 `all-files` metadata-binding fallback is available only **after**
that rollout PR has merged into `main`.

If the rollout PR itself does not obtain an exact-head review under the old
policy, automation must not bypass branch protection, synthesize a success check,
or execute the PR's replacement gate with an Actions-write token. Request a fresh
review of the unchanged rollout head. If GitHub still cannot produce evidence
accepted by the old protected policy, the rollout requires an explicit
repository-governance decision outside this automation; the gate stays failed.

After the policy upgrade merges, update each affected open PR from the new
`main` so its CI run has a base SHA containing the new policy. Let normal CI and
Copilot review finish, then use the exact confirmation command printed for that
new head when human completion is required. Rerunning an old-base CI run does not
adopt a new policy version.

The refresh workflow intentionally refuses to rerun an old-base gate when its
confirmation policy version differs from the current trusted source. Existing
branch protection, required checks, writer-permission checks, native GitHub
review requirements, edit-history validation and technical gates remain
unchanged.

References: [GitHub approval rules](https://docs.github.com/en/pull-requests/how-tos/review-pull-requests/approving-a-pull-request-with-required-reviews),
[collaborator permissions](https://docs.github.com/en/rest/collaborators/collaborators#get-repository-permissions-for-a-user),
[issue comment events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#issue_comment),
[rerunning a job](https://docs.github.com/en/rest/actions/workflow-runs#re-run-a-job-from-a-workflow-run).

# Confirming a pull-request review

The required **Maven verification** check accepts either a complete, comment-free
Copilot approval or a complete, comment-free **Needs a closer look** review followed
by human confirmation. Both paths still require every authoritative test lane,
digest-bound UI evidence, all changed files reviewed and no unresolved current
review threads. A **Changes recommended** outcome cannot be overridden.

For a closer-look review, inspect the complete current change, Copilot's risk
areas and the CI evidence. Then choose one of these paths:

* A repository writer other than the PR author can submit GitHub's **Approve**
  review on the current commit after the latest Copilot review.
* A repository writer, including the PR author, can add a new comment in the
  PR's **Conversation** tab containing exactly:

  ```text
  /confirm-review FULL_40_CHARACTER_HEAD_SHA COPILOT_REVIEW_ID
  ```

The failing **Maven verification** step summary prints the exact command for that
head and review. The review ID is the numeric suffix of its
`#pullrequestreview-…` link. Do not use a short SHA or add prose or code fences to
the actual comment. The command is an explicit maintainer attestation that the
closer review was performed. It does not create a GitHub approval or claim an
independent peer review. Automation must never post it on a human's behalf.

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
timestamp, exact head SHA and Copilot review ID. The merged-PR audit recognizes
only confirmations submitted before the merge. It conservatively rechecks current
permissions and currently available, unedited evidence.

## Installing this policy

The policy and refresh workflow must first reach the protected default branch
through the existing review process. This change cannot approve itself: CI loads
the gate from the run's original trusted **base SHA**. The new refresh workflow
also refuses to rerun an old-base gate that does not support confirmation.

After installation, update each open PR from `main` and let its normal CI and
Copilot review finish. Confirm the resulting new head and review as described
above. Rerunning an old CI run does not adopt the new policy. Existing branch
protection, required checks and native GitHub review requirements are unchanged.

References: [GitHub approval rules](https://docs.github.com/en/pull-requests/how-tos/review-pull-requests/approving-a-pull-request-with-required-reviews),
[collaborator permissions](https://docs.github.com/en/rest/collaborators/collaborators#get-repository-permissions-for-a-user),
[issue comment events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#issue_comment),
[rerunning a job](https://docs.github.com/en/rest/actions/workflow-runs#re-run-a-job-from-a-workflow-run).

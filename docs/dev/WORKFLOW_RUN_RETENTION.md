# GitHub Actions workflow-run retention

Taxonomy keeps enough GitHub Actions evidence to diagnose the current state without retaining every obsolete run indefinitely.

## Protected evidence

The cleanup never deletes a completed run merely because it is old when the run belongs to:

- the current default-branch commit;
- the current head commit of an open pull request;
- a release or tag commit;
- the newest successful run of an active workflow;
- the newest failed run of an active workflow; or
- the configured minimum number of newest runs of an active workflow.

Queued and running jobs are not considered for deletion.

## Active and historical workflows

A workflow is active when its workflow file still exists on the default branch. Removed one-off and repair workflows are historical. Historical workflow identities receive a shorter grace period and are processed before active workflows at the same round-robin position, so obsolete names disappear from the Actions page without allowing one large workflow to consume the whole deletion budget.

## Defaults

The scheduled run executes daily at 02:17 UTC and uses:

- five days of retention for active workflows;
- two days of retention for historical workflows;
- at least three newest runs per active workflow; and
- at most 1,500 deletions per execution.

Manual executions default to `dry_run=true`. The job summary reports the protected evidence, the deletion candidates, the selected runs, remaining backlog, and per-workflow counts. A non-dry run stops before the GitHub API rate limit becomes unsafe and after repeated deletion failures.

## Manual cleanup

Run **Cleanup Old Workflow Runs** from the Actions page. Review a dry run first, then repeat with `dry_run=false` when the proposed retention result is acceptable. The current heads of open pull requests and the current default-branch head remain protected in both modes.

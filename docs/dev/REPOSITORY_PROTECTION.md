# Repository protection and delivery administration

The codebase enforces release and delivery contracts in CI. The `main` branch ruleset must still be enabled once by a repository administrator because it is a GitHub setting rather than a versioned file.

## Protect `main`

Create an active branch ruleset for `main` with these settings:

- require a pull request before merging;
- require the **Maven verification** status check;
- require the branch to be up to date before merging;
- require all review conversations to be resolved;
- block force pushes and branch deletion;
- do not grant routine bypass access to automation or maintainers.

The canonical gate is the `verify` job in `.github/workflows/ci-cd.yml`, displayed by GitHub as **Maven verification**. Delivery runs only after that workflow completed successfully for the exact `main` commit. `.github/CODEOWNERS` assigns review ownership for workflows, release scripts, the Dockerfile and Helm deployment files.

## Render deployment verification

When `RENDER_DEPLOY_HOOK_URL` is configured, the Delivery workflow triggers the hook and then waits for the exact source commit to appear in `/actuator/info` while readiness remains `UP`.

The public service defaults to:

```text
https://taxonomy-analyzer.onrender.com
```

Set the optional repository variable `RENDER_BASE_URL` only when that public root changes. Use the externally reachable service root without a trailing path.

## Verification

After activating the ruleset:

1. Open a pull request and confirm that direct merging is blocked until **Maven verification** succeeds.
2. Merge the pull request and inspect the **Delivery** workflow.
3. Confirm that GitHub Pages contains `quality-summary.json` with the merged commit SHA.
4. Confirm that the Render job reports the same SHA as live.

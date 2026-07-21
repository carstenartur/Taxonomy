# CI Permission and Publication Boundaries

Verification and repository mutation are deliberately separated.

## Read-only verification

Ordinary pull-request, branch, and manual verification runs use
`contents: read`. The build job may additionally use `checks: write` to publish
JUnit annotations. Checkout credentials are not persisted. Compilation, tests,
SBOM creation, dependency validation, report generation, and artifact upload do
not have permission to change repository contents.

## Write-capable publication

Only narrowly scoped jobs can write:

- `publish-reports` runs after a successful default-branch push, downloads the
  immutable report artifact, and updates the GitHub Pages branch.
- `Documentation Screenshots` is a separate `workflow_dispatch` workflow. Its
  generation job is read-only; only the final main-branch publication job has
  `contents: write`.
- Container publication has `packages: write` and only `contents: read`.

Write-capable workflows validate event type and target ref, use concurrency
protection, and receive generated content through artifacts rather than sharing
a mutable build workspace.

## Action updates

Dependabot is the normal update mechanism for action major versions. A review
must confirm the action owner, required permissions, and release notes. Security
sensitive third-party actions may be pinned to an immutable commit SHA when the
maintenance cost is justified; the adjacent comment must identify the upstream
tag so Dependabot or a maintainer can update it deliberately.

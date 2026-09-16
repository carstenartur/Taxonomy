# Analysis live-progress dependency review

The live-progress implementation in PR #1067 adds two measured package-level
edges to the existing **analysis -> workspace** ownership direction. This is a
scoped baseline amendment, not permission for arbitrary future dependencies.
The unchanged `ArchitectureContextDependencyRatchetTest` continues to compare
all counts exactly and still rejects unclassified production code.

## Measured delta

The compiled-source ratchet on `f1152fab618379e8e9afd9b9852b4cfabf323814`
reports analysis/controller -> workspace/service growing from 2 to 3 class
dependencies, and analysis/service -> workspace/service growing from 0 to 2.
Every other package edge remains unchanged. These changes introduce no new
context-level direction; analysis already depends on the workspace owner API.
The baseline records exactly these counts, not upper bounds or exclusions.

## Responsibility and security rationale

`AnalysisProgressController` obtains the authenticated owner and the current
request-pinned context through `WorkspaceResolver`. It must not trust an owner,
repository or branch supplied as an unverified progress-request parameter.
`AnalysisProgressRegistry` and its immutable scope key use `WorkspaceContext`
to bind observation, diagnostic detail reads and cancellation to the same
owner/workspace/repository/branch tuple as the accepted analysis. The registry
stores that small identity tuple, not workspace entities, Hibernate sessions or
Git repositories. It does not alter workspace lifecycle, provision repositories,
perform Git writes, or turn transient telemetry into the durable job history.
No workspace -> analysis dependency is added.

This owner-API dependency remains explicit when analysis is extracted as a
feature JAR. Hiding it behind an unclassified adapter or duplicating workspace
selection and authorization would not improve this boundary. Future extraction
must retain exact scope checks and the existing workspace owner API, and must
rerun the measured module graph rather than treating this note as an exemption.

The cancellation regression suite exercises the actual shared Spring Security
rules and MVC controller: owner access with valid CSRF, authentication and CSRF
rejection, denial of other owners/workspaces/repositories/branches, and denial of
unrelated sibling writes. Existing metadata/detail scope tests remain intact.

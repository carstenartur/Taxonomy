# Frontend API boundary

Browser feature modules call named domain methods under
`taxonomy-app/src/main/resources/static/js/api`. The canonical
`api/taxonomy-api-client.js` alone acquires the ordinary HTTP transport. Merely
placing a file in `api/` does not exempt it from the boundary policy.

## Request and delivery ownership

Named methods retain domain routes, encoding, optimistic concurrency and expected
business statuses. The canonical client owns application-prefix and CSRF handling,
request IDs, authentication transitions, deadlines, cancellation and guarded
response bodies. Authentication failures never become accepted business statuses.
Writes have no automatic retries. Explicit source/workspace pins take precedence
over the mutable current context.

The transport composes the same registered admission factories used by the
remaining legacy compatibility wrappers. This preserves workspace routing,
analysis generation, durable operation cancellation and architecture workflow
source identity. Removing a wrapper without preserving its admission factory is
not a completed migration. Feature handlers additionally fence local selections,
dialogs and progress ownership: a late success, error or cleanup must not replace
a newer interaction within the same workspace.

## JUnit policy

`FrontendApiBoundaryPolicyIT` remains the single full-checkout policy owner in
`taxonomy-build`. Its lexer examines executable JavaScript, including template
interpolations, statically resolvable aliases, parentheses, destructuring and
computed browser-global properties. It excludes comments, quoted prose, regular
expressions and raw template text. It detects native `fetch`, `EventSource` and
`XMLHttpRequest` acquisition and alias invocation. It is a bounded static policy,
not a complete JavaScript execution model.

The ordinary feature migration allowlist is empty after this migration. New
literal `/api/` calls outside the canonical transport and specifically bounded
infrastructure are rejected. Native acquisition debt is also compared per file
and in aggregate with the Git baseline, preventing a variable-URL call from
being hidden by moving it between files. The explicit infrastructure ledger has
fixed native-reference budgets and restricted API prefixes. SSE-only exceptions
also reject ordinary HTTP acquisition independently of that budget.

For pull requests CI supplies `FRONTEND_API_BASE_REF`; push and local verification
fall back to `HEAD^`. Revisions are passed to Git without shell evaluation.
The canonical command remains `./mvnw -B verify -Pci`. The policy writes baseline,
current inventory, exceptions, violations and decision to
`target/frontend-api-boundary-report.txt`.

## Narrow infrastructure exceptions

| Module | Purpose | Removal condition |
|---|---|---|
| `taxonomy-i18n.js` | Application-prefix/locale bootstrap before the canonical client; only `/api/i18n` | Canonical transport owns prefix capture and locale bootstrap |
| `core/taxonomy-analysis-session-core.js` | Legacy workspace routing and native SSE reconnect routing | Last legacy consumer retires and equivalent SSE routing is owned by the named stream adapter |
| `core/taxonomy-analysis-session-transport.js` | Same generation-admission factory for named and legacy consumers | Last legacy installer consumer migrates |
| `core/taxonomy-operation-coordinator.js` | Same durable-operation admission factory for named and legacy consumers | Last legacy installer consumer migrates |
| `portfolio/taxonomy-portfolio-async.js` | Shared durable Portfolio job admission factory | Last legacy installer consumer migrates |
| `shared/architecture-workflow.js` | Same immutable source-routing factory for named and legacy consumers | Last legacy installer consumer migrates |
| `core/taxonomy-state.js` | SSE lifecycle facade cancels obsolete analysis sessions and preserves native stream semantics | Complete stream/session facade moves into the named SSE adapter |
| `core/taxonomy-scoring.js` | Native `/api/analyze-stream` events with selection/recovery fencing and explicit close | Complete scoring stream lifecycle moves into the named SSE adapter |
| `api/analysis-session-api.js` | Native `/api/analysis-runs` SSE, immutable pins and Last-Event-ID reconnect protocol | Equivalent named streaming transport owns the full lifecycle |
| `shared/taxonomy-help.js` | Public `/help` HTML outside authenticated architecture context | Named documentation HTML reader owns these static assets |
| `shared/taxonomy-about.js` | Public notices asset download only; authenticated About calls use named methods | Named static asset reader owns notice downloads |

These exceptions do not authorize general feature HTTP. Binary exports use the
canonical blob response lifecycle; a download is not a native-fetch exception.

## Domain caller inventory for issue #626

| Required domain | Named boundary and actual caller coverage |
|---|---|
| Workspace / provisioning | Workspace, Context, Preferences and WorkspaceProvisioning APIs; workspace selection, sync, publish, provisioning/projection status and rebuild; successful switch keeps remembered workspace and reload behavior |
| Versioning | Versioning methods in `workspace-api.js`; history, state, branch comparison, diff, restore/revert/undo/save, branch deletion, merge/cherry-pick conflict resolution and selective copy-back; local timeline/dialog ownership retained |
| Relations / graph | Relations, proposals, hypotheses and DSL boundaries; relation CRUD, Git commands, graph and coverage callers |
| Search / browse | Search and reference boundaries; semantic search, taxonomy reads and navigation callers |
| Portfolio | Portfolio boundary and shared job-admission factory; Portfolio views and background work |
| Import | Import boundary; document intake retains cancellation and admission behavior |
| Analysis | Analysis boundary and shared generation-admission factory; specialized native SSE remains explicitly accounted for above |
| Export | Export boundary; guarded binary responses and source verification retained |
| Workbench / Copilot / templates | Named clients and feature handlers, including standalone document-template bootstrap |
| Global account / startup / About | Named global reads explicitly avoid workspace-navigation cancellation while retaining authentication invalidation |

Local focused tests establish individual contracts; they do not replace hosted
full-reactor, native-browser or runtime-container verification. The final PR must
link its exact source and canonical CI evidence before issue closure. Every
remaining native interface is recorded above; no ordinary domain exception is
silently treated as complete.

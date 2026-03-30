# Preferences

The Preferences system provides **runtime-configurable settings** for the Taxonomy Architecture Analyzer. All changes are persisted in a dedicated JGit repository with a full audit trail — every update creates a Git commit recording who changed what and when.

## Table of Contents

- [Overview](#overview)
- [Settings Scope](#settings-scope)
- [Accessing Preferences](#accessing-preferences)
- [Using the Web UI](#using-the-web-ui)
- [Available Settings](#available-settings)
- [LLM Configuration](#llm-configuration)
- [DSL and Git Configuration](#dsl-and-git-configuration)
- [Size Limits](#size-limits)
- [Diagram Configuration](#diagram-configuration)
- [Audit Trail](#audit-trail)
- [Resetting to Defaults](#resetting-to-defaults)
- [REST API Endpoints](#rest-api-endpoints)
- [Security](#security)
- [Related Documentation](#related-documentation)

---

## Overview

Preferences are stored separately from the Architecture DSL in their own JGit repository (`taxonomy-preferences`). This separation ensures that preference changes do not interfere with architecture version history.

On startup, the system loads preferences from the JGit HEAD. If no commits exist (first run), defaults from `application.properties` are used and committed as the initial state.

Changes take effect **immediately** — no application restart is required.

---

## Settings Scope

All preferences are **system-wide** — they apply to the entire application instance and affect all users and workspaces. There are no per-user or per-workspace preferences.

| Scope | Icon | Meaning |
|---|---|---|
| ☁️ **System** | Shared across all users and workspaces | All preferences in this application |

The following table clarifies the scope for each setting category:

| Category | Scope | Who Can Change | Affects |
|---|---|---|---|
| **LLM Configuration** | ☁️ System | Admin only | All analysis requests for all users |
| **DSL and Git Configuration** | ☁️ System | Admin only | The shared DSL repository and all branches |
| **Size Limits** | ☁️ System | Admin only | All users' analysis, export, and view operations |
| **Diagram Configuration** | ☁️ System | Admin only | Architecture diagram rendering for all users |

> **Note:** Per-user and per-workspace preferences are not currently supported. If a setting needs to be different for different users or teams, it must be configured at the environment-variable level using separate deployment instances (see [Configuration Reference](CONFIGURATION_REFERENCE.md)).

**Related scoped data:**
- **Workspace state** (current branch, navigation history, projection) — per-user, managed by the [Workspace Manager](WORKSPACE_VERSIONING.md)
- **Architecture DSL** — stored in Git repositories managed by `DslGitRepositoryFactory`; each workspace has its own repository (repo-per-workspace isolation with cross-repo publish/sync), with a shared `taxonomy-dsl` system repository
- **Preferences** — stored in a separate JGit repository (`taxonomy-preferences`); system-wide
- **User accounts and roles** — stored in the database; per-user

---

## Accessing Preferences

Preferences can be managed through the **web UI** or the **REST API**. Both methods require the **ADMIN** role.

### REST API

```bash
# Get all current preferences
curl -u admin:password http://localhost:8080/api/preferences

# Update preferences
curl -u admin:password -X PUT \
  -H "Content-Type: application/json" \
  -d '{"llm.rpm": 10, "llm.timeout.seconds": 45}' \
  http://localhost:8080/api/preferences
```

---

## Using the Web UI

The **Preferences** tab in the navigation bar provides a graphical interface for managing all application settings. This tab is visible only to users with admin privileges.

### Opening Preferences

1. Click **⚙️ Preferences** in the main navigation bar (visible only when logged in as admin).
2. The Preferences page opens, showing all settings grouped into three sections.

### Editing Settings

The Preferences page is organized into three collapsible cards:

1. **🤖 LLM Configuration** — Controls for LLM request rate, timeout, server rate limiting, and minimum relevance score.
2. **📂 JGit / DSL Configuration** — Default branch, project name, auto-save interval, remote Git URL, token, and push-on-commit toggle.
3. **📈 Size Limits** — Maximum business text length, architecture nodes, and export nodes.

Each card shows the current value for each setting. To change a setting:

1. Modify the value in the input field.
2. Click **💾 Save** to persist the changes (creates a Git commit in the preferences repository).
3. A success message confirms the save.

### Viewing Change History

At the bottom of the Preferences page, expand the **📋 Preferences Change History** section to see an audit trail of all preference changes, including the commit ID, author, timestamp, and commit message.

### Resetting to Defaults

Click **↩️ Reset to Defaults** to restore all settings to the values from `application.properties`. This also creates a Git commit, so the previous values can be recovered from the history.

---

## Available Settings

> All settings listed below have **☁️ System** scope — they apply globally to all users and workspaces.

### LLM Configuration

| Key | Type | Default | Scope | Description |
|---|---|---|---|---|
| `llm.rpm` | int | `5` | ☁️ System | Maximum API requests per minute (outgoing LLM throttle) |
| `llm.timeout.seconds` | int | `30` | ☁️ System | HTTP read timeout for LLM API calls |
| `rate-limit.per-minute` | int | `10` | ☁️ System | Incoming rate limit for analysis endpoints (per IP) |
| `analysis.min-relevance-score` | int | `70` | ☁️ System | Minimum score for nodes to appear in analysis results |

The `llm.rpm` setting controls the sliding-window throttle for outgoing LLM API calls. The system maintains a FIFO queue of timestamps and sleeps the thread if the rate limit would be exceeded. A 50ms grace period is added for clock drift.

The `llm.timeout.seconds` setting dynamically updates the `RestTemplate` read timeout without restarting the application.

### DSL and Git Configuration

| Key | Type | Default | Scope | Description |
|---|---|---|---|---|
| `dsl.default-branch` | string | `"draft"` | ☁️ System | Active branch for DSL materialization |
| `dsl.project-name` | string | `"Taxonomy Architecture"` | ☁️ System | Human-readable project display name |
| `dsl.auto-save.interval-seconds` | int | `0` | ☁️ System | Auto-save frequency (0 = disabled) |
| `dsl.remote.url` | string | `""` | ☁️ System | Remote Git URL for push/pull operations |
| `dsl.remote.token` | string | `""` | ☁️ System | Authentication token for remote Git (masked in API responses) |
| `dsl.remote.push-on-commit` | boolean | `false` | ☁️ System | Automatically push to remote after local commits |

> **Security note:** The `dsl.remote.token` value is masked in all API responses. When retrieved via `GET /api/preferences`, it appears as `"****{last4chars}"`. The full token is only used internally for Git remote operations.

### Size Limits

| Key | Type | Default | Scope | Description |
|---|---|---|---|---|
| `limits.max-business-text` | int | `5000` | ☁️ System | Maximum characters in a business requirement text |
| `limits.max-architecture-nodes` | int | `50` | ☁️ System | Maximum nodes displayed in the architecture view |
| `limits.max-export-nodes` | int | `200` | ☁️ System | Maximum nodes included in an export operation |

### Diagram Configuration

| Key | Type | Default | Scope | Description |
|---|---|---|---|---|
| `diagram.policy` | string | `"defaultImpact"` | ☁️ System | Selection policy for architecture diagrams |

The `diagram.policy` setting controls which nodes and edges are visible in architecture diagrams. Available values:

| Value | Description | Use Case |
|---|---|---|
| `defaultImpact` | Relevant nodes with score-based filtering; roots and scaffolding suppressed | Day-to-day analysis |
| `leafOnly` | Only deepest leaf nodes; intermediate parents suppressed; edges re-routed | Showcase / README diagrams |
| `clustering` | Intermediate nodes become visual containers grouping their children | Grouped architecture views |
| `trace` | Full hierarchy preserved; nothing suppressed | Audit and traceability |

Changes take effect on the **next diagram export** — no application restart required.

---

## Audit Trail

Every preference change creates a Git commit in the `taxonomy-preferences` repository. The commit includes:

- **Author** — The authenticated user who made the change
- **Timestamp** — When the change was made
- **Full JSON snapshot** — The complete preference state after the change

View the change history:

```bash
curl -u admin:password http://localhost:8080/api/preferences/history
```

Response:

```json
[
  {
    "commitId": "abc1234...",
    "author": "admin",
    "message": "Preference update",
    "timestamp": "2025-01-15T10:30:00Z"
  }
]
```

---

## Resetting to Defaults

To reset all preferences to the values from `application.properties`:

```bash
curl -u admin:password -X POST http://localhost:8080/api/preferences/reset
```

This creates a new commit in the preference history, so you can always revert back to a previous state using the Git history.

---

## REST API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/preferences` | Get all preferences (token masked) |
| `PUT` | `/api/preferences` | Update preferences (creates Git commit) |
| `POST` | `/api/preferences/reset` | Reset to `application.properties` defaults |
| `GET` | `/api/preferences/history` | Get preference change history |

All endpoints require the **ADMIN** role and authentication.

---

## Security

- All preference endpoints require the `ADMIN` role
- The `dsl.remote.token` is masked in all responses (shows only `"****{last4chars}"`)
- Each update records the authenticated user as the commit author
- The full preference JSON is committed for audit compliance

---

## Related Documentation

- [Configuration Reference](CONFIGURATION_REFERENCE.md) — Environment variables and startup configuration
- [Security](SECURITY.md) — Roles, authentication, and access control
- [Git Integration](GIT_INTEGRATION.md) — DSL version control and branching
- [AI Providers](AI_PROVIDERS.md) — LLM provider selection and configuration

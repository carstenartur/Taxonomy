# Preferences

The Preferences system provides **runtime-configurable settings** for the Taxonomy Architecture Analyzer. All changes are persisted in a dedicated JGit repository with a full audit trail — every update creates a Git commit recording who changed what and when.

## Table of Contents

- [Overview](#overview)
- [Accessing Preferences](#accessing-preferences)
- [Available Settings](#available-settings)
- [LLM Configuration](#llm-configuration)
- [DSL and Git Configuration](#dsl-and-git-configuration)
- [Size Limits](#size-limits)
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

## Accessing Preferences

Preferences are managed through the REST API. All endpoints require the **ADMIN** role.

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

## Available Settings

### LLM Configuration

| Key | Type | Default | Description |
|---|---|---|---|
| `llm.rpm` | int | `5` | Maximum API requests per minute (outgoing LLM throttle) |
| `llm.timeout.seconds` | int | `30` | HTTP read timeout for LLM API calls |
| `rate-limit.per-minute` | int | `10` | Incoming rate limit for analysis endpoints |
| `analysis.min-relevance-score` | int | `70` | Minimum score for nodes to appear in analysis results |

The `llm.rpm` setting controls the sliding-window throttle for outgoing LLM API calls. The system maintains a FIFO queue of timestamps and sleeps the thread if the rate limit would be exceeded. A 50ms grace period is added for clock drift.

The `llm.timeout.seconds` setting dynamically updates the `RestTemplate` read timeout without restarting the application.

### DSL and Git Configuration

| Key | Type | Default | Description |
|---|---|---|---|
| `dsl.default-branch` | string | `"draft"` | Active branch for DSL materialization |
| `dsl.project-name` | string | `"Taxonomy Architecture"` | Human-readable project display name |
| `dsl.auto-save.interval-seconds` | int | `0` | Auto-save frequency (0 = disabled) |
| `dsl.remote.url` | string | `""` | Remote Git URL for push/pull operations |
| `dsl.remote.token` | string | `""` | Authentication token for remote Git (masked in API responses) |
| `dsl.remote.push-on-commit` | boolean | `false` | Automatically push to remote after local commits |

> **Security note:** The `dsl.remote.token` value is masked in all API responses. When retrieved via `GET /api/preferences`, it appears as `"****{last4chars}"`. The full token is only used internally for Git remote operations.

### Size Limits

| Key | Type | Default | Description |
|---|---|---|---|
| `limits.max-business-text` | int | `5000` | Maximum characters in a business requirement text |
| `limits.max-architecture-nodes` | int | `50` | Maximum nodes displayed in the architecture view |
| `limits.max-export-nodes` | int | `200` | Maximum nodes included in an export operation |

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

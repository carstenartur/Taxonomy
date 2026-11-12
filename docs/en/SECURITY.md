# Security

This document describes the authentication, authorization, credential, and deployment-security model of Taxonomy Architecture Analyzer.

For confidential vulnerability disclosure, follow the repository-level [Security Policy](../../SECURITY.md). Do not publish suspected vulnerabilities or secrets in a public issue.

## Authentication modes

The application supports two mutually exclusive security modes.

### Local-user mode

This is the default when the `keycloak` Spring profile is not active.

- Browser users authenticate through Spring Security form login and a server-side session.
- REST clients may authenticate with HTTP Basic.
- Passwords are stored as BCrypt hashes in the application database.
- Browser state-changing requests remain protected by CSRF tokens.
- Only requests authenticated through supported stateless HTTP Basic or Keycloak Bearer credentials qualify for a CSRF exemption. Restored browser sessions retain CSRF protection; an ignored `Authorization` header does not grant an exemption.

### Keycloak/OIDC mode

Activate the `keycloak` Spring profile to delegate authentication to Keycloak.

- Browser authentication uses OAuth 2.0/OIDC login.
- REST clients use Bearer JWT access tokens.
- The resource server accepts Bearer credentials up to 8,192 characters by default. Set `TAXONOMY_BEARER_MAX_TOKEN_LENGTH` to a positive integer to change the limit. The same limit applies when an installation supplies a custom token resolver. A request that declares Bearer authentication receives HTTP 401 if the resolver returns no credential.
- Trusted role claims are mapped to the application's READER, USER (Analyst), ARCHITECT, and ADMIN authorities. Bearer tokens also retain explicit OAuth `scope`/`scp` authorities.
- Local password management is not the authoritative identity-management path in this profile.

See [Keycloak setup](KEYCLOAK_SETUP.md) for deployment details.

## Administrator bootstrap

The local-user profile creates the `admin` account on the first startup of a new database. There is **no built-in or documented reusable password**.

| Situation | Behaviour |
|---|---|
| `TAXONOMY_ADMIN_PASSWORD` is unset or blank in a non-production deployment | A high-entropy one-time bootstrap password is written to a uniquely named owner-only temporary file. Only its absolute path is logged. The account is marked for password replacement, and the file is removed after the successfully committed administrator password change. |
| `TAXONOMY_ADMIN_PASSWORD` is set | The configured value is used for initial account creation. `TAXONOMY_REQUIRE_PASSWORD_CHANGE` controls whether it must be replaced at first login. |
| A historical database still contains the removed `admin` credential | The account is marked for mandatory password replacement without re-locking normal accounts on every restart. |
| The `production` profile is active | Startup fails before account creation when the password is missing, is a known placeholder, or contains fewer than 16 characters. |

For local development with an explicit secret:

```bash
export TAXONOMY_ADMIN_PASSWORD='use-a-unique-local-development-secret'
./mvnw -pl taxonomy-app -am spring-boot:run
```

Read an automatically generated credential file only from the trusted host account that owns the application process. If the filesystem cannot enforce owner-only POSIX permissions or an owner-only ACL, administrator creation fails closed. Do not copy a password from documentation, tests, screenshots, or examples into a deployed environment.

## Roles and permissions

| Capability | Stored role / Spring authority | Intended permissions |
|---|---|---|
| Reader | `READER` / `ROLE_READER` | Read architecture and existing analyses; use permitted exports and authorized backup downloads. |
| Analyst | `USER` / `ROLE_USER` | Read and export; execute analyses and manage personal analysis drafts. `USER` remains the stored role name for compatibility. |
| Architect | `ARCHITECT` / `ROLE_ARCHITECT` | Read, analyse, create/change/delete business data, import and publish architecture changes. |
| Administrator | `ADMIN` / `ROLE_ADMIN` | Read/export and technical administration, user/session management and diagnostics. Administration alone grants neither analysis execution nor business editing. |

Roles are independent assignments. An administrator who edits architecture needs an explicit `ROLE_ARCHITECT`; an administrator who runs analyses needs `ROLE_USER` or `ROLE_ARCHITECT`. Existing multi-role accounts keep their assignments. The local bootstrap administrator explicitly receives `ROLE_USER`, `ROLE_ARCHITECT` and `ROLE_ADMIN`; that account's capabilities do not imply an ADMIN role hierarchy. Local startup seeds `ROLE_READER` idempotently, and the local user-management form offers all four roles.

For each permission-protected REST operation, Spring Security requires an allowed application role in **every required permission group**. A Bearer JWT additionally requires **every listed OAuth scope**: allowed role within each group AND all explicit scopes. A role-only token has no unscoped fallback; a scope never supplies a missing role. Local HTTP Basic and browser sessions use roles with their existing CSRF rules. If capability alternatives are listed, one complete alternative must match; every role group and scope within that alternative is required. Conditional requirements apply when their stated condition holds. Own-authentication/password endpoints and preflight follow the specific authenticated/public rules in the shared catalogue.

| OAuth scope | Allowed application roles |
|---|---|
| `architecture:read` | READER, USER, ARCHITECT, ADMIN |
| `architecture:write`, `architecture:delete`, `architecture:publish` | ARCHITECT |
| `analysis:read` | READER, USER, ARCHITECT, ADMIN |
| `analysis:run` | USER, ARCHITECT |
| `integration:import` | ARCHITECT |
| `integration:export` | READER, USER, ARCHITECT, ADMIN |
| `repository:manage` | ARCHITECT, ADMIN, plus OWNER on the exact repository |
| `backup:read` | READER, USER, ARCHITECT, ADMIN |
| `backup:create` | USER, ARCHITECT, ADMIN |
| `admin:users`, `admin:configuration`, `admin:diagnostics` | ADMIN |

Scopes classify complete endpoint operations, not individual changes within an edit. `architecture:delete` protects dedicated REST DELETE endpoints. `architecture:write` authorizes the full command/DSL edit operation, which can remove or replace content; it is not a semantic-diff policy. Read-only scopes still authorize no business edits.

Permission groups can be conjunctive: `POST /api/workspace/external/push` and `/api/workspace/external/full-sync` require both ARCHITECT with `architecture:publish` **and** ADMIN with `admin:configuration` for Bearer calls. Sessions require both roles. Neither group alone suffices.

HTTP method alone does not determine effect. `GET /api/analyze-stream` and `GET /api/analyze-node` start new LLM work and require ANALYSIS_RUN (USER or ARCHITECT; Bearer scope `analysis:run`). Lazy/default working-copy creation during `GET /api/workspace/current` also requires ANALYSIS_RUN. A reader without a ready working copy receives HTTP 204 and continues in the central read-only context; existing ready working copies remain readable subject to resource checks.

Workspace lifecycle routes use explicit permissions, with existing ownership and resource checks:

| Operation | Required permission (Bearer scope) |
|---|---|
| Create, archive, rename or update the description of an owned workspace; clear personal navigation history | `ANALYSIS_RUN` (`analysis:run`; USER or ARCHITECT) |
| Compare contexts, switch to an owned workspace or return to personal navigation history | `ARCHITECTURE_READ` (`architecture:read`) |
| Delete an owned workspace | `ARCHITECTURE_DELETE` (`architecture:delete`; ARCHITECT) |
| Administrative eviction of in-memory workspace state | `ADMIN_CONFIGURATION` (`admin:configuration`; ADMIN) |

Unclassified workspace writes are denied.

Retained project analysis jobs, snapshots, Copilot status and unadopted reformulation drafts use `analysis:read`. Continuing a job or managing an analysis draft uses `analysis:run`. Copilot/Autopilot start and explicit Copilot resumption can also enrich business data, so they require both `architecture:write` and `analysis:run`. Status GETs only observe saved work; they neither resume model calls nor promote results.

An import with `analyzeAfterImport=true` needs `analysis:run` in addition to its business-write permission, checked before any import is persisted. With the flag false, a write-scoped token can import without model-execution permission. Configured automatic analysis after requirement saves is skipped when execution permission is absent.

Artifact downloads, including editor SVG/PDF, portfolio DSL, saved reports and reformulation exports, require `integration:export`; format/capability metadata remains a separate read. Business integration publication requires `architecture:publish`. Retrying an integration uses the saved operation direction: INBOUND requires `integration:import`, OUTBOUND requires `integration:export`, and PUSH/SYNCHRONIZE requires `architecture:publish`. A different allowed retry scope cannot authorize another operation kind. Swagger shows these alternatives and conditional requirements explicitly.

Fresh readers and read-scoped tokens can inspect Git, context and projection diagnostics without creating workspace metadata, navigation state or projection records. Reads requiring an isolated working copy return 403 when no ready copy exists; explicit system provisioning remains available through its existing path.

Configured connector publication is a reviewed business action using server-configured profiles and credentials, with repository membership, workspace ownership and exact-branch checks. It does not change technical transport configuration. Workspace external push/full-sync retain their separate publication-and-administration conjunction.

Document AI extraction/regulation mapping and source/editor focus suggestions require `analysis:run`. Focus endpoints accept optional `useAi`; the complete operation therefore requires execution authority even for a deterministic request. Ordinary document upload/confirmation remains a business write. Diagram routes that reanalyse input (`/api/diagram/visio`, `/archimate`, `/mermaid`, `/structurizr` and `/export/{formatId}` under that prefix) require `analysis:run` AND `integration:export`; existing-view `/api/diagram/current/**` and score downloads need only `integration:export`.

An accepted explicit pass continuation also admits finalization of its retained Copilot operation when both business-write and analysis-execution capabilities are present. An analysis-only continuation cannot borrow business enrichment authority. Loading, polling and uncertain-command recovery remain observational and never repeat a command automatically.

`ApiPermission` and the ordered `ApiAccessPolicy` are the common source for server authorization and OpenAPI. The policy distinguishes reads (including read-only POST), analysis execution, changes/deletion/publication, import/export and administration. Unclassified writes are denied, including for administrators. Existing project/repository visibility, membership, lifecycle and personal-workspace ownership checks still apply. Neither a global role nor a scope grants access to someone else's private resources. Hiding a UI control is not an authorization boundary.

## Organizational groups and repository grants

Manage organizational groups, LDAP/AD federation and SAML federation at the identity provider. Map trusted group membership to explicit application roles there. Taxonomy does not maintain a separate local group database or interpret arbitrary group names as permissions.

A validated OIDC/JWT claim may additionally grant repository roles to the **currently authenticated actor**:

```json
{
  "taxonomy_repository_roles": {
    "repository-a": "READER",
    "repository-b": "CONTRIBUTOR"
  }
}
```

The claim must be an exact repository-ID-to-single-role map. Values are exactly `READER`, `CONTRIBUTOR`, `MAINTAINER` or `OWNER` (no `ROLE_` prefix or arrays); keys must be nonblank and have no surrounding whitespace. Keys are matched literally; wildcard expansion is not supported. A malformed entry causes the whole claim mapping to be ignored. Grants are considered alongside stored repository memberships, only for the exact repository and only when the evaluated actor equals the authenticated principal's name. Request parameters and local-login attributes cannot supply these grants. A repository grant does not supply an application role, OAuth scope or ownership of another user's workspace. Existing public/primary-repository compatibility rules remain in effect.

## Swagger and API access descriptions

Swagger groups operations by their functional tags and displays an access table for each operation: all required permission groups, allowed roles within each group, all required Bearer scopes, operation effect, resource restrictions, and documented HTTP 401/403 responses. These descriptions derive from the shared access policy. OpenAPI advertises only the authentication schemes active in the deployment: local Basic/session authentication or OIDC OAuth/Bearer authentication with `sessionCookie` as an alternative in the OIDC profile too. Existing OIDC sessions use application roles and CSRF protection for browser writes; JWT calls require roles and all listed scopes. Missing or invalid credentials return 401; an authenticated caller lacking the required capability, scope or resource permission receives 403.

In Keycloak mode, Swagger uses a separate public `taxonomy-swagger` client with Authorization Code and PKCE. It has no browser client secret. The confidential `taxonomy-app` client remains the server-side session-login client. Service accounts use a separate confidential client with Client Credentials and explicit roles, scopes and resource grants. See [Keycloak setup](KEYCLOAK_SETUP.md) and the [upgrade steps](KEYCLOAK_MIGRATION.md) before deploying this access model.

## CSRF and API clients

Browser sessions retain CSRF protection, including for state-changing application API calls. JavaScript clients rendered by the application use the CSRF token supplied by the page.

A REST request is considered stateless only when both conditions are true:

1. its path starts with `/api/`; and
2. it carries an explicit `Authorization: Basic ...` or `Authorization: Bearer ...` header.

The absence of a session alone does not disable CSRF protection.

## Password and login protection

The local-user profile provides:

- BCrypt password hashing;
- configurable failed-login rate limiting;
- temporary IP lockout after repeated failures;
- first-login password replacement for bootstrap accounts;
- protection against disabling the last administrator;
- audit logging when enabled.

Relevant settings include:

| Environment variable | Purpose |
|---|---|
| `TAXONOMY_ADMIN_PASSWORD` | Initial local administrator credential. Required and validated in production. |
| `TAXONOMY_REQUIRE_PASSWORD_CHANGE` | Require replacement of an explicitly configured initial password. |
| `TAXONOMY_LOGIN_RATE_LIMIT` | Enable failed-login rate limiting. |
| `TAXONOMY_LOGIN_MAX_ATTEMPTS` | Failures permitted before lockout. |
| `TAXONOMY_LOGIN_LOCKOUT_SECONDS` | Lockout duration. |
| `TAXONOMY_AUDIT_LOGGING` | Enable security event logging. |
| `TAXONOMY_SWAGGER_PUBLIC` | Permit unauthenticated Swagger access when SpringDoc is enabled. |

Mandatory password replacement is servlet-context aware. A root deployment uses `/change-password` and `/api/account/change-password`; a deployment below `/taxonomy` uses `/taxonomy/change-password` and `/taxonomy/api/account/change-password`. Login, logout, error handling, the replacement page and API, CSS, JavaScript, images, and webjars remain reachable while the account is restricted.

A restricted API request returns HTTP `428 Precondition Required`, `Cache-Control: no-store`, error code `PASSWORD_CHANGE_REQUIRED`, and the replacement endpoint inside the active context path. The enforcement filter is registered exactly once in the local-user Spring Security chain; ordinary servlet registration is disabled. Keycloak continues to own this lifecycle when its profile is active.

## External Git credentials

External canonical-repository credentials are deployment secrets, not repository data.

| Environment variable | Purpose |
|---|---|
| `TAXONOMY_EXTERNAL_GIT_USERNAME` | Transport username; defaults to `oauth2` when omitted. |
| `TAXONOMY_EXTERNAL_GIT_TOKEN` | Access token supplied to JGit transport. |

The token is not stored in JPA entities, returned by status APIs, or written to logs. Historical plaintext database values are removed during startup, and cleanup failures stop startup rather than silently retaining the secret.

Repository URLs containing embedded HTTP credentials or passwords are rejected. Store the remote URL and credential separately.

## Security headers

The local-user security chain configures:

- `X-Content-Type-Options: nosniff`;
- same-origin frame protection;
- HTTP Strict Transport Security;
- `Referrer-Policy: strict-origin-when-cross-origin`.

HSTS is effective only when clients reach the deployment through HTTPS.

## Production requirements

Use the supported production composition and review the [Deployment Checklist](DEPLOYMENT_CHECKLIST.md). At minimum:

- terminate TLS through a trusted reverse proxy such as Caddy;
- keep the application port, database, Git storage, Lucene index, and backups off the public network;
- provide unique secrets through deployment secret storage;
- disable or authenticate Swagger unless explicitly required;
- enable and retain appropriate authentication and audit logs;
- review the generated SBOM, vulnerability scan, and release provenance;
- test restoration of database, Git repository, index, and configuration backups;
- use Keycloak or separately managed user accounts instead of sharing an administrator login.

The production profile deliberately fails closed for unsafe bootstrap credentials.

## Public endpoints

Health and static-resource exposure is controlled by the active security configuration and deployment profile. Do not assume that an endpoint is safe merely because it is reachable in a development configuration. In particular, review actuator and OpenAPI exposure before deployment.

## Security verification

The canonical Maven build exercises authentication, authorization, password bootstrap and migration, production hardening, container startup, persistence, browser workflows, accessibility, and selected failure paths:

```bash
./mvnw verify -Pci -DrunOnnxTests=true
```

The repository also runs CodeQL and Trivy-based dependency, secret, and configuration checks. These controls reduce risk but do not replace threat modelling, deployment review, penetration testing, or incident response.

## Related documentation

- [Repository Security Policy](../../SECURITY.md)
- [Deployment Guide](DEPLOYMENT_GUIDE.md)
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md)
- [Configuration Reference](CONFIGURATION_REFERENCE.md)
- [Data Protection](DATA_PROTECTION.md)
- [Keycloak Setup](KEYCLOAK_SETUP.md)

<a id="user-management"></a>
## Local user management

Administrators can create local accounts, edit their roles, enable or disable accounts and reset passwords in **User management**. The confirmation forms show the affected account before a change is applied. In Keycloak/OIDC mode, manage external identities in the identity provider.

<a id="browser-sessions"></a>
## Signed-in sessions

Administrators can open the session inventory to see registered, non-expired sessions for this application instance, grouped by user. **Refresh** obtains a new measurement. The timestamp is shown in UTC. A registered session does not prove that someone is currently viewing a page; the inventory is not a cluster-wide presence indicator.

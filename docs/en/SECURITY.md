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
- An `/api/**` request is exempt from CSRF only when it carries an explicit Basic or Bearer `Authorization` header and is therefore treated as a stateless API request.

### Keycloak/OIDC mode

Activate the `keycloak` Spring profile to delegate authentication to Keycloak.

- Browser authentication uses OAuth 2.0/OIDC login.
- REST clients use Bearer JWT access tokens.
- Realm roles are mapped to the application's USER, ARCHITECT, and ADMIN authorities.
- Local password management is not the authoritative identity-management path in this profile.

See [Keycloak setup](KEYCLOAK_SETUP.md) for deployment details.

## Administrator bootstrap

The local-user profile creates the `admin` account on the first startup of a new database. There is **no built-in or documented reusable password**.

| Situation | Behaviour |
|---|---|
| `TAXONOMY_ADMIN_PASSWORD` is unset or blank in a non-production deployment | A high-entropy one-time bootstrap password is generated and printed once to the startup log. The account is marked for password replacement. |
| `TAXONOMY_ADMIN_PASSWORD` is set | The configured value is used for initial account creation. `TAXONOMY_REQUIRE_PASSWORD_CHANGE` controls whether it must be replaced at first login. |
| A historical database still contains the removed `admin` credential | The account is marked for mandatory password replacement without re-locking normal accounts on every restart. |
| The `production` profile is active | Startup fails before account creation when the password is missing, is a known placeholder, or contains fewer than 16 characters. |

For local development with an explicit secret:

```bash
export TAXONOMY_ADMIN_PASSWORD='use-a-unique-local-development-secret'
./mvnw -pl taxonomy-app -am spring-boot:run
```

Do not copy a password from documentation, tests, screenshots, or examples into a deployed environment.

## Roles and permissions

| Role | Intended permissions |
|---|---|
| `USER` | Browse, search, analyse, inspect graphs, and use permitted exports. |
| `ARCHITECT` | USER permissions plus architecture, relation, DSL, and versioning mutations. |
| `ADMIN` | ARCHITECT permissions plus administrative configuration, diagnostics, and user management. |

Authorization is enforced by Spring Security and method-level checks. Hiding a control in the UI is not treated as an authorization boundary.

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

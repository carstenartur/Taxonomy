# Security

This document describes the authentication, authorization, and security architecture of the Taxonomy Architecture Analyzer.

---

## Authentication

The application uses **Spring Security** with two authentication methods:

| Method | Used by | Session |
|---|---|---|
| **Form login** | Browser users (GUI) | Server-side session with CSRF protection |
| **HTTP Basic** | REST clients (curl, scripts, CI) | Stateless; no CSRF token required |

Both methods authenticate against the same user database (JPA-backed, BCrypt-hashed passwords).

### Default User

A default admin user is created on first startup:

| Username | Password | Roles |
|---|---|---|
| `admin` | Value of `TAXONOMY_ADMIN_PASSWORD` (default: `admin`) | USER, ARCHITECT, ADMIN |

> **Change the default password** before exposing the application to any network. Set the `TAXONOMY_ADMIN_PASSWORD` environment variable or the Spring property `taxonomy.admin-password`.

---

## Roles and Permissions

Three roles control access to features:

| Role | What you can do |
|---|---|
| **USER** | Browse taxonomy, run analysis, search, view graph, export diagrams, view proposals |
| **ARCHITECT** | Everything in USER, plus: create/edit/delete relations, commit DSL, manage Git branches |
| **ADMIN** | Everything in ARCHITECT, plus: admin endpoints, LLM diagnostics, system configuration |

### Endpoint Access Matrix

| Endpoint Pattern | USER | ARCHITECT | ADMIN |
|---|:---:|:---:|:---:|
| `GET /api/**` (read) | ✅ | ✅ | ✅ |
| `POST /api/analyze`, `POST /api/justify-leaf` | ✅ | ✅ | ✅ |
| `POST /api/export/**` | ✅ | ✅ | ✅ |
| `POST/PUT/DELETE /api/relations/**` | ❌ | ✅ | ✅ |
| `POST/PUT/DELETE /api/dsl/**` | ❌ | ✅ | ✅ |
| `POST/PUT/DELETE /api/git/**` | ❌ | ✅ | ✅ |
| `/admin/**`, `/api/admin/**` | ❌ | ❌ | ✅ |

---

## GUI vs REST Security

### Browser (GUI)

- Users authenticate via the Spring Security **login page** (`/login`).
- Sessions are protected by **CSRF tokens** (automatically handled by the Thymeleaf templates).
- The admin panel (LLM diagnostics, prompt template editor) requires the `ADMIN` role and additionally requires unlocking via the 🔒 button in the navigation bar (using the `ADMIN_PASSWORD` token).

### REST API

- Clients authenticate via **HTTP Basic** (`-u username:password`).
- CSRF is **disabled** for all `/api/**` paths, so no token exchange is needed.
- Example:
  ```bash
  curl -u admin:admin http://localhost:8080/api/taxonomy
  ```

---

## Public Endpoints

These endpoints do **not** require authentication:

| Endpoint | Purpose |
|---|---|
| `/login`, `/error` | Login page and error page |
| `/actuator/health`, `/actuator/info` | Health checks (for load balancers, Docker) |
| `/v3/api-docs/**`, `/swagger-ui/**` | OpenAPI documentation |
| `/css/**`, `/js/**`, `/images/**`, `/webjars/**` | Static assets |

---

## Admin Panel Access

The admin panel provides LLM diagnostics, prompt template editing, and communication logs. It is protected by two layers:

1. **Role-based**: Only users with `ROLE_ADMIN` can access `/admin/**` and `/api/admin/**`.
2. **Token-based**: In the GUI, the admin panel additionally requires entering the `ADMIN_PASSWORD` via the 🔒 lock button. This provides a second factor for shared environments where multiple users may have the same admin account.

| Variable | Purpose |
|---|---|
| `TAXONOMY_ADMIN_PASSWORD` | Spring Security login password for the `admin` user |
| `ADMIN_PASSWORD` | Token for unlocking admin panels in the GUI |

> **Important:** These are two separate passwords. `TAXONOMY_ADMIN_PASSWORD` controls login authentication. `ADMIN_PASSWORD` controls access to admin-only UI panels.

---

## Deployment Security Checklist

### Local Development

For local development, the defaults are fine:

```bash
mvn spring-boot:run   # admin/admin, no admin panel password
```

### Shared or Exposed Deployment

For any deployment accessible by others:

1. **Set `TAXONOMY_ADMIN_PASSWORD`** to a strong password:
   ```bash
   TAXONOMY_ADMIN_PASSWORD=strong-password-here
   ```

2. **Set `ADMIN_PASSWORD`** to protect admin panels:
   ```bash
   ADMIN_PASSWORD=admin-panel-secret
   ```

3. **Use HTTPS** — configure a reverse proxy (nginx, Caddy, or cloud load balancer) with TLS termination.

4. **Restrict Swagger UI** in production — set `TAXONOMY_SPRINGDOC_ENABLED=false` to disable OpenAPI documentation.

5. **Review actuator exposure** — by default only `/actuator/health` and `/actuator/info` are public. Do not expose additional actuator endpoints without authentication.

### Docker

```bash
docker run -p 8080:8080 \
  -e TAXONOMY_ADMIN_PASSWORD=strong-password \
  -e ADMIN_PASSWORD=admin-panel-secret \
  -e GEMINI_API_KEY=your-key \
  ghcr.io/carstenartur/taxonomy:latest
```

---

## CSRF Protection

| Path | CSRF |
|---|---|
| `/api/**` | **Disabled** — REST clients use HTTP Basic, no browser session |
| All other paths | **Enabled** — browser sessions include CSRF tokens via Thymeleaf meta tags |

The browser-based JavaScript (`fetch()` calls to `/api/**`) includes CSRF tokens from meta tags, but they are ignored by the server for these paths. This is intentional — the HTTP Basic authentication on the API paths is sufficient for security.

---

## Brute-Force Protection

The `LoginRateLimitFilter` protects against brute-force login attacks by tracking failed authentication attempts per IP address. After a configurable number of failures, the IP is locked out for a configurable duration.

| Setting | Environment Variable | Default | Description |
|---|---|---|---|
| Enabled | `TAXONOMY_LOGIN_RATE_LIMIT` | `true` | Enable/disable login rate limiting |
| Max attempts | `TAXONOMY_LOGIN_MAX_ATTEMPTS` | `5` | Failed attempts before lockout |
| Lockout duration | `TAXONOMY_LOGIN_LOCKOUT_SECONDS` | `300` | Lockout duration in seconds (5 minutes) |

**Behavior:**
- Applies to `POST /login` (form login) and `/api/**` (HTTP Basic returning 401)
- Returns HTTP 423 (Locked) with a JSON body when an IP is locked out
- Lockout expires automatically after the configured duration
- Successful login clears the failure counter for that IP

Disable with `TAXONOMY_LOGIN_RATE_LIMIT=false` for development or testing.

---

## Security Headers

The application sends the following security headers on all responses:

| Header | Value | Purpose |
|---|---|---|
| `X-Content-Type-Options` | `nosniff` | Prevents MIME type sniffing |
| `X-Frame-Options` | `SAMEORIGIN` | Prevents clickjacking via iframes |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | Enforces HTTPS (HSTS) |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | Controls referrer information |

These headers are always enabled and cannot be disabled.

---

## Swagger Access Control

Swagger UI and OpenAPI documentation access can be controlled independently of whether SpringDoc is enabled:

| Variable | Default | Description |
|---|---|---|
| `TAXONOMY_SPRINGDOC_ENABLED` | `true` | Enable/disable SpringDoc entirely |
| `TAXONOMY_SWAGGER_PUBLIC` | `true` | Allow unauthenticated access to Swagger UI |

- **Development** (default): Swagger UI is publicly accessible (`TAXONOMY_SWAGGER_PUBLIC=true`)
- **Production**: Set `TAXONOMY_SWAGGER_PUBLIC=false` to require authentication, or `TAXONOMY_SPRINGDOC_ENABLED=false` to disable entirely

---

## First-Time Password Change

The default admin password (`admin`) triggers a startup warning:

```
SECURITY WARNING: Default admin password 'admin' is in use. Set TAXONOMY_ADMIN_PASSWORD environment variable to change it.
```

To enforce password changes, set `TAXONOMY_REQUIRE_PASSWORD_CHANGE=true`. When enabled:
- GUI users with the default password are redirected to `/change-password`
- The change-password form requires the current password, a new password (minimum 8 characters), and confirmation

Authenticated users can always change their password at `/change-password`.

---

## Managing Users

The User Management API allows administrators to create, update, and disable users via REST endpoints.

### Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/admin/users` | List all users (without password hashes) |
| `GET` | `/api/admin/users/{id}` | Get a specific user |
| `POST` | `/api/admin/users` | Create a new user |
| `PUT` | `/api/admin/users/{id}` | Update user details (roles, displayName, email, enabled) |
| `PUT` | `/api/admin/users/{id}/password` | Change a user's password |
| `DELETE` | `/api/admin/users/{id}` | Disable a user (soft delete) |

All endpoints require `ROLE_ADMIN`. See the [API Reference](API_REFERENCE.md) for request/response details.

### Safety Rules

- The last remaining admin user cannot be disabled
- The ADMIN role cannot be removed from the last admin user
- Usernames must be unique
- Passwords must be at least 8 characters

---

## Audit Logging

Security audit logging records authentication events for compliance and forensics. Disabled by default.

| Variable | Default | Description |
|---|---|---|
| `TAXONOMY_AUDIT_LOGGING` | `false` | Enable security audit logging |

When enabled, the following events are logged:

| Event | Log Level | Format |
|---|---|---|
| Successful login | `INFO` | `LOGIN_SUCCESS user={username} ip={ip}` |
| Failed login | `WARN` | `LOGIN_FAILED user={username} ip={ip}` |
| User created | `INFO` | `USER_CREATED user={username} roles={roles} by={admin}` |
| User updated | `INFO` | `USER_UPDATED user={username} by={admin}` |
| User disabled | `INFO` | `USER_DISABLED user={username} by={admin}` |
| Password changed | `INFO` | `USER_PASSWORD_CHANGED user={username} by={admin}` |

Enable with `TAXONOMY_AUDIT_LOGGING=true` for production or compliance environments.

---

## Security Environment Variables Summary

All security-related environment variables in one place:

| Variable | Default | Description |
|---|---|---|
| `TAXONOMY_ADMIN_PASSWORD` | `admin` | Login password for the default admin user |
| `ADMIN_PASSWORD` | *(empty)* | Token for the UI admin panel protection layer |
| `TAXONOMY_LOGIN_RATE_LIMIT` | `true` | Enable brute-force protection on `/login` |
| `TAXONOMY_LOGIN_MAX_ATTEMPTS` | `5` | Failed attempts before lockout |
| `TAXONOMY_LOGIN_LOCKOUT_SECONDS` | `300` | Lockout duration (5 minutes) |
| `TAXONOMY_REQUIRE_PASSWORD_CHANGE` | `false` | Force password change on first login |
| `TAXONOMY_SWAGGER_PUBLIC` | `true` | Allow unauthenticated Swagger UI access |
| `TAXONOMY_AUDIT_LOGGING` | `false` | Log security events to application log |

> ⚠️ **For production deployments**, always change `TAXONOMY_ADMIN_PASSWORD` from the default, set `TAXONOMY_SWAGGER_PUBLIC=false`, and enable `TAXONOMY_AUDIT_LOGGING=true`.

---

## Password Change Flow

The password change mechanism is available at `POST /change-password`:

1. User submits current password, new password, and confirmation
2. Server validates current password matches
3. Server validates new password meets requirements
4. Password is updated in the database
5. If `TAXONOMY_REQUIRE_PASSWORD_CHANGE=true`, the first-login flag is cleared

The `ChangePasswordController` handles the flow and redirects back to the main page on success.

---

## Keycloak / OIDC Integration (Planned)

For enterprise and government deployments, the application supports integration with **Keycloak** as an external identity provider via OpenID Connect (OIDC):

- **Keycloak as Identity Broker** — federates with government SAML/OIDC identity providers
- **JWT-based API authentication** — replaces HTTP Basic for REST clients
- **Centralized user management** — no separate user database required
- **SSO/SLO support** — Single Sign-On and Single Logout across applications

The existing three-role model (USER, ARCHITECT, ADMIN) maps directly to Keycloak realm roles.

See [Keycloak Setup](KEYCLOAK_SETUP.md) and [SSO Integration](SSO_INTEGRATION.md) for configuration details.

---

## Network Topology (Production)

Recommended production deployment with defense-in-depth:

```
┌──────────────────────────────────────────────────────────────┐
│  DMZ                                                          │
│  ┌────────────────┐                                           │
│  │  Reverse Proxy  │  ← TLS termination, rate limiting        │
│  │  (nginx/Caddy)  │  ← WAF rules (optional)                  │
│  └───────┬────────┘                                           │
│          │ :8080 (plaintext, internal only)                    │
├──────────┼────────────────────────────────────────────────────┤
│  Application Zone                                              │
│  ┌───────▼────────┐     ┌──────────────┐                      │
│  │  Taxonomy App   │────▶│  Keycloak     │  ← OIDC/SAML       │
│  │  (Spring Boot)  │     │  (optional)   │                     │
│  └───────┬────────┘     └──────┬───────┘                      │
│          │                      │                              │
├──────────┼──────────────────────┼─────────────────────────────┤
│  Data Zone (no direct external access)                         │
│  ┌───────▼────────┐     ┌──────▼───────┐                      │
│  │  PostgreSQL     │     │  LDAP / AD    │  ← User directory   │
│  │  (Data Store)   │     │  (optional)   │                     │
│  └────────────────┘     └──────────────┘                      │
└──────────────────────────────────────────────────────────────┘
```

### Network Rules

| Source | Destination | Port | Protocol | Purpose |
|---|---|---|---|---|
| Internet | Reverse Proxy | 443 | HTTPS | User access |
| Reverse Proxy | Taxonomy App | 8080 | HTTP | Proxied requests |
| Taxonomy App | PostgreSQL | 5432 | TCP | Database |
| Taxonomy App | Keycloak | 8180 | HTTP/HTTPS | OIDC token validation |
| Taxonomy App | LLM API | 443 | HTTPS | AI analysis (optional) |
| Keycloak | LDAP/AD | 389/636 | LDAP/LDAPS | User federation |

---

## Hardening Measures

### Application Hardening

| Measure | Status | Configuration |
|---|---|---|
| **HTTPS enforced** | ✅ Via reverse proxy | HSTS header sent by application |
| **CSRF protection** | ✅ Default enabled | Thymeleaf auto-inserts tokens |
| **Brute-force protection** | ✅ Default enabled | `TAXONOMY_LOGIN_RATE_LIMIT=true` |
| **Password hashing** | ✅ BCrypt | Spring Security default |
| **Security headers** | ✅ Always enabled | X-Content-Type-Options, X-Frame-Options, HSTS, Referrer-Policy |
| **Input validation** | ✅ Size limits | `TAXONOMY_LIMITS_MAX_BUSINESS_TEXT=5000` |
| **Audit logging** | ✅ Production profile | `TAXONOMY_AUDIT_LOGGING=true` |
| **Swagger disabled** | ✅ Production profile | `TAXONOMY_SPRINGDOC_ENABLED=false` |
| **Admin panel protection** | ✅ Dual-layer | Role-based + token-based |

### JVM Hardening

```bash
# Recommended JAVA_OPTS for production
JAVA_OPTS="-XX:+UseG1GC \
  -XX:MaxRAMPercentage=75.0 \
  -Djava.security.egd=file:/dev/urandom \
  -Dfile.encoding=UTF-8"
```

### Container Hardening

```dockerfile
# Run as non-root user
RUN addgroup -S taxonomy && adduser -S taxonomy -G taxonomy
USER taxonomy

# Read-only filesystem (data volume is writable)
# Use: docker run --read-only --tmpfs /tmp -v taxonomy-data:/app/data
```

### Database Hardening

- Use dedicated database user with minimum privileges (SELECT, INSERT, UPDATE, DELETE on application tables)
- Enable TLS for database connections
- Restrict network access to the database server
- Enable database audit logging

---

## SBOM (Software Bill of Materials)

A CycloneDX SBOM is generated automatically during the build:

```bash
mvn package
# Output: target/taxonomy-sbom.json and target/taxonomy-sbom.xml
```

The SBOM lists all direct and transitive dependencies with:
- Package name and version
- License information
- Cryptographic hashes

Review the SBOM as part of the BSI IT-Grundschutz software supply chain requirements.

---

## Related Documentation

- [Configuration Reference](CONFIGURATION_REFERENCE.md) — full list of environment variables including security settings
- [Deployment Guide](DEPLOYMENT_GUIDE.md) — Docker and Render.com deployment with security considerations
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) — government deployment checklist
- [Keycloak Setup](KEYCLOAK_SETUP.md) — Keycloak integration guide
- [SSO Integration](SSO_INTEGRATION.md) — SAML/OIDC federation for government SSO
- [Data Protection](DATA_PROTECTION.md) — GDPR/DSGVO documentation
- [AI Transparency](AI_TRANSPARENCY.md) — AI model transparency and data flows
- [Operations Guide](OPERATIONS_GUIDE.md) — monitoring, backup, recovery
- [API Reference](API_REFERENCE.md) — endpoint authentication requirements
- [Preferences](PREFERENCES.md) — runtime configuration with audit trail

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

## Related Documentation

- [Configuration Reference](CONFIGURATION_REFERENCE.md) — full list of environment variables including security settings
- [Deployment Guide](DEPLOYMENT_GUIDE.md) — Docker and Render.com deployment with security considerations
- [API Reference](API_REFERENCE.md) — endpoint authentication requirements

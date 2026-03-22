# Migrating from Form Login to Keycloak

This guide describes how to migrate from the default form-login authentication mode to Keycloak/OIDC authentication.

---

## Quick Start

1. **Start Keycloak** (includes pre-configured realm with roles and users):
   ```bash
   docker compose -f docker-compose-keycloak.yml up -d
   ```

2. **Access the application**: http://localhost:8080 → Redirects to Keycloak login page

3. **Default users** (created by realm import):
   | Username | Password | Roles |
   |---|---|---|
   | `admin` | `admin` | USER, ARCHITECT, ADMIN |
   | `architect` | `architect` | USER, ARCHITECT |
   | `user` | `user` | USER |

---

## What Changes When the Keycloak Profile Is Active

| Feature | Form Login (default) | Keycloak Mode |
|---|---|---|
| Login page | Spring default `/login` form | Keycloak login page (OIDC redirect) |
| API authentication | HTTP Basic (`curl -u admin:admin`) | JWT Bearer Token (`Authorization: Bearer <token>`) |
| User management | `/api/admin/users` REST API | Keycloak Admin Console |
| Password changes | `/change-password` page | Keycloak Account Console |
| User database | Local (HSQLDB/PostgreSQL) | Keycloak (no local user DB) |
| Brute-force protection | `LoginRateLimitFilter` | Keycloak built-in protection |
| Default admin user | Created by `SecurityDataInitializer` | Created in Keycloak realm |

---

## Standalone Deployment (Without Docker Compose)

Set the following environment variables:

```bash
# Activate the Keycloak profile
export SPRING_PROFILES_ACTIVE=keycloak

# Keycloak realm configuration
export KEYCLOAK_ISSUER_URI=http://your-keycloak-host:8180/realms/taxonomy
export KEYCLOAK_JWK_SET_URI=http://your-keycloak-host:8180/realms/taxonomy/protocol/openid-connect/certs
export KEYCLOAK_CLIENT_ID=taxonomy-app
export KEYCLOAK_CLIENT_SECRET=your-client-secret

# Optional: Keycloak Admin Console URL (for password change redirects)
export KEYCLOAK_ADMIN_URL=http://your-keycloak-host:8180
```

---

## Mapping Existing Users

If migrating from form-login to Keycloak, create matching users in the Keycloak realm with the same usernames and roles:

1. Open Keycloak Admin Console → Realm: `taxonomy` → Users
2. Create each user with the same username
3. Assign realm roles: `ROLE_USER`, `ROLE_ARCHITECT`, `ROLE_ADMIN` as appropriate
4. Set initial passwords

**Important:** Keycloak usernames must match the usernames used in existing workspace data. The workspace system resolves the authenticated username via `Authentication.getName()`, which maps to the Keycloak `preferred_username` claim.

---

## REST API Authentication with JWT

In Keycloak mode, REST API clients authenticate with JWT Bearer tokens instead of HTTP Basic:

```bash
# 1. Obtain a token from Keycloak
TOKEN=$(curl -s -X POST \
  "http://localhost:8180/realms/taxonomy/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=taxonomy-app" \
  -d "client_secret=taxonomy-dev-secret" \
  -d "username=admin" \
  -d "password=admin" \
  -d "grant_type=password" \
  | jq -r '.access_token')

# 2. Use the token in API calls
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/taxonomy
```

---

## Reverting to Form Login

To switch back to form login, simply remove the `keycloak` profile:

```bash
# Remove keycloak from SPRING_PROFILES_ACTIVE
export SPRING_PROFILES_ACTIVE=hsqldb  # or postgres, etc.
```

The local user database and form-login components are automatically re-enabled when the `keycloak` profile is not active.

# Keycloak Setup

This document describes how to integrate the Taxonomy Architecture Analyzer with Keycloak for enterprise-grade identity management.

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Quick Start with Docker Compose](#quick-start-with-docker-compose)
4. [Keycloak Realm Configuration](#keycloak-realm-configuration)
5. [Application Configuration](#application-configuration)
6. [Role Mapping](#role-mapping)
7. [User Federation](#user-federation)
8. [Testing the Integration](#testing-the-integration)
9. [Production Considerations](#production-considerations)

---

## Overview

Keycloak provides centralized identity management with support for:

- **OpenID Connect (OIDC)** — modern token-based authentication
- **SAML 2.0** — federation with government identity providers
- **LDAP/AD integration** — existing directory services
- **Multi-factor authentication (MFA)** — hardware tokens, TOTP
- **Single Sign-On (SSO)** — across multiple applications

The integration replaces the built-in Spring Security form login with Keycloak-managed authentication while preserving the existing three-role authorization model (USER, ARCHITECT, ADMIN).

---

## Architecture

```
┌─────────────┐     OIDC / JWT      ┌──────────────┐
│  Browser     │ ◀─────────────────▶ │  Keycloak     │
│  (User)      │                     │  Server       │
└──────┬──────┘                     └──────┬───────┘
       │                                    │
       │  Authenticated request             │  Token validation
       │  (JWT Bearer token)                │  (JWKS endpoint)
       ▼                                    ▼
┌──────────────────────────────────────────────────┐
│  Taxonomy Architecture Analyzer                   │
│  (Spring Security + OAuth2 Resource Server)        │
│                                                    │
│  SecurityConfig:                                   │
│  - Validates JWT from Keycloak                     │
│  - Maps Keycloak roles to Spring roles             │
│  - Preserves RBAC (USER, ARCHITECT, ADMIN)         │
└──────────────────────────────────────────────────┘
```

---

## Quick Start with Docker Compose

Use `docker-compose-keycloak.yml` to start a development environment with Keycloak:

```bash
docker compose -f docker-compose-keycloak.yml up -d
```

This starts:

| Service | URL | Credentials |
|---|---|---|
| **Taxonomy Analyzer** | http://localhost:8080 | Keycloak-managed |
| **Keycloak** | http://localhost:8180 | admin / admin |
| **PostgreSQL** | localhost:5432 | taxonomy / taxonomy |

---

## Keycloak Realm Configuration

### 1. Create the Realm

1. Log in to Keycloak Admin Console: http://localhost:8180
2. Create a new realm: **taxonomy**

### 2. Create the Client

| Setting | Value |
|---|---|
| **Client ID** | `taxonomy-app` |
| **Client Protocol** | openid-connect |
| **Access Type** | confidential |
| **Root URL** | `http://localhost:8080` |
| **Valid Redirect URIs** | `http://localhost:8080/*` |
| **Web Origins** | `http://localhost:8080` |

### 3. Create Realm Roles

Create three roles matching the application's authorization model:

| Role | Description |
|---|---|
| `ROLE_USER` | Browse taxonomy, run analysis, search, export |
| `ROLE_ARCHITECT` | Create/edit relations, commit DSL, manage branches |
| `ROLE_ADMIN` | Admin endpoints, LLM diagnostics, user management |

### 4. Create Users

| Username | Roles | Purpose |
|---|---|---|
| `admin` | ROLE_USER, ROLE_ARCHITECT, ROLE_ADMIN | Full administrator |
| `architect` | ROLE_USER, ROLE_ARCHITECT | Architecture team |
| `analyst` | ROLE_USER | Read-only analyst |

### 5. Configure Client Scopes (Role Mapping in JWT)

Ensure realm roles are included in the JWT token:

1. Go to **Client Scopes** → **roles** → **Mappers**
2. Verify **realm roles** mapper is configured
3. Token Claim Name: `realm_access.roles`
4. Add to ID token: Yes
5. Add to access token: Yes

---

## Application Configuration

### Environment Variables for Keycloak

```bash
# Enable OAuth2/OIDC authentication
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=http://keycloak:8180/realms/taxonomy
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://keycloak:8180/realms/taxonomy/protocol/openid-connect/certs

# Client credentials (for token exchange / service-to-service)
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_ID=taxonomy-app
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_SECRET=<client-secret-from-keycloak>
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_ISSUER_URI=http://keycloak:8180/realms/taxonomy
```

### Spring Security Configuration Changes

The current `SecurityConfig` uses form login and HTTP Basic. To switch to Keycloak:

1. Add `spring-boot-starter-oauth2-resource-server` dependency
2. Configure JWT validation in `SecurityConfig`
3. Map Keycloak realm roles to Spring Security authorities
4. Keep HTTP Basic as fallback for REST clients (optional)

**Example SecurityConfig adjustment:**

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/relations/**").hasRole("ARCHITECT")
            .requestMatchers(HttpMethod.PUT, "/api/relations/**").hasRole("ARCHITECT")
            .requestMatchers(HttpMethod.DELETE, "/api/relations/**").hasRole("ARCHITECT")
            .requestMatchers("/api/**").authenticated()
            .requestMatchers("/actuator/health/**").permitAll()
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt
                .jwtAuthenticationConverter(keycloakJwtConverter())
            )
        );
    return http.build();
}

private JwtAuthenticationConverter keycloakJwtConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        Collection<String> roles = (Collection<String>) realmAccess.get("roles");
        return roles.stream()
            .filter(role -> role.startsWith("ROLE_"))
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toList());
    });
    return converter;
}
```

> **Note:** This is a reference implementation for the planned Keycloak migration (Phase 2). The current codebase uses form login + HTTP Basic.

---

## Role Mapping

The application's three roles map directly to Keycloak realm roles:

| Application Role | Keycloak Realm Role | Spring Authority |
|---|---|---|
| USER | `ROLE_USER` | `ROLE_USER` |
| ARCHITECT | `ROLE_ARCHITECT` | `ROLE_ARCHITECT` |
| ADMIN | `ROLE_ADMIN` | `ROLE_ADMIN` |

### Composite Roles (Optional)

Create composite roles in Keycloak for convenience:

| Composite Role | Includes |
|---|---|
| `architect` | ROLE_USER + ROLE_ARCHITECT |
| `admin` | ROLE_USER + ROLE_ARCHITECT + ROLE_ADMIN |

---

## User Federation

### LDAP / Active Directory

Keycloak can federate users from LDAP or Active Directory:

1. Go to **User Federation** → **Add provider** → **ldap**
2. Configure connection:
   - **Connection URL:** `ldap://ldap.example.gov:389`
   - **Users DN:** `ou=users,dc=example,dc=gov`
   - **Bind DN:** `cn=service-account,dc=example,dc=gov`
3. Map LDAP groups to Keycloak roles:
   - LDAP group `taxonomy-users` → ROLE_USER
   - LDAP group `taxonomy-architects` → ROLE_ARCHITECT
   - LDAP group `taxonomy-admins` → ROLE_ADMIN

### SAML Identity Provider (Government SSO)

See [SSO Integration](SSO_INTEGRATION.md) for SAML federation with government identity providers.

---

## Testing the Integration

### Obtain a Token

```bash
# Get an access token from Keycloak
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/taxonomy/protocol/openid-connect/token \
  -d "client_id=taxonomy-app" \
  -d "client_secret=<secret>" \
  -d "username=admin" \
  -d "password=admin" \
  -d "grant_type=password" | jq -r .access_token)

echo $TOKEN
```

### Use the Token with the API

```bash
# Call a protected endpoint with the JWT
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/taxonomy

# Verify role-based access
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/admin/users
```

### Inspect the Token

```bash
# Decode the JWT (base64)
echo $TOKEN | cut -d. -f2 | base64 -d | jq .
```

Expected payload includes:

```json
{
  "realm_access": {
    "roles": ["ROLE_USER", "ROLE_ARCHITECT", "ROLE_ADMIN"]
  },
  "preferred_username": "admin"
}
```

---

## Production Considerations

### High Availability

- Deploy Keycloak in a clustered configuration (Infinispan cache)
- Use an external PostgreSQL database for Keycloak
- Place behind a load balancer with sticky sessions

### Security

- Enable HTTPS for Keycloak (required for production)
- Configure CORS appropriately
- Set strong admin credentials
- Enable audit events in Keycloak

### Network Topology

```
┌───────────────┐     ┌───────────────┐     ┌───────────────┐
│  Load Balancer │────▶│  Keycloak      │────▶│  LDAP / AD     │
│  (TLS)         │     │  (OIDC/SAML)   │     │  (User Store)  │
└───────┬───────┘     └───────────────┘     └───────────────┘
        │
        ▼
┌───────────────┐     ┌───────────────┐
│  Taxonomy App  │────▶│  PostgreSQL    │
│  (Spring Boot) │     │  (Data Store)  │
└───────────────┘     └───────────────┘
```

---

## Related Documentation

- [SSO Integration](SSO_INTEGRATION.md) — SAML/OIDC federation for government SSO
- [Security](SECURITY.md) — current security architecture
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) — government deployment checklist
- [Configuration Reference](CONFIGURATION_REFERENCE.md) — all environment variables

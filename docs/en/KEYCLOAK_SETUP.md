# Keycloak & SSO Setup

> **✅ Implementation Status: Available**
>
> Keycloak/OIDC integration is implemented and can be activated via the `keycloak` Spring profile.
> The default mode (without Keycloak) continues to use **form login** (for the GUI) and **HTTP Basic** (for the REST API).
> Activate the `keycloak` profile to switch to OIDC-based authentication with Keycloak.

This document describes how to integrate the Taxonomy Architecture Analyzer with Keycloak for enterprise-grade identity management, including SSO federation with government identity providers via SAML 2.0 and OIDC.

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
10. [SSO Federation](#sso-federation)
11. [Government SSO Scenarios](#government-sso-scenarios)
12. [SSO Troubleshooting](#sso-troubleshooting)

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

> **Note:** Keycloak/OIDC integration is fully implemented. Activate the `keycloak` Spring profile to switch from the default form-login mode to OIDC-based authentication with Keycloak.

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

See [SSO Federation](#sso-federation) below for SAML/OIDC federation with government identity providers.

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

- [Security](SECURITY.md) — current security architecture
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) — government deployment checklist
- [Configuration Reference](CONFIGURATION_REFERENCE.md) — all environment variables

---

## SSO Federation

Government environments typically require integration with a central identity provider (IdP) for:

- **Centralized user management** — no separate user accounts per application
- **Single Sign-On** — authenticate once, access multiple applications
- **Multi-factor authentication** — enforce MFA policies centrally
- **Audit compliance** — centralized authentication logs
- **De-provisioning** — disable access across all applications from one place

The Taxonomy Architecture Analyzer supports SSO through **Keycloak as an identity broker**, which federates with government identity providers via SAML 2.0 or OIDC.

### Supported Protocols

| Protocol | Use Case | Standard |
|---|---|---|
| **SAML 2.0** | Government IdPs (ADFS, Shibboleth, BundID) | OASIS SAML 2.0 |
| **OpenID Connect** | Modern cloud IdPs (Azure AD, Google, Keycloak-to-Keycloak) | OpenID Connect Core 1.0 |
| **LDAP** | Direct directory integration (Active Directory, OpenLDAP) | RFC 4511 |

### Architecture Patterns

**Pattern A: Keycloak as Identity Broker (Recommended)**

```
┌─────────────┐                    ┌──────────────┐
│  Government  │  SAML 2.0 / OIDC  │              │
│  Identity    │ ◀────────────────▶ │  Keycloak    │
│  Provider    │                    │  (Broker)    │
└─────────────┘                    └──────┬───────┘
                                          │
                                   OIDC / JWT
                                          │
                                          ▼
                                   ┌──────────────┐
                                   │  Taxonomy     │
                                   │  Analyzer     │
                                   └──────────────┘
```

**Pattern B: Direct SAML Integration**

When Keycloak is not permitted or a lightweight integration is preferred. Requires `spring-security-saml2-service-provider` dependency.

### SAML 2.0 Federation via Keycloak

1. **In Keycloak:** Add the government IdP as an Identity Provider
   - Go to **Identity Providers** → **Add provider** → **SAML v2.0**
   - Enter the IdP's **metadata URL** or upload the metadata XML

2. **Configure SAML settings:**

   | Setting | Value |
   |---|---|
   | **Alias** | `gov-idp` |
   | **Display Name** | Government SSO |
   | **Import from URL** | `https://idp.example.gov/metadata` |
   | **NameID Policy Format** | `urn:oasis:names:tc:SAML:2.0:nameid-format:persistent` |
   | **Principal Type** | Subject NameID |
   | **First Login Flow** | `first broker login` (creates local Keycloak user on first SSO login) |

3. **Map SAML attributes to Keycloak:**

   | SAML Attribute | Keycloak Attribute | Purpose |
   |---|---|---|
   | `urn:oid:0.9.2342.19200300.100.1.3` | `email` | Email address |
   | `urn:oid:2.5.4.42` | `firstName` | First name |
   | `urn:oid:2.5.4.4` | `lastName` | Last name |
   | `urn:oid:1.3.6.1.4.1.5923.1.1.1.7` | `groups` | Group membership (for role mapping) |

**Service Provider Metadata** — Keycloak provides SP metadata for the government IdP at:
```
https://keycloak.example.gov/realms/taxonomy/protocol/saml/descriptor
```

### OIDC Federation via Keycloak

1. **In Keycloak:** Add an OIDC Identity Provider
   - Go to **Identity Providers** → **Add provider** → **OpenID Connect v1.0**

2. **Configure OIDC settings:**

   | Setting | Value |
   |---|---|
   | **Alias** | `gov-oidc` |
   | **Authorization URL** | `https://idp.example.gov/authorize` |
   | **Token URL** | `https://idp.example.gov/token` |
   | **Client ID** | Provided by the government IdP |
   | **Client Secret** | Provided by the government IdP |
   | **Default Scopes** | `openid profile email` |

### Direct Spring Security OIDC (Without Keycloak)

For direct integration without Keycloak:

```properties
# application-sso.properties
spring.security.oauth2.client.registration.gov.client-id=taxonomy-app
spring.security.oauth2.client.registration.gov.client-secret=${GOV_SSO_CLIENT_SECRET}
spring.security.oauth2.client.registration.gov.scope=openid,profile,email
spring.security.oauth2.client.provider.gov.issuer-uri=https://idp.example.gov
```

### Role Mapping from SSO

Government IdPs typically provide group membership as SAML attributes or OIDC claims. Configure Keycloak to map these to application roles:

1. Go to **Identity Providers** → **gov-idp** → **Mappers**
2. Add a mapper:

   | Setting | Value |
   |---|---|
   | **Mapper Type** | Attribute Importer (SAML) / Claim to Role (OIDC) |
   | **Attribute Name** | `memberOf` or `groups` |
   | **Attribute Value** | `taxonomy-users` |
   | **Role** | `ROLE_USER` |

3. Repeat for `taxonomy-architects` → `ROLE_ARCHITECT` and `taxonomy-admins` → `ROLE_ADMIN`

**Default Role Assignment:** Configure a default role for new SSO users in **Realm Settings** → **Default Roles** → Add `ROLE_USER`.

### Session Management

| Event | Behavior |
|---|---|
| **Login** | User redirected to Keycloak → IdP → back to application with JWT |
| **Session duration** | Controlled by Keycloak session settings (default: 30 minutes) |
| **Token refresh** | Application refreshes JWT using refresh token |
| **Logout** | Application → Keycloak → IdP (front-channel or back-channel logout) |
| **Idle timeout** | Configurable in Keycloak realm settings |

---

## Government SSO Scenarios

### Scenario 1: Federal Agency (BundID / Nutzerkonto Bund)

| Aspect | Configuration |
|---|---|
| **Protocol** | SAML 2.0 |
| **IdP** | BundID / Nutzerkonto Bund |
| **Authentication** | eID (Personalausweis), username/password |
| **MFA** | Enforced by IdP |
| **Integration** | Keycloak as broker → SAML federation |

### Scenario 2: State Agency (Landesportal)

| Aspect | Configuration |
|---|---|
| **Protocol** | SAML 2.0 or OIDC (varies by state) |
| **IdP** | State identity portal |
| **Authentication** | Username/password + MFA |
| **Integration** | Keycloak as broker |

### Scenario 3: Enterprise with Active Directory

| Aspect | Configuration |
|---|---|
| **Protocol** | LDAP + Kerberos (SPNEGO) or ADFS (SAML) |
| **IdP** | Active Directory Federation Services (ADFS) |
| **Authentication** | Windows Integrated Authentication |
| **Integration** | Keycloak LDAP federation or ADFS SAML broker |

### Scenario 4: Multi-Organization Collaboration

| Aspect | Configuration |
|---|---|
| **Protocol** | OIDC (inter-organizational) |
| **IdP** | Each organization's IdP |
| **Authentication** | Organization-managed |
| **Integration** | Multiple IdPs federated through Keycloak |

---

## SSO Troubleshooting

| Problem | Cause | Solution |
|---|---|---|
| Redirect loop after login | Incorrect redirect URI in Keycloak | Verify `Valid Redirect URIs` in client settings |
| "Invalid token" errors | Clock skew between servers | Synchronize clocks (NTP) |
| Roles not mapped | SAML attributes not forwarded | Check IdP attribute release policy |
| User created without roles | No group-to-role mapping | Configure Keycloak mappers |
| SSL/TLS errors | Self-signed certificates | Import CA cert into Java truststore |

**Diagnostic Steps:**

1. **Check Keycloak events:** Admin Console → Events → Login Events
2. **Inspect JWT contents:** Decode the token (see [Testing the Integration](#testing-the-integration))
3. **Enable debug logging:** `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY=DEBUG`
4. **Verify SAML assertion:** Check the SAML response from the IdP for attribute names and values

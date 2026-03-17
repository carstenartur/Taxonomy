# SSO Integration

This document describes how to integrate the Taxonomy Architecture Analyzer with government Single Sign-On (SSO) infrastructure via SAML 2.0 and OpenID Connect (OIDC).

---

## Table of Contents

1. [Overview](#overview)
2. [Supported Protocols](#supported-protocols)
3. [Architecture Patterns](#architecture-patterns)
4. [SAML 2.0 Integration](#saml-20-integration)
5. [OIDC Integration](#oidc-integration)
6. [Government SSO Scenarios](#government-sso-scenarios)
7. [Role Mapping from SSO](#role-mapping-from-sso)
8. [Session Management](#session-management)
9. [Troubleshooting](#troubleshooting)

---

## Overview

Government environments typically require integration with a central identity provider (IdP) for:

- **Centralized user management** — no separate user accounts per application
- **Single Sign-On** — authenticate once, access multiple applications
- **Multi-factor authentication** — enforce MFA policies centrally
- **Audit compliance** — centralized authentication logs
- **De-provisioning** — disable access across all applications from one place

The Taxonomy Architecture Analyzer supports SSO through **Keycloak as an identity broker**, which federates with government identity providers via SAML 2.0 or OIDC.

---

## Supported Protocols

| Protocol | Use Case | Standard |
|---|---|---|
| **SAML 2.0** | Government IdPs (ADFS, Shibboleth, BundID) | OASIS SAML 2.0 |
| **OpenID Connect** | Modern cloud IdPs (Azure AD, Google, Keycloak-to-Keycloak) | OpenID Connect Core 1.0 |
| **LDAP** | Direct directory integration (Active Directory, OpenLDAP) | RFC 4511 |

---

## Architecture Patterns

### Pattern A: Keycloak as Identity Broker (Recommended)

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

**Advantages:**
- Application only needs OIDC support (simpler)
- Keycloak handles protocol translation
- Multiple IdPs can be federated through one Keycloak instance
- Role mapping configured in Keycloak

### Pattern B: Direct SAML Integration

```
┌─────────────┐                    ┌──────────────┐
│  Government  │  SAML 2.0          │  Taxonomy     │
│  Identity    │ ◀──────────────────▶│  Analyzer     │
│  Provider    │                    │  (SAML SP)    │
└─────────────┘                    └──────────────┘
```

**When to use:** When Keycloak is not permitted or a lightweight integration is preferred. Requires `spring-security-saml2-service-provider` dependency.

---

## SAML 2.0 Integration

### Via Keycloak (Recommended)

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

### Service Provider Metadata

Keycloak provides SP metadata for the government IdP at:

```
https://keycloak.example.gov/realms/taxonomy/protocol/saml/descriptor
```

Provide this URL to the government IdP administrator for trust establishment.

---

## OIDC Integration

### Via Keycloak

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

### Direct Spring Security OIDC

For direct integration without Keycloak:

```properties
# application-sso.properties
spring.security.oauth2.client.registration.gov.client-id=taxonomy-app
spring.security.oauth2.client.registration.gov.client-secret=${GOV_SSO_CLIENT_SECRET}
spring.security.oauth2.client.registration.gov.scope=openid,profile,email
spring.security.oauth2.client.provider.gov.issuer-uri=https://idp.example.gov
```

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

## Role Mapping from SSO

### Mapping SSO Groups to Application Roles

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

### Default Role Assignment

Configure a default role for new SSO users who don't match any group mapping:

- Go to **Realm Settings** → **Default Roles** → Add `ROLE_USER`

This ensures every authenticated user has at minimum read access.

---

## Session Management

### Session Lifecycle

| Event | Behavior |
|---|---|
| **Login** | User redirected to Keycloak → IdP → back to application with JWT |
| **Session duration** | Controlled by Keycloak session settings (default: 30 minutes) |
| **Token refresh** | Application refreshes JWT using refresh token |
| **Logout** | Application → Keycloak → IdP (front-channel or back-channel logout) |
| **Idle timeout** | Configurable in Keycloak realm settings |

### Single Logout (SLO)

Configure single logout to ensure users are logged out from all applications:

1. **Keycloak:** Enable front-channel logout for the client
2. **Application:** Configure logout to redirect to Keycloak's logout endpoint

```
GET /realms/taxonomy/protocol/openid-connect/logout?
    redirect_uri=http://localhost:8080/login
```

---

## Troubleshooting

### Common Issues

| Problem | Cause | Solution |
|---|---|---|
| Redirect loop after login | Incorrect redirect URI in Keycloak | Verify `Valid Redirect URIs` in client settings |
| "Invalid token" errors | Clock skew between servers | Synchronize clocks (NTP) |
| Roles not mapped | SAML attributes not forwarded | Check IdP attribute release policy |
| User created without roles | No group-to-role mapping | Configure Keycloak mappers |
| SSL/TLS errors | Self-signed certificates | Import CA cert into Java truststore |

### Diagnostic Steps

1. **Check Keycloak events:** Admin Console → Events → Login Events
2. **Inspect JWT contents:** Decode the token (see [Keycloak Setup](KEYCLOAK_SETUP.md))
3. **Enable debug logging:**
   ```bash
   LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY=DEBUG
   ```
4. **Verify SAML assertion:** Check the SAML response from the IdP for attribute names and values

---

## Related Documentation

- [Keycloak Setup](KEYCLOAK_SETUP.md) — Keycloak installation and configuration
- [Security](SECURITY.md) — current authentication and authorization architecture
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) — government deployment checklist

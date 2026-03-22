# Keycloak- & SSO-Einrichtung

> **⚠️ Implementierungsstatus: Integrationsanleitung (noch nicht implementiert)**
>
> Dieses Dokument ist eine **Planungs- und Integrationsanleitung** für eine zukünftige Keycloak/SSO-Migration (Phase 2).
> Die aktuelle Anwendung verwendet **Form-Login** (für die GUI) und **HTTP Basic** (für die REST-API)
> mit einer integrierten Benutzerdatenbank. Keine OAuth2/OIDC-Client-Abhängigkeiten sind im aktuellen Code enthalten.
> Verwenden Sie diese Anleitung, wenn Sie bereit sind, Keycloak in Ihre Bereitstellung zu integrieren.

Dieses Dokument beschreibt, wie Sie den Taxonomy Architecture Analyzer mit Keycloak für unternehmenstaugliches Identitätsmanagement integrieren, einschließlich SSO-Federation mit behördlichen Identitätsanbietern über SAML 2.0 und OIDC.

---

## Inhaltsverzeichnis

1. [Überblick](#überblick)
2. [Architektur](#architektur)
3. [Schnellstart mit Docker Compose](#schnellstart-mit-docker-compose)
4. [Keycloak-Realm-Konfiguration](#keycloak-realm-konfiguration)
5. [Anwendungskonfiguration](#anwendungskonfiguration)
6. [Rollenzuordnung](#rollenzuordnung)
7. [Benutzer-Federation](#benutzer-federation)
8. [Testen der Integration](#testen-der-integration)
9. [Produktionshinweise](#produktionshinweise)
10. [SSO-Federation](#sso-federation)
11. [Behördliche SSO-Szenarien](#behördliche-sso-szenarien)
12. [SSO-Fehlerbehebung](#sso-fehlerbehebung)

---

## Überblick

Keycloak bietet zentralisiertes Identitätsmanagement mit Unterstützung für:

- **OpenID Connect (OIDC)** — moderne tokenbasierte Authentifizierung
- **SAML 2.0** — Federation mit behördlichen Identitätsanbietern
- **LDAP/AD-Integration** — bestehende Verzeichnisdienste
- **Multi-Faktor-Authentifizierung (MFA)** — Hardware-Token, TOTP
- **Single Sign-On (SSO)** — über mehrere Anwendungen hinweg

Die Integration ersetzt den integrierten Spring Security Form-Login durch Keycloak-verwaltete Authentifizierung und bewahrt dabei das bestehende Drei-Rollen-Autorisierungsmodell (USER, ARCHITECT, ADMIN).

---

## Architektur

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

## Schnellstart mit Docker Compose

Verwenden Sie `docker-compose-keycloak.yml`, um eine Entwicklungsumgebung mit Keycloak zu starten:

```bash
docker compose -f docker-compose-keycloak.yml up -d
```

Damit werden folgende Dienste gestartet:

| Dienst | URL | Zugangsdaten |
|---|---|---|
| **Taxonomy Analyzer** | http://localhost:8080 | Keycloak-verwaltet |
| **Keycloak** | http://localhost:8180 | admin / admin |
| **PostgreSQL** | localhost:5432 | taxonomy / taxonomy |

---

## Keycloak-Realm-Konfiguration

### 1. Realm erstellen

1. Melden Sie sich an der Keycloak Admin Console an: http://localhost:8180
2. Erstellen Sie einen neuen Realm: **taxonomy**

### 2. Client erstellen

| Einstellung | Wert |
|---|---|
| **Client ID** | `taxonomy-app` |
| **Client Protocol** | openid-connect |
| **Access Type** | confidential |
| **Root URL** | `http://localhost:8080` |
| **Valid Redirect URIs** | `http://localhost:8080/*` |
| **Web Origins** | `http://localhost:8080` |

### 3. Realm-Rollen erstellen

Erstellen Sie drei Rollen entsprechend dem Autorisierungsmodell der Anwendung:

| Rolle | Beschreibung |
|---|---|
| `ROLE_USER` | Taxonomie durchsuchen, Analyse ausführen, Suche, Export |
| `ROLE_ARCHITECT` | Relationen erstellen/bearbeiten, DSL committen, Branches verwalten |
| `ROLE_ADMIN` | Admin-Endpunkte, LLM-Diagnose, Benutzerverwaltung |

### 4. Benutzer erstellen

| Benutzername | Rollen | Zweck |
|---|---|---|
| `admin` | ROLE_USER, ROLE_ARCHITECT, ROLE_ADMIN | Voller Administrator |
| `architect` | ROLE_USER, ROLE_ARCHITECT | Architektur-Team |
| `analyst` | ROLE_USER | Nur-Lese-Analyst |

### 5. Client Scopes konfigurieren (Rollenzuordnung im JWT)

Stellen Sie sicher, dass Realm-Rollen im JWT-Token enthalten sind:

1. Navigieren Sie zu **Client Scopes** → **roles** → **Mappers**
2. Überprüfen Sie, ob der **realm roles**-Mapper konfiguriert ist
3. Token Claim Name: `realm_access.roles`
4. Zum ID-Token hinzufügen: Ja
5. Zum Access-Token hinzufügen: Ja

---

## Anwendungskonfiguration

### Umgebungsvariablen für Keycloak

```bash
# Enable OAuth2/OIDC authentication
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=http://keycloak:8180/realms/taxonomy
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://keycloak:8180/realms/taxonomy/protocol/openid-connect/certs

# Client credentials (for token exchange / service-to-service)
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_ID=taxonomy-app
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENT_SECRET=<client-secret-from-keycloak>
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_ISSUER_URI=http://keycloak:8180/realms/taxonomy
```

### Änderungen an der Spring-Security-Konfiguration

Die aktuelle `SecurityConfig` verwendet Form-Login und HTTP Basic. Um auf Keycloak umzustellen:

1. Fügen Sie die Abhängigkeit `spring-boot-starter-oauth2-resource-server` hinzu
2. Konfigurieren Sie die JWT-Validierung in `SecurityConfig`
3. Ordnen Sie Keycloak-Realm-Rollen den Spring-Security-Authorities zu
4. Behalten Sie HTTP Basic als Fallback für REST-Clients bei (optional)

**Beispiel für die SecurityConfig-Anpassung:**

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

> **Hinweis:** Dies ist eine Referenzimplementierung für die geplante Keycloak-Migration (Phase 2). Die aktuelle Codebasis verwendet Form-Login + HTTP Basic.

---

## Rollenzuordnung

Die drei Rollen der Anwendung werden direkt auf Keycloak-Realm-Rollen abgebildet:

| Anwendungsrolle | Keycloak-Realm-Rolle | Spring Authority |
|---|---|---|
| USER | `ROLE_USER` | `ROLE_USER` |
| ARCHITECT | `ROLE_ARCHITECT` | `ROLE_ARCHITECT` |
| ADMIN | `ROLE_ADMIN` | `ROLE_ADMIN` |

### Zusammengesetzte Rollen (Optional)

Erstellen Sie zusammengesetzte Rollen in Keycloak zur Vereinfachung:

| Zusammengesetzte Rolle | Enthält |
|---|---|
| `architect` | ROLE_USER + ROLE_ARCHITECT |
| `admin` | ROLE_USER + ROLE_ARCHITECT + ROLE_ADMIN |

---

## Benutzer-Federation

### LDAP / Active Directory

Keycloak kann Benutzer aus LDAP oder Active Directory föderieren:

1. Navigieren Sie zu **User Federation** → **Add provider** → **ldap**
2. Konfigurieren Sie die Verbindung:
   - **Connection URL:** `ldap://ldap.example.gov:389`
   - **Users DN:** `ou=users,dc=example,dc=gov`
   - **Bind DN:** `cn=service-account,dc=example,dc=gov`
3. Ordnen Sie LDAP-Gruppen den Keycloak-Rollen zu:
   - LDAP-Gruppe `taxonomy-users` → ROLE_USER
   - LDAP-Gruppe `taxonomy-architects` → ROLE_ARCHITECT
   - LDAP-Gruppe `taxonomy-admins` → ROLE_ADMIN

### SAML Identity Provider (Behördliches SSO)

Siehe den Abschnitt [SSO-Federation](#sso-federation) weiter unten für SAML-/OIDC-Federation mit behördlichen Identitätsanbietern.

---

## Testen der Integration

### Token abrufen

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

### Token mit der API verwenden

```bash
# Call a protected endpoint with the JWT
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/taxonomy

# Verify role-based access
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/admin/users
```

### Token inspizieren

```bash
# Decode the JWT (base64)
echo $TOKEN | cut -d. -f2 | base64 -d | jq .
```

Der erwartete Payload enthält:

```json
{
  "realm_access": {
    "roles": ["ROLE_USER", "ROLE_ARCHITECT", "ROLE_ADMIN"]
  },
  "preferred_username": "admin"
}
```

---

## Produktionshinweise

### Hochverfügbarkeit

- Stellen Sie Keycloak in einer Cluster-Konfiguration bereit (Infinispan Cache)
- Verwenden Sie eine externe PostgreSQL-Datenbank für Keycloak
- Schalten Sie einen Load Balancer mit Sticky Sessions davor

### Sicherheit

- Aktivieren Sie HTTPS für Keycloak (für den Produktivbetrieb erforderlich)
- Konfigurieren Sie CORS entsprechend
- Vergeben Sie starke Admin-Zugangsdaten
- Aktivieren Sie Audit-Events in Keycloak

### Netzwerktopologie

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

## Verwandte Dokumentation

- [Sicherheit](SECURITY.md) — aktuelle Sicherheitsarchitektur
- [Deployment-Checkliste](DEPLOYMENT_CHECKLIST.md) — Deployment-Checkliste für den Behördeneinsatz
- [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) — alle Umgebungsvariablen

---

## SSO-Federation

Behördenumgebungen erfordern typischerweise die Integration mit einem zentralen Identitätsanbieter (IdP) für:

- **Zentrale Benutzerverwaltung** — keine separaten Benutzerkonten pro Anwendung
- **Single Sign-On** — einmalige Authentifizierung für mehrere Anwendungen
- **Multi-Faktor-Authentifizierung** — zentrale Durchsetzung von MFA-Richtlinien
- **Audit-Konformität** — zentrale Authentifizierungsprotokolle
- **De-Provisioning** — Zugang zentral deaktivieren

Der Taxonomy Architecture Analyzer unterstützt SSO durch **Keycloak als Identity Broker**, der über SAML 2.0 oder OIDC mit behördlichen Identitätsanbietern föderiert.

### Unterstützte Protokolle

| Protokoll | Anwendungsfall | Standard |
|---|---|---|
| **SAML 2.0** | Behördliche IdPs (ADFS, Shibboleth, BundID) | OASIS SAML 2.0 |
| **OpenID Connect** | Moderne Cloud-IdPs (Azure AD, Google, Keycloak-zu-Keycloak) | OpenID Connect Core 1.0 |
| **LDAP** | Direkte Verzeichnisintegration (Active Directory, OpenLDAP) | RFC 4511 |

### SAML-2.0-Federation über Keycloak

1. **In Keycloak:** Fügen Sie den behördlichen IdP als Identity Provider hinzu
   - **Identity Providers** → **Add provider** → **SAML v2.0**
   - IdP-**Metadaten-URL** eingeben oder Metadaten-XML hochladen

2. **SAML-Einstellungen konfigurieren:**

   | Einstellung | Wert |
   |---|---|
   | **Alias** | `gov-idp` |
   | **Anzeigename** | Behördliches SSO |
   | **Von URL importieren** | `https://idp.example.gov/metadata` |
   | **NameID Policy Format** | `urn:oasis:names:tc:SAML:2.0:nameid-format:persistent` |

### OIDC-Federation über Keycloak

1. **In Keycloak:** OIDC Identity Provider hinzufügen
   - **Identity Providers** → **Add provider** → **OpenID Connect v1.0**

2. **OIDC-Einstellungen konfigurieren:**

   | Einstellung | Wert |
   |---|---|
   | **Alias** | `gov-oidc` |
   | **Authorization URL** | `https://idp.example.gov/authorize` |
   | **Token URL** | `https://idp.example.gov/token` |
   | **Client ID** | Vom behördlichen IdP bereitgestellt |
   | **Client Secret** | Vom behördlichen IdP bereitgestellt |
   | **Default Scopes** | `openid profile email` |

---

## Behördliche SSO-Szenarien

### Szenario 1: Bundesbehörde (BundID / Nutzerkonto Bund)

| Aspekt | Konfiguration |
|---|---|
| **Protokoll** | SAML 2.0 |
| **IdP** | BundID / Nutzerkonto Bund |
| **Authentifizierung** | eID (Personalausweis), Benutzername/Passwort |
| **MFA** | Vom IdP erzwungen |
| **Integration** | Keycloak als Broker → SAML-Federation |

### Szenario 2: Landesbehörde (Landesportal)

| Aspekt | Konfiguration |
|---|---|
| **Protokoll** | SAML 2.0 oder OIDC (je nach Bundesland) |
| **IdP** | Landes-Identitätsportal |
| **Authentifizierung** | Benutzername/Passwort + MFA |
| **Integration** | Keycloak als Broker |

### Szenario 3: Unternehmen mit Active Directory

| Aspekt | Konfiguration |
|---|---|
| **Protokoll** | LDAP + Kerberos (SPNEGO) oder ADFS (SAML) |
| **IdP** | Active Directory Federation Services (ADFS) |
| **Authentifizierung** | Windows Integrated Authentication |
| **Integration** | Keycloak LDAP-Federation oder ADFS-SAML-Broker |

---

## SSO-Fehlerbehebung

| Problem | Ursache | Lösung |
|---|---|---|
| Redirect-Schleife nach Login | Falsche Redirect-URI in Keycloak | `Valid Redirect URIs` in Client-Einstellungen prüfen |
| „Invalid token"-Fehler | Uhrzeitabweichung zwischen Servern | Uhren synchronisieren (NTP) |
| Rollen nicht zugeordnet | SAML-Attribute nicht weitergeleitet | IdP-Attributfreigabe-Richtlinie prüfen |
| Benutzer ohne Rollen erstellt | Keine Gruppen-zu-Rollen-Zuordnung | Keycloak-Mapper konfigurieren |
| SSL/TLS-Fehler | Selbstsignierte Zertifikate | CA-Zertifikat in Java-Truststore importieren |

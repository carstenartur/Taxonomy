# SSO-Integration

Dieses Dokument beschreibt, wie Sie den Taxonomy Architecture Analyzer in die Single-Sign-On-Infrastruktur (SSO) von Behörden über SAML 2.0 und OpenID Connect (OIDC) integrieren.

---

## Inhaltsverzeichnis

1. [Überblick](#überblick)
2. [Unterstützte Protokolle](#unterstützte-protokolle)
3. [Architekturmuster](#architekturmuster)
4. [SAML 2.0 Integration](#saml-20-integration)
5. [OIDC-Integration](#oidc-integration)
6. [SSO-Szenarien für Behörden](#sso-szenarien-für-behörden)
7. [Rollenzuordnung aus SSO](#rollenzuordnung-aus-sso)
8. [Sitzungsverwaltung](#sitzungsverwaltung)
9. [Fehlerbehebung](#fehlerbehebung)

---

## Überblick

Behördenumgebungen erfordern in der Regel die Integration mit einem zentralen Identity Provider (IdP) für:

- **Zentrale Benutzerverwaltung** — keine separaten Benutzerkonten pro Anwendung
- **Single Sign-On** — einmalige Authentifizierung, Zugriff auf mehrere Anwendungen
- **Multi-Faktor-Authentifizierung** — zentrale Durchsetzung von MFA-Richtlinien
- **Audit-Konformität** — zentrale Authentifizierungsprotokolle
- **Deprovisionierung** — Zugriff über alle Anwendungen hinweg zentral deaktivieren

Der Taxonomy Architecture Analyzer unterstützt SSO über **Keycloak als Identity Broker**, der sich über SAML 2.0 oder OIDC mit Identity Providern von Behörden verbindet.

---

## Unterstützte Protokolle

| Protokoll | Anwendungsfall | Standard |
|---|---|---|
| **SAML 2.0** | Behörden-IdPs (ADFS, Shibboleth, BundID) | OASIS SAML 2.0 |
| **OpenID Connect** | Moderne Cloud-IdPs (Azure AD, Google, Keycloak-zu-Keycloak) | OpenID Connect Core 1.0 |
| **LDAP** | Direkte Verzeichnisintegration (Active Directory, OpenLDAP) | RFC 4511 |

---

## Architekturmuster

### Muster A: Keycloak als Identity Broker (Empfohlen)

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

**Vorteile:**
- Die Anwendung benötigt nur OIDC-Unterstützung (einfacher)
- Keycloak übernimmt die Protokollübersetzung
- Mehrere IdPs können über eine einzige Keycloak-Instanz föderiert werden
- Rollenzuordnung wird in Keycloak konfiguriert

### Muster B: Direkte SAML-Integration

```
┌─────────────┐                    ┌──────────────┐
│  Government  │  SAML 2.0          │  Taxonomy     │
│  Identity    │ ◀──────────────────▶│  Analyzer     │
│  Provider    │                    │  (SAML SP)    │
└─────────────┘                    └──────────────┘
```

**Wann zu verwenden:** Wenn Keycloak nicht erlaubt ist oder eine leichtgewichtige Integration bevorzugt wird. Erfordert die Abhängigkeit `spring-security-saml2-service-provider`.

---

## SAML 2.0 Integration

### Über Keycloak (Empfohlen)

1. **In Keycloak:** Fügen Sie den Behörden-IdP als Identity Provider hinzu
   - Gehen Sie zu **Identity Providers** → **Add provider** → **SAML v2.0**
   - Geben Sie die **Metadaten-URL** des IdP ein oder laden Sie die Metadaten-XML hoch

2. **SAML-Einstellungen konfigurieren:**

   | Einstellung | Wert |
   |---|---|
   | **Alias** | `gov-idp` |
   | **Display Name** | Government SSO |
   | **Import from URL** | `https://idp.example.gov/metadata` |
   | **NameID Policy Format** | `urn:oasis:names:tc:SAML:2.0:nameid-format:persistent` |
   | **Principal Type** | Subject NameID |
   | **First Login Flow** | `first broker login` (erstellt bei der ersten SSO-Anmeldung einen lokalen Keycloak-Benutzer) |

3. **SAML-Attribute auf Keycloak abbilden:**

   | SAML-Attribut | Keycloak-Attribut | Zweck |
   |---|---|---|
   | `urn:oid:0.9.2342.19200300.100.1.3` | `email` | E-Mail-Adresse |
   | `urn:oid:2.5.4.42` | `firstName` | Vorname |
   | `urn:oid:2.5.4.4` | `lastName` | Nachname |
   | `urn:oid:1.3.6.1.4.1.5923.1.1.1.7` | `groups` | Gruppenmitgliedschaft (für Rollenzuordnung) |

### Service-Provider-Metadaten

Keycloak stellt SP-Metadaten für den Behörden-IdP unter folgender URL bereit:

```
https://keycloak.example.gov/realms/taxonomy/protocol/saml/descriptor
```

Stellen Sie diese URL dem Administrator des Behörden-IdP für den Vertrauensaufbau zur Verfügung.

---

## OIDC-Integration

### Über Keycloak

1. **In Keycloak:** Fügen Sie einen OIDC Identity Provider hinzu
   - Gehen Sie zu **Identity Providers** → **Add provider** → **OpenID Connect v1.0**

2. **OIDC-Einstellungen konfigurieren:**

   | Einstellung | Wert |
   |---|---|
   | **Alias** | `gov-oidc` |
   | **Authorization URL** | `https://idp.example.gov/authorize` |
   | **Token URL** | `https://idp.example.gov/token` |
   | **Client ID** | Vom Behörden-IdP bereitgestellt |
   | **Client Secret** | Vom Behörden-IdP bereitgestellt |
   | **Default Scopes** | `openid profile email` |

### Direkte Spring Security OIDC-Integration

Für eine direkte Integration ohne Keycloak:

```properties
# application-sso.properties
spring.security.oauth2.client.registration.gov.client-id=taxonomy-app
spring.security.oauth2.client.registration.gov.client-secret=${GOV_SSO_CLIENT_SECRET}
spring.security.oauth2.client.registration.gov.scope=openid,profile,email
spring.security.oauth2.client.provider.gov.issuer-uri=https://idp.example.gov
```

---

## SSO-Szenarien für Behörden

### Szenario 1: Bundesbehörde (BundID / Nutzerkonto Bund)

| Aspekt | Konfiguration |
|---|---|
| **Protokoll** | SAML 2.0 |
| **IdP** | BundID / Nutzerkonto Bund |
| **Authentifizierung** | eID (Personalausweis), Benutzername/Passwort |
| **MFA** | Vom IdP erzwungen |
| **Integration** | Keycloak als Broker → SAML-Föderation |

### Szenario 2: Landesbehörde (Landesportal)

| Aspekt | Konfiguration |
|---|---|
| **Protokoll** | SAML 2.0 oder OIDC (je nach Bundesland unterschiedlich) |
| **IdP** | Landesidentitätsportal |
| **Authentifizierung** | Benutzername/Passwort + MFA |
| **Integration** | Keycloak als Broker |

### Szenario 3: Unternehmen mit Active Directory

| Aspekt | Konfiguration |
|---|---|
| **Protokoll** | LDAP + Kerberos (SPNEGO) oder ADFS (SAML) |
| **IdP** | Active Directory Federation Services (ADFS) |
| **Authentifizierung** | Windows Integrated Authentication |
| **Integration** | Keycloak LDAP-Föderation oder ADFS SAML-Broker |

### Szenario 4: Organisationsübergreifende Zusammenarbeit

| Aspekt | Konfiguration |
|---|---|
| **Protokoll** | OIDC (organisationsübergreifend) |
| **IdP** | IdP der jeweiligen Organisation |
| **Authentifizierung** | Organisationsintern verwaltet |
| **Integration** | Mehrere IdPs über Keycloak föderiert |

---

## Rollenzuordnung aus SSO

### SSO-Gruppen auf Anwendungsrollen abbilden

Behörden-IdPs stellen Gruppenmitgliedschaften typischerweise als SAML-Attribute oder OIDC-Claims bereit. Konfigurieren Sie Keycloak, um diese auf Anwendungsrollen abzubilden:

1. Gehen Sie zu **Identity Providers** → **gov-idp** → **Mappers**
2. Fügen Sie einen Mapper hinzu:

   | Einstellung | Wert |
   |---|---|
   | **Mapper Type** | Attribute Importer (SAML) / Claim to Role (OIDC) |
   | **Attribute Name** | `memberOf` oder `groups` |
   | **Attribute Value** | `taxonomy-users` |
   | **Role** | `ROLE_USER` |

3. Wiederholen Sie den Vorgang für `taxonomy-architects` → `ROLE_ARCHITECT` und `taxonomy-admins` → `ROLE_ADMIN`

### Standard-Rollenzuweisung

Konfigurieren Sie eine Standardrolle für neue SSO-Benutzer, die keiner Gruppenzuordnung entsprechen:

- Gehen Sie zu **Realm Settings** → **Default Roles** → Fügen Sie `ROLE_USER` hinzu

Damit wird sichergestellt, dass jeder authentifizierte Benutzer mindestens Lesezugriff hat.

---

## Sitzungsverwaltung

### Sitzungslebenszyklus

| Ereignis | Verhalten |
|---|---|
| **Anmeldung** | Benutzer wird zu Keycloak → IdP → zurück zur Anwendung mit JWT weitergeleitet |
| **Sitzungsdauer** | Wird durch Keycloak-Sitzungseinstellungen gesteuert (Standard: 30 Minuten) |
| **Token-Aktualisierung** | Die Anwendung aktualisiert das JWT mithilfe des Refresh Tokens |
| **Abmeldung** | Anwendung → Keycloak → IdP (Front-Channel- oder Back-Channel-Logout) |
| **Inaktivitäts-Timeout** | Konfigurierbar in den Keycloak-Realm-Einstellungen |

### Single Logout (SLO)

Konfigurieren Sie Single Logout, um sicherzustellen, dass Benutzer von allen Anwendungen abgemeldet werden:

1. **Keycloak:** Aktivieren Sie Front-Channel-Logout für den Client
2. **Anwendung:** Konfigurieren Sie die Abmeldung so, dass sie zum Logout-Endpunkt von Keycloak weiterleitet

```
GET /realms/taxonomy/protocol/openid-connect/logout?
    redirect_uri=http://localhost:8080/login
```

---

## Fehlerbehebung

### Häufige Probleme

| Problem | Ursache | Lösung |
|---|---|---|
| Weiterleitungsschleife nach Anmeldung | Falsche Redirect-URI in Keycloak | Überprüfen Sie `Valid Redirect URIs` in den Client-Einstellungen |
| „Invalid token"-Fehler | Zeitabweichung zwischen Servern | Synchronisieren Sie die Uhren (NTP) |
| Rollen nicht zugeordnet | SAML-Attribute werden nicht weitergeleitet | Prüfen Sie die Attribut-Freigaberichtlinie des IdP |
| Benutzer ohne Rollen erstellt | Keine Gruppen-zu-Rollen-Zuordnung | Konfigurieren Sie Keycloak-Mapper |
| SSL/TLS-Fehler | Selbstsignierte Zertifikate | Importieren Sie das CA-Zertifikat in den Java-Truststore |

### Diagnoseschritte

1. **Keycloak-Ereignisse prüfen:** Admin Console → Events → Login Events
2. **JWT-Inhalte untersuchen:** Dekodieren Sie das Token (siehe [Keycloak-Einrichtung](KEYCLOAK_SETUP.md))
3. **Debug-Protokollierung aktivieren:**
   ```bash
   LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY=DEBUG
   ```
4. **SAML-Assertion überprüfen:** Prüfen Sie die SAML-Antwort des IdP auf Attributnamen und -werte

---

## Weiterführende Dokumentation

- [Keycloak-Einrichtung](KEYCLOAK_SETUP.md) — Installation und Konfiguration von Keycloak
- [Sicherheit](SECURITY.md) — Aktuelle Authentifizierungs- und Autorisierungsarchitektur
- [Deployment-Checkliste](DEPLOYMENT_CHECKLIST.md) — Deployment-Checkliste für Behörden

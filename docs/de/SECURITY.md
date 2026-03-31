# Sicherheit

Dieses Dokument beschreibt die Authentifizierung, Autorisierung und Sicherheitsarchitektur des Taxonomy Architecture Analyzer.

---

## Authentifizierung

Die Anwendung verwendet **Spring Security** mit zwei Authentifizierungsmethoden:

| Methode | Verwendet von | Sitzung |
|---|---|---|
| **Formular-Login** | Browser-Benutzer (GUI) | Serverseitige Sitzung mit CSRF-Schutz |
| **HTTP Basic** | REST-Clients (curl, Skripte, CI) | Zustandslos; kein CSRF-Token erforderlich |

Beide Methoden authentifizieren gegen dieselbe Benutzerdatenbank (JPA-basiert, BCrypt-gehashte Passwörter).

### Standardbenutzer

Beim ersten Start wird ein Standard-Admin-Benutzer erstellt:

| Benutzername | Passwort | Rollen |
|---|---|---|
| `admin` | Wert von `TAXONOMY_ADMIN_PASSWORD` (Standard: `admin`) | USER, ARCHITECT, ADMIN |

> **Ändern Sie das Standardpasswort**, bevor Sie die Anwendung in einem Netzwerk bereitstellen. Setzen Sie die Umgebungsvariable `TAXONOMY_ADMIN_PASSWORD` oder die Spring-Eigenschaft `taxonomy.admin-password`.

---

## Rollen und Berechtigungen

Drei Rollen steuern den Zugriff auf Funktionen:

| Rolle | Was Sie tun können |
|---|---|
| **USER** | Taxonomie durchsuchen, Analyse ausführen, suchen, Graph anzeigen, Diagramme exportieren, Vorschläge anzeigen |
| **ARCHITECT** | Alles aus USER, plus: Relationen erstellen/bearbeiten/löschen, DSL committen, Git-Branches verwalten |
| **ADMIN** | Alles aus ARCHITECT, plus: Admin-Endpunkte, LLM-Diagnose, Systemkonfiguration |

### Endpunkt-Zugriffsmatrix

| Endpunktmuster | USER | ARCHITECT | ADMIN |
|---|:---:|:---:|:---:|
| `GET /api/**` (Lesen) | ✅ | ✅ | ✅ |
| `POST /api/analyze`, `POST /api/justify-leaf` | ✅ | ✅ | ✅ |
| `POST /api/export/**` | ✅ | ✅ | ✅ |
| `POST/PUT/DELETE /api/relations/**` | ❌ | ✅ | ✅ |
| `POST/PUT/DELETE /api/dsl/**` | ❌ | ✅ | ✅ |
| `POST/PUT/DELETE /api/git/**` | ❌ | ✅ | ✅ |
| `/admin/**`, `/api/admin/**` | ❌ | ❌ | ✅ |

---

## GUI- vs. REST-Sicherheit

### Browser (GUI)

- Benutzer authentifizieren sich über die **Anmeldeseite** von Spring Security (`/login`).
- Sitzungen werden durch **CSRF-Tokens** geschützt (automatisch von den Thymeleaf-Templates verarbeitet).
- Das Admin-Panel (LLM-Diagnose, Prompt-Template-Editor) erfordert die Rolle `ADMIN` und muss zusätzlich über die 🔒-Schaltfläche in der Navigationsleiste (mit dem `ADMIN_PASSWORD`-Token) entsperrt werden.

### REST-API

- Clients authentifizieren sich über **HTTP Basic** (`-u username:password`).
- CSRF ist für alle `/api/**`-Pfade **deaktiviert**, sodass kein Token-Austausch erforderlich ist.
- Beispiel:
  ```bash
  curl -u admin:admin http://localhost:8080/api/taxonomy
  ```

---

## Öffentliche Endpunkte

Diese Endpunkte erfordern **keine** Authentifizierung:

| Endpunkt | Zweck |
|---|---|
| `/login`, `/error` | Anmeldeseite und Fehlerseite |
| `/actuator/health`, `/actuator/info` | Zustandsprüfungen (für Load Balancer, Docker) |
| `/v3/api-docs/**`, `/swagger-ui/**` | OpenAPI-Dokumentation |
| `/css/**`, `/js/**`, `/images/**`, `/webjars/**` | Statische Ressourcen |

---

## Admin-Panel-Zugriff

Das Admin-Panel bietet LLM-Diagnose, Prompt-Template-Bearbeitung und Kommunikationsprotokolle. Es wird durch zwei Schichten geschützt:

1. **Rollenbasiert**: Nur Benutzer mit `ROLE_ADMIN` können auf `/admin/**` und `/api/admin/**` zugreifen.
2. **Token-basiert**: In der GUI erfordert das Admin-Panel zusätzlich die Eingabe des `ADMIN_PASSWORD` über die 🔒-Schaltfläche. Dies bietet einen zweiten Faktor für gemeinsam genutzte Umgebungen, in denen mehrere Benutzer dasselbe Admin-Konto verwenden.

| Variable | Zweck |
|---|---|
| `TAXONOMY_ADMIN_PASSWORD` | Spring-Security-Anmeldepasswort für den Benutzer `admin` |
| `ADMIN_PASSWORD` | Token zum Entsperren der Admin-Panels in der GUI |

> **Wichtig:** Dies sind zwei separate Passwörter. `TAXONOMY_ADMIN_PASSWORD` steuert die Anmeldeauthentifizierung. `ADMIN_PASSWORD` steuert den Zugriff auf die Admin-UI-Panels.

---

## Sicherheitscheckliste für die Bereitstellung

### Lokale Entwicklung

Für die lokale Entwicklung sind die Standardwerte ausreichend:

```bash
mvn spring-boot:run   # admin/admin, no admin panel password
```

### Gemeinsam genutzte oder exponierte Bereitstellung

Für jede Bereitstellung, die für andere zugänglich ist:

1. **Setzen Sie `TAXONOMY_ADMIN_PASSWORD`** auf ein starkes Passwort:
   ```bash
   TAXONOMY_ADMIN_PASSWORD=strong-password-here
   ```

2. **Setzen Sie `ADMIN_PASSWORD`** zum Schutz der Admin-Panels:
   ```bash
   ADMIN_PASSWORD=admin-panel-secret
   ```

3. **Verwenden Sie HTTPS** — konfigurieren Sie einen Reverse Proxy (nginx, Caddy oder Cloud-Load-Balancer) mit TLS-Terminierung.

4. **Beschränken Sie die Swagger-UI** in der Produktion — setzen Sie `TAXONOMY_SPRINGDOC_ENABLED=false`, um die OpenAPI-Dokumentation zu deaktivieren.

5. **Überprüfen Sie die Actuator-Freigabe** — standardmäßig sind nur `/actuator/health` und `/actuator/info` öffentlich. Geben Sie keine zusätzlichen Actuator-Endpunkte ohne Authentifizierung frei.

### Docker

```bash
docker run -p 8080:8080 \
  -e TAXONOMY_ADMIN_PASSWORD=strong-password \
  -e ADMIN_PASSWORD=admin-panel-secret \
  -e GEMINI_API_KEY=your-key \
  ghcr.io/carstenartur/taxonomy:latest
```

---

## CSRF-Schutz

| Pfad | CSRF |
|---|---|
| `/api/**` | **Deaktiviert** — REST-Clients verwenden HTTP Basic, keine Browser-Sitzung |
| Alle anderen Pfade | **Aktiviert** — Browser-Sitzungen enthalten CSRF-Tokens über Thymeleaf-Meta-Tags |

Das browserbasierte JavaScript (`fetch()`-Aufrufe an `/api/**`) enthält CSRF-Tokens aus Meta-Tags, die jedoch vom Server für diese Pfade ignoriert werden. Dies ist beabsichtigt — die HTTP-Basic-Authentifizierung auf den API-Pfaden ist für die Sicherheit ausreichend.

---

## Brute-Force-Schutz

Der `LoginRateLimitFilter` schützt vor Brute-Force-Anmeldeangriffen, indem er fehlgeschlagene Authentifizierungsversuche pro IP-Adresse verfolgt. Nach einer konfigurierbaren Anzahl von Fehlversuchen wird die IP für eine konfigurierbare Dauer gesperrt.

| Einstellung | Umgebungsvariable | Standard | Beschreibung |
|---|---|---|---|
| Aktiviert | `TAXONOMY_LOGIN_RATE_LIMIT` | `true` | Anmelde-Ratenbegrenzung aktivieren/deaktivieren |
| Max. Versuche | `TAXONOMY_LOGIN_MAX_ATTEMPTS` | `5` | Fehlversuche vor der Sperrung |
| Sperrdauer | `TAXONOMY_LOGIN_LOCKOUT_SECONDS` | `300` | Sperrdauer in Sekunden (5 Minuten) |

**Verhalten:**
- Gilt für `POST /login` (Formular-Login) und `/api/**` (HTTP Basic mit 401-Antwort)
- Gibt HTTP 423 (Locked) mit einem JSON-Body zurück, wenn eine IP gesperrt ist
- Die Sperre läuft automatisch nach der konfigurierten Dauer ab
- Eine erfolgreiche Anmeldung setzt den Fehlerzähler für diese IP zurück

Deaktivieren Sie den Schutz mit `TAXONOMY_LOGIN_RATE_LIMIT=false` für Entwicklung oder Tests.

---

## Sicherheitsheader

Die Anwendung sendet die folgenden Sicherheitsheader bei allen Antworten:

| Header | Wert | Zweck |
|---|---|---|
| `X-Content-Type-Options` | `nosniff` | Verhindert MIME-Type-Sniffing |
| `X-Frame-Options` | `SAMEORIGIN` | Verhindert Clickjacking über iframes |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | Erzwingt HTTPS (HSTS) |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | Steuert Referrer-Informationen |

Diese Header sind immer aktiviert und können nicht deaktiviert werden.

---

## Swagger-Zugriffskontrolle

Der Zugriff auf Swagger UI und die OpenAPI-Dokumentation kann unabhängig davon gesteuert werden, ob SpringDoc aktiviert ist:

| Variable | Standard | Beschreibung |
|---|---|---|
| `TAXONOMY_SPRINGDOC_ENABLED` | `true` | SpringDoc vollständig aktivieren/deaktivieren |
| `TAXONOMY_SWAGGER_PUBLIC` | `true` | Unauthentifizierten Zugriff auf Swagger UI erlauben |

- **Entwicklung** (Standard): Swagger UI ist öffentlich zugänglich (`TAXONOMY_SWAGGER_PUBLIC=true`)
- **Produktion**: Setzen Sie `TAXONOMY_SWAGGER_PUBLIC=false`, um eine Authentifizierung zu erfordern, oder `TAXONOMY_SPRINGDOC_ENABLED=false`, um die Funktion vollständig zu deaktivieren

---

## Erstmalige Passwortänderung

Das Standard-Admin-Passwort (`admin`) löst eine Startwarnung aus:

```
SECURITY WARNING: Default admin password 'admin' is in use. Set TAXONOMY_ADMIN_PASSWORD environment variable to change it.
```

Um Passwortänderungen zu erzwingen, setzen Sie `TAXONOMY_REQUIRE_PASSWORD_CHANGE=true`. Wenn aktiviert:
- GUI-Benutzer mit dem Standardpasswort werden zu `/change-password` weitergeleitet
- Das Passwortänderungsformular erfordert das aktuelle Passwort, ein neues Passwort (mindestens 8 Zeichen) und eine Bestätigung

Authentifizierte Benutzer können ihr Passwort jederzeit unter `/change-password` ändern.

---

## Benutzerverwaltung

Die Benutzerverwaltungs-API ermöglicht es Administratoren, Benutzer über REST-Endpunkte zu erstellen, zu aktualisieren und zu deaktivieren.

### Endpunkte

| Methode | Pfad | Beschreibung |
|---|---|---|
| `GET` | `/api/admin/users` | Alle Benutzer auflisten (ohne Passwort-Hashes) |
| `GET` | `/api/admin/users/{id}` | Einen bestimmten Benutzer abrufen |
| `POST` | `/api/admin/users` | Einen neuen Benutzer erstellen |
| `PUT` | `/api/admin/users/{id}` | Benutzerdetails aktualisieren (Rollen, displayName, E-Mail, aktiviert) |
| `PUT` | `/api/admin/users/{id}/password` | Passwort eines Benutzers ändern |
| `DELETE` | `/api/admin/users/{id}` | Einen Benutzer deaktivieren (Soft Delete) |

Alle Endpunkte erfordern `ROLE_ADMIN`. Siehe die [API-Referenz](API_REFERENCE.md) für Details zu Anfragen und Antworten.

### Sicherheitsregeln

- Der letzte verbleibende Admin-Benutzer kann nicht deaktiviert werden
- Die ADMIN-Rolle kann nicht vom letzten Admin-Benutzer entfernt werden
- Benutzernamen müssen eindeutig sein
- Passwörter müssen mindestens 8 Zeichen lang sein

---

## Audit-Protokollierung

Die Sicherheits-Audit-Protokollierung zeichnet Authentifizierungsereignisse für Compliance und Forensik auf. Standardmäßig deaktiviert.

| Variable | Standard | Beschreibung |
|---|---|---|
| `TAXONOMY_AUDIT_LOGGING` | `false` | Sicherheits-Audit-Protokollierung aktivieren |

Wenn aktiviert, werden die folgenden Ereignisse protokolliert:

| Ereignis | Log-Level | Format |
|---|---|---|
| Erfolgreiche Anmeldung | `INFO` | `LOGIN_SUCCESS user={username} ip={ip}` |
| Fehlgeschlagene Anmeldung | `WARN` | `LOGIN_FAILED user={username} ip={ip}` |
| Benutzer erstellt | `INFO` | `USER_CREATED user={username} roles={roles} by={admin}` |
| Benutzer aktualisiert | `INFO` | `USER_UPDATED user={username} by={admin}` |
| Benutzer deaktiviert | `INFO` | `USER_DISABLED user={username} by={admin}` |
| Passwort geändert | `INFO` | `USER_PASSWORD_CHANGED user={username} by={admin}` |

Aktivieren Sie die Protokollierung mit `TAXONOMY_AUDIT_LOGGING=true` für Produktions- oder Compliance-Umgebungen.

---

## Zusammenfassung der Sicherheits-Umgebungsvariablen

Alle sicherheitsrelevanten Umgebungsvariablen auf einen Blick:

| Variable | Standard | Beschreibung |
|---|---|---|
| `TAXONOMY_ADMIN_PASSWORD` | `admin` | Anmeldepasswort für den Standard-Admin-Benutzer |
| `ADMIN_PASSWORD` | *(leer)* | Token für die Schutzschicht des UI-Admin-Panels |
| `TAXONOMY_LOGIN_RATE_LIMIT` | `true` | Brute-Force-Schutz für `/login` aktivieren |
| `TAXONOMY_LOGIN_MAX_ATTEMPTS` | `5` | Fehlversuche vor der Sperrung |
| `TAXONOMY_LOGIN_LOCKOUT_SECONDS` | `300` | Sperrdauer (5 Minuten) |
| `TAXONOMY_REQUIRE_PASSWORD_CHANGE` | `false` | Passwortänderung bei der ersten Anmeldung erzwingen |
| `TAXONOMY_SWAGGER_PUBLIC` | `true` | Unauthentifizierten Zugriff auf Swagger UI erlauben |
| `TAXONOMY_AUDIT_LOGGING` | `false` | Sicherheitsereignisse im Anwendungslog protokollieren |

> ⚠️ **Für Produktionsbereitstellungen**: Ändern Sie immer `TAXONOMY_ADMIN_PASSWORD` vom Standardwert, setzen Sie `TAXONOMY_SWAGGER_PUBLIC=false` und aktivieren Sie `TAXONOMY_AUDIT_LOGGING=true`.

---

## Ablauf der Passwortänderung

Der Passwortänderungsmechanismus ist unter `POST /change-password` verfügbar:

1. Der Benutzer gibt das aktuelle Passwort, ein neues Passwort und eine Bestätigung ein
2. Der Server überprüft, ob das aktuelle Passwort übereinstimmt
3. Der Server überprüft, ob das neue Passwort die Anforderungen erfüllt
4. Das Passwort wird in der Datenbank aktualisiert
5. Wenn `TAXONOMY_REQUIRE_PASSWORD_CHANGE=true` gesetzt ist, wird das Erstanmeldungs-Flag zurückgesetzt

Der `ChangePasswordController` verarbeitet den Ablauf und leitet bei Erfolg auf die Hauptseite weiter.

---

## Keycloak-/OIDC-Integration (Verfügbar)

Die Anwendung unterstützt **Dual-Mode-Authentifizierung**: Form-Login (Standard) oder **Keycloak/OIDC** (über das `keycloak`-Spring-Profil). Wenn das `keycloak`-Profil aktiv ist, wird die Authentifizierung vollständig an Keycloak delegiert:

- **Browser (GUI)**: OAuth2-Login → Keycloak-Anmeldeseite → OIDC-Session
- **REST-API**: Bearer-JWT-Token im `Authorization`-Header (kein HTTP Basic)
- **Benutzerverwaltung**: Erfolgt über die Keycloak Admin Console (lokale Benutzerdatenbank wird nicht verwendet)
- **Passwortänderungen**: Weiterleitung zur Keycloak Account Console
- **SSO/SLO-Unterstützung**: Single Sign-On und Single Logout über RP-Initiated Logout

Das Drei-Rollen-Modell (USER, ARCHITECT, ADMIN) lässt sich direkt auf Keycloak-Realm-Rollen abbilden (`ROLE_USER`, `ROLE_ARCHITECT`, `ROLE_ADMIN`).

### Schnellstart

```bash
# Keycloak + PostgreSQL + Taxonomy mit OIDC starten
docker compose -f docker-compose-keycloak.yml up -d
# Zugriff: http://localhost:8080 → Weiterleitung zur Keycloak-Anmeldung
# Standard-Benutzer: admin/admin, architect/architect, user/user
```

### Keycloak-Modus aktivieren (ohne Docker Compose)

```bash
export SPRING_PROFILES_ACTIVE=keycloak
export KEYCLOAK_ISSUER_URI=http://your-keycloak:8180/realms/taxonomy
export KEYCLOAK_CLIENT_ID=taxonomy-app
export KEYCLOAK_CLIENT_SECRET=your-client-secret
```

Siehe [Keycloak- & SSO-Einrichtung](KEYCLOAK_SETUP.md) für vollständige Konfigurationsdetails und [Keycloak-Migrationsanleitung](KEYCLOAK_MIGRATION.md) für die Migration von Form-Login.

---

## Netzwerktopologie (Produktion)

Empfohlene Produktionsbereitstellung mit Defense-in-Depth:

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

### Netzwerkregeln

| Quelle | Ziel | Port | Protokoll | Zweck |
|---|---|---|---|---|
| Internet | Reverse Proxy | 443 | HTTPS | Benutzerzugriff |
| Reverse Proxy | Taxonomy App | 8080 | HTTP | Weitergeleitete Anfragen |
| Taxonomy App | PostgreSQL | 5432 | TCP | Datenbank |
| Taxonomy App | Keycloak | 8180 | HTTP/HTTPS | OIDC-Token-Validierung |
| Taxonomy App | LLM API | 443 | HTTPS | KI-Analyse (optional) |
| Keycloak | LDAP/AD | 389/636 | LDAP/LDAPS | Benutzer-Föderation |

---

## Härtungsmaßnahmen

### Anwendungshärtung

| Maßnahme | Status | Konfiguration |
|---|---|---|
| **HTTPS erzwungen** | ✅ Über Reverse Proxy | HSTS-Header wird von der Anwendung gesendet |
| **CSRF-Schutz** | ✅ Standardmäßig aktiviert | Thymeleaf fügt Tokens automatisch ein |
| **Brute-Force-Schutz** | ✅ Standardmäßig aktiviert | `TAXONOMY_LOGIN_RATE_LIMIT=true` |
| **Passwort-Hashing** | ✅ BCrypt | Spring-Security-Standard |
| **Sicherheitsheader** | ✅ Immer aktiviert | X-Content-Type-Options, X-Frame-Options, HSTS, Referrer-Policy |
| **Eingabevalidierung** | ✅ Größenlimits | `TAXONOMY_LIMITS_MAX_BUSINESS_TEXT=5000` |
| **Audit-Protokollierung** | ✅ Produktionsprofil | `TAXONOMY_AUDIT_LOGGING=true` |
| **Swagger deaktiviert** | ✅ Produktionsprofil | `TAXONOMY_SPRINGDOC_ENABLED=false` |
| **Admin-Panel-Schutz** | ✅ Zwei-Schichten-Schutz | Rollenbasiert + Token-basiert |

### JVM-Härtung

```bash
# Recommended JAVA_OPTS for production
JAVA_OPTS="-XX:+UseG1GC \
  -XX:MaxRAMPercentage=75.0 \
  -Djava.security.egd=file:/dev/urandom \
  -Dfile.encoding=UTF-8"
```

### Container-Härtung

```dockerfile
# Run as non-root user
RUN addgroup -S taxonomy && adduser -S taxonomy -G taxonomy
USER taxonomy

# Read-only filesystem (data volume is writable)
# Use: docker run --read-only --tmpfs /tmp -v taxonomy-data:/app/data
```

### Datenbank-Härtung

- Verwenden Sie einen dedizierten Datenbankbenutzer mit minimalen Rechten (SELECT, INSERT, UPDATE, DELETE auf Anwendungstabellen)
- Aktivieren Sie TLS für Datenbankverbindungen
- Beschränken Sie den Netzwerkzugriff auf den Datenbankserver
- Aktivieren Sie die Datenbank-Audit-Protokollierung

---

## SBOM (Software Bill of Materials)

Eine CycloneDX-SBOM wird automatisch während des Build-Prozesses generiert:

```bash
mvn package
# Output: target/taxonomy-sbom.json and target/taxonomy-sbom.xml
```

Die SBOM listet alle direkten und transitiven Abhängigkeiten mit:
- Paketname und Version
- Lizenzinformationen
- Kryptographische Hashes

Überprüfen Sie die SBOM im Rahmen der BSI-IT-Grundschutz-Anforderungen an die Software-Lieferkette.

---

## Arbeitsbereich-Zugriffsrechte

Wenn die Multi-Benutzer-Arbeitsbereichisolierung aktiviert ist (siehe [Repository-Topologie](REPOSITORY_TOPOLOGY.md)), gilt die folgende Zugriffskontrollmatrix für Arbeitsbereichoperationen:

| Aktion | USER | ARCHITECT | ADMIN | Arbeitsbereich-Eigentümer |
|---|:---:|:---:|:---:|:---:|
| Arbeitsbereich erstellen | ✅ | ✅ | ✅ | — |
| Eigenen Arbeitsbereich lesen | ✅ | ✅ | ✅ | ✅ |
| Arbeitsbereich anderer Benutzer lesen | ❌ | ❌ | ✅ | ❌ |
| Arbeitsbereich teilen | ❌ | ✅ | ✅ | ✅ |
| DSL im Arbeitsbereich committen | ❌ | ✅ | ✅ | ✅ (wenn ARCHITECT) |
| Arbeitsbereich veröffentlichen | ❌ | ✅ | ✅ | ✅ (wenn ARCHITECT) |
| Von geteiltem Bereich synchronisieren | ✅ | ✅ | ✅ | ✅ |
| Arbeitsbereich löschen | ❌ | ❌ | ✅ | ✅ (nur eigene) |
| Arbeitsbereich-Statistiken anzeigen | ✅ | ✅ | ✅ | ✅ |

**Grundprinzipien:**
- **Branch-Level-Isolierung**: Jeder Benutzer arbeitet auf seinem eigenen Git-Branch innerhalb des Arbeitsbereichs
- **Explizite Synchronisierung**: Änderungen fließen nur durch explizite Veröffentlichungs-/Synchronisierungsaktionen zwischen Arbeitsbereichen
- **Gemeinsamer Draft-Branch**: Der `draft`-Branch dient als gemeinsamer Kollaborationsbereich
- **ADMIN-Überschreibung**: Administratoren können auf alle Arbeitsbereiche zugreifen, um Fehler zu beheben und Audits durchzuführen

---

## Verwandte Dokumentation

- [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md) — vollständige Liste der Umgebungsvariablen einschließlich Sicherheitseinstellungen
- [Bereitstellungsleitfaden](DEPLOYMENT_GUIDE.md) — Docker- und Render.com-Bereitstellung mit Sicherheitsaspekten
- [Bereitstellungscheckliste](DEPLOYMENT_CHECKLIST.md) — Checkliste für die Bereitstellung in Behörden
- [Keycloak- & SSO-Einrichtung](KEYCLOAK_SETUP.md) — Keycloak-Integration und SSO-Federation
- [Datenschutz](DATA_PROTECTION.md) — GDPR-/DSGVO-Dokumentation
- [KI-Transparenz](AI_TRANSPARENCY.md) — KI-Modelltransparenz und Datenflüsse
- [BSI-KI-Checkliste](BSI_KI_CHECKLIST.md) — BSI-Kriteriencheckliste für KI-Modelle
- [Digitale Souveränität](DIGITAL_SOVEREIGNTY.md) — Digitale Souveränität und openCode-Kompatibilität
- [Betriebsleitfaden](OPERATIONS_GUIDE.md) — Überwachung, Sicherung, Wiederherstellung
- [API-Referenz](API_REFERENCE.md) — Authentifizierungsanforderungen der Endpunkte
- [Einstellungen](PREFERENCES.md) — Laufzeitkonfiguration mit Audit-Trail

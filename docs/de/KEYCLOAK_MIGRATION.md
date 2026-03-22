# Migration von Form-Login zu Keycloak

Diese Anleitung beschreibt, wie Sie von der Standard-Form-Login-Authentifizierung zur Keycloak/OIDC-Authentifizierung migrieren.

---

## Schnellstart

1. **Keycloak starten** (enthält vorkonfiguriertes Realm mit Rollen und Benutzern):
   ```bash
   docker compose -f docker-compose-keycloak.yml up -d
   ```

2. **Auf die Anwendung zugreifen**: http://localhost:8080 → Weiterleitung zur Keycloak-Anmeldeseite

3. **Standard-Benutzer** (erstellt durch Realm-Import):
   | Benutzername | Passwort | Rollen |
   |---|---|---|
   | `admin` | `admin` | USER, ARCHITECT, ADMIN |
   | `architect` | `architect` | USER, ARCHITECT |
   | `user` | `user` | USER |

---

## Was sich ändert, wenn das Keycloak-Profil aktiv ist

| Funktion | Form-Login (Standard) | Keycloak-Modus |
|---|---|---|
| Anmeldeseite | Spring-Standard `/login`-Formular | Keycloak-Anmeldeseite (OIDC-Redirect) |
| API-Authentifizierung | HTTP Basic (`curl -u admin:admin`) | JWT Bearer Token (`Authorization: Bearer <token>`) |
| Benutzerverwaltung | `/api/admin/users` REST-API | Keycloak Admin Console |
| Passwortänderungen | `/change-password`-Seite | Keycloak Account Console |
| Benutzerdatenbank | Lokal (HSQLDB/PostgreSQL) | Keycloak (keine lokale Benutzer-DB) |
| Brute-Force-Schutz | `LoginRateLimitFilter` | Keycloak eingebauter Schutz |
| Standard-Admin-Benutzer | Erstellt durch `SecurityDataInitializer` | Erstellt im Keycloak-Realm |

---

## Eigenständige Bereitstellung (ohne Docker Compose)

Setzen Sie die folgenden Umgebungsvariablen:

```bash
# Keycloak-Profil aktivieren
export SPRING_PROFILES_ACTIVE=keycloak

# Keycloak-Realm-Konfiguration
export KEYCLOAK_ISSUER_URI=http://your-keycloak-host:8180/realms/taxonomy
export KEYCLOAK_JWK_SET_URI=http://your-keycloak-host:8180/realms/taxonomy/protocol/openid-connect/certs
export KEYCLOAK_CLIENT_ID=taxonomy-app
export KEYCLOAK_CLIENT_SECRET=your-client-secret

# Optional: Keycloak Admin Console URL (für Passwortänderungs-Weiterleitungen)
export KEYCLOAK_ADMIN_URL=http://your-keycloak-host:8180
```

---

## Bestehende Benutzer zuordnen

Wenn Sie von Form-Login zu Keycloak migrieren, erstellen Sie passende Benutzer im Keycloak-Realm mit denselben Benutzernamen und Rollen:

1. Keycloak Admin Console öffnen → Realm: `taxonomy` → Benutzer
2. Jeden Benutzer mit demselben Benutzernamen erstellen
3. Realm-Rollen zuweisen: `ROLE_USER`, `ROLE_ARCHITECT`, `ROLE_ADMIN` entsprechend
4. Anfangspasswörter setzen

**Wichtig:** Keycloak-Benutzernamen müssen mit den Benutzernamen in bestehenden Workspace-Daten übereinstimmen. Das Workspace-System löst den authentifizierten Benutzernamen über `Authentication.getName()` auf, der dem Keycloak-Claim `preferred_username` zugeordnet wird.

---

## REST-API-Authentifizierung mit JWT

Im Keycloak-Modus authentifizieren sich REST-API-Clients mit JWT-Bearer-Tokens statt HTTP Basic:

```bash
# 1. Token von Keycloak erhalten
TOKEN=$(curl -s -X POST \
  "http://localhost:8180/realms/taxonomy/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=taxonomy-app" \
  -d "client_secret=taxonomy-dev-secret" \
  -d "username=admin" \
  -d "password=admin" \
  -d "grant_type=password" \
  | jq -r '.access_token')

# 2. Token in API-Aufrufen verwenden
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/taxonomy
```

---

## Zurück zu Form-Login wechseln

Um auf Form-Login zurückzuwechseln, entfernen Sie einfach das `keycloak`-Profil:

```bash
# keycloak aus SPRING_PROFILES_ACTIVE entfernen
export SPRING_PROFILES_ACTIVE=hsqldb  # oder postgres, etc.
```

Die lokale Benutzerdatenbank und die Form-Login-Komponenten werden automatisch wieder aktiviert, wenn das `keycloak`-Profil nicht aktiv ist.

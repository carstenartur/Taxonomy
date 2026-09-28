# Local user management / Lokale Benutzerverwaltung

## Deutsch

Die Seite **Administration → Benutzer verwalten** (`/admin/users`) ergänzt die
bestehende lokale Benutzerverwaltung. Sie verwendet `UserManagementService` und
legt weder eine zweite Benutzerablage noch einen neuen Anmeldemechanismus an.

### Aktivierung

Das zusätzliche Spring-Profil `local-user-management` ist ausdrücklich erforderlich.
Vorhandene Datenbank- und Deployment-Profile beibehalten, beispielsweise:

```properties
SPRING_PROFILES_ACTIVE=production,postgres,local-user-management
```

Als Administrator anmelden und im Administrationsbereich **Benutzer verwalten**
öffnen. Der direkte Pfad lautet `/admin/users`, bei einem Kontextpfad beispielsweise
`/taxonomy/admin/users`. Die Oberfläche ist auf Deutsch und Englisch verfügbar.

| Konfiguration | Neue Seite und Formularaktionen | Bisherige lokale REST-API |
|---|---|---|
| Lokale Anmeldung ohne Zusatzprofil | Aus | Unverändert verfügbar |
| `local-user-management`, lokale Benutzer aktiviert | Nur ADMIN | Nur ADMIN |
| `taxonomy.security.local-users-enabled=false` | Aus | Aus |
| `keycloak`, auch zusammen mit `local-user-management` | Aus | Aus |

Der bestehende Schalter `TAXONOMY_SECURITY_LOCAL_USERS_ENABLED=false` kann die
lokale Verwaltung zusätzlich vollständig deaktivieren. Er schaltet nicht den
Anmeldemechanismus selbst um. Das Profil erzwingt diesen Schalter **nicht** auf `true`.
Auch ein irrtümliches `true` aktiviert im Keycloak-Modus weder die lokale Seite noch
die lokale REST-Verwaltung. Die Reihenfolge der Profile spielt dabei keine Rolle. Unter `production,keycloak`
weist der Startschutz widersprüchliche lokale Verwaltungsprofile/-einstellungen
bereits beim Start zurück; es wird kein lokales Bootstrap-Passwort verlangt.

### Active Directory und andere externe Konten

Die bestehende AD/LDAP-Anbindung erfolgt über die Benutzerföderation von Keycloak.
Für diese Betriebsart das vorhandene `keycloak`-Profil und die zugehörige
OIDC-Konfiguration verwenden, zum Beispiel `production,postgres,keycloak`.
Benutzer und Passwörter werden dann im Verzeichnis beziehungsweise Identitätsanbieter
verwaltet. Dieses Feature richtet **keine direkte AD/LDAP-Anmeldung** ein. Ein anderes
Authentifizierungsmodul muss tatsächlich eingerichtet werden; allein das Weglassen
des UI-Profils aktiviert keinen Verzeichnisdienst.

Es gibt keine Erreichbarkeitsprüfung des Verzeichnisservers und keinen automatischen
Rückfall auf lokale Konten bei einer Störung. Bei einem späteren neuen externen
Anmeldemodus muss dessen Ausschluss auch in den zentralen Zugriffsvertrag
`LocalUserManagementAccess` aufgenommen und getestet werden.

### Funktionen und Grenzen

Die Seite bietet Anlage, Bearbeiten von Anzeigename/E-Mail/Rollen, Passwortzurücksetzen
sowie Aktivieren und Deaktivieren mit Bestätigungsseite. Benutzernamen bleiben stabil;
ein Deaktivieren löscht weder Konto noch Historie. Der bestehende Schutz des letzten
aktiven Administrators bleibt serverseitig maßgeblich.

Alle Formularaktionen benötigen ADMIN und ein CSRF-Token. GET-Aufrufe verändern keine
Konten. Benutzerwerte werden als Text ausgegeben; Passwörter gelangen nicht in
Formularmodelle, Fehlerseiten oder Weiterleitungsparameter. Antworten sind `no-store`.
Neue Passwörter benötigen mindestens 12 Zeichen und dürfen wegen BCrypt höchstens
72 UTF-8-Bytes belegen. Es werden keine Passwörter per E-Mail verschickt.

Das Zusatzprofil setzt den vorhandenen verpflichtenden Passwortwechsel standardmäßig
auf `true`; `TAXONOMY_REQUIRE_PASSWORD_CHANGE` bleibt eine bewusste Überschreibung.
Die Seite weist auf eine deaktivierte Wechselpflicht hin. Das betrifft neu angelegte
und zurückgesetzte Kennwörter; bestehende normale Kennwörter werden nicht ersetzt.
`TAXONOMY_ADMIN_PASSWORD` dient weiterhin nur der initialen Administratoranlage.

**Bestehende Browsersitzungen werden durch die vorhandene Kontenverwaltung nicht
widerrufen.** Deaktivierung verhindert neue Anmeldungen; Rollenänderungen und
Passwortzurücksetzen sind keine garantierte, sofortige Abmeldung aller Sitzungen.
Die Bestätigungsseite weist beim Deaktivieren ausdrücklich darauf hin. Verteilter
Sitzungswiderruf und eine neue Verzeichnisanbindung gehören nicht zu dieser Erweiterung.

Für dauerhafte Konten eine persistente Datenbank und eine nicht destruktive
Schemaeinstellung verwenden. Die HSQLDB-Entwicklungseinstellung im Arbeitsspeicher
mit `ddl-auto=create` ist dafür nicht geeignet. Secrets aus dem Deployment-Secretstore
beziehen und nur über HTTPS betreiben. Siehe [Sicherheit](de/SECURITY.md) und
[Konfigurationsreferenz](de/CONFIGURATION_REFERENCE.md).

## English

Enable the additional `local-user-management` Spring profile alongside existing
local authentication, database and deployment profiles. Administrators then find
**Administration → Manage users** or `/admin/users` (context-path aware). The page,
including every POST form handler, requires ADMIN, the opt-in profile and the existing
`taxonomy.security.local-users-enabled=true` setting (default in local mode).

The `keycloak` profile always excludes the page and local REST administration,
regardless of profile order or an accidentally enabled local-user property. The
existing REST API remains available in ordinary local mode without the UI profile.
Setting `TAXONOMY_SECURITY_LOCAL_USERS_ENABLED=false` disables both management
surfaces but does not itself configure external authentication.

Existing AD/LDAP integration is through Keycloak federation. This feature neither
implements a native LDAP provider nor falls back to local accounts during directory
outages. Manage external identities at their identity provider.

The UI reuses the existing account service for create, edit, roles, reset password,
enable and disable. Forms retain CSRF protection, escape account values, never echo
passwords, use no-store responses and require explicit status-change confirmation.
The last enabled administrator remains protected by the service. The opt-in profile
requires replacement of newly created/reset passwords by default; the existing
`TAXONOMY_REQUIRE_PASSWORD_CHANGE` override is respected and displayed.

Account disabling prevents new logins but **does not revoke existing browser sessions**.
No distributed session revocation, email delivery, second user store or new identity
provider is introduced. Use persistent storage, deployment secrets and HTTPS.

## Verification

`LocalUserManagementProfileTest` checks bean registration and capability agreement,
including conflicting Keycloak profile order and disabled local accounts.
`UserManagementPageTest` checks rendering, ADMIN-only method security, CSRF, validation,
password non-disclosure, context paths, immutable usernames and service error handling.
`UserManagementFormTest` checks the safe profile projection and field validation.

```bash
./mvnw verify -DexcludedGroups="real-llm"
cd .github
npm run test:local-user-management
```

The navigation tests execute the actual JavaScript and are included in both
`verify:ui` and `verify:ui-contracts`. They cover opt-in/admin visibility, context-relative
links, idempotence, capability changes and failed capability refreshes.

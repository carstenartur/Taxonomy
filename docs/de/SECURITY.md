# Sicherheit

Dieses Dokument beschreibt das Authentifizierungs-, Autorisierungs-, Zugangsdaten- und Bereitstellungssicherheitsmodell des Taxonomy Architecture Analyzer.

Für vertrauliche Schwachstellenmeldungen gilt die zentrale [Security Policy](../../SECURITY.md). Vermutete Schwachstellen oder Geheimnisse dürfen nicht in einem öffentlichen Issue veröffentlicht werden.

## Authentifizierungsmodi

Die Anwendung unterstützt zwei gegenseitig ausschließende Sicherheitsmodi.

### Lokale Benutzerverwaltung

Dieser Modus ist aktiv, solange das Spring-Profil `keycloak` nicht verwendet wird.

- Browser-Benutzer authentifizieren sich über Spring Security Form Login und eine serverseitige Sitzung.
- REST-Clients können HTTP Basic verwenden.
- Passwörter werden als BCrypt-Hashes in der Anwendungsdatenbank gespeichert.
- Zustandsändernde Browser-Anfragen bleiben durch CSRF-Tokens geschützt.
- Eine Anfrage unter `/api/**` ist nur dann von CSRF ausgenommen, wenn sie einen expliziten Basic- oder Bearer-`Authorization`-Header enthält und damit als zustandsloser API-Aufruf behandelt wird.

### Keycloak/OIDC

Mit dem Spring-Profil `keycloak` wird die Authentifizierung an Keycloak delegiert.

- Browser-Anmeldung erfolgt über OAuth 2.0/OIDC.
- REST-Clients verwenden Bearer-JWTs.
- Realm-Rollen werden auf die Anwendungsrollen USER, ARCHITECT und ADMIN abgebildet.
- Die lokale Passwortverwaltung ist in diesem Profil nicht das führende Identitätsmanagement.

Details stehen in der [Keycloak-Anleitung](KEYCLOAK_SETUP.md).

## Administrator-Bootstrap

Im lokalen Benutzermodus wird beim ersten Start einer neuen Datenbank das Konto `admin` angelegt. Es gibt **kein eingebautes oder dokumentiertes wiederverwendbares Passwort**.

| Situation | Verhalten |
|---|---|
| `TAXONOMY_ADMIN_PASSWORD` ist in einer Nicht-Produktionsumgebung leer | Ein hochentropisches einmaliges Bootstrap-Passwort wird erzeugt und einmal im Startprotokoll ausgegeben. Das Konto muss das Passwort ersetzen. |
| `TAXONOMY_ADMIN_PASSWORD` ist gesetzt | Der konfigurierte Wert wird für die initiale Kontoerstellung verwendet. `TAXONOMY_REQUIRE_PASSWORD_CHANGE` steuert, ob beim ersten Login ein Wechsel erforderlich ist. |
| Eine historische Datenbank enthält noch das entfernte Kennwort `admin` | Das Konto wird einmalig zum Passwortwechsel verpflichtet, ohne normale Konten bei jedem Neustart erneut zu sperren. |
| Das Profil `production` ist aktiv | Der Start wird vor der Kontoerstellung abgebrochen, wenn das Passwort fehlt, einem bekannten Platzhalter entspricht oder weniger als 16 Zeichen enthält. |

Lokale Entwicklung mit explizitem Secret:

```bash
export TAXONOMY_ADMIN_PASSWORD='ein-eindeutiges-lokales-entwicklungssecret'
./mvnw -pl taxonomy-app -am spring-boot:run
```

Übernehmen Sie niemals Passwörter aus Dokumentation, Tests, Screenshots oder Beispielen in eine Bereitstellung.

## Rollen und Berechtigungen

| Rolle | Vorgesehene Berechtigungen |
|---|---|
| `USER` | Taxonomie durchsuchen, analysieren, Graphen ansehen und erlaubte Exporte verwenden. |
| `ARCHITECT` | USER-Berechtigungen plus Änderungen an Architektur, Relationen, DSL und Versionierung. |
| `ADMIN` | ARCHITECT-Berechtigungen plus Administration, Diagnose und Benutzerverwaltung. |

Die Autorisierung wird serverseitig durch Spring Security und Methodensicherheit durchgesetzt. Das Ausblenden eines Bedienelements ist keine Sicherheitsgrenze.

## CSRF und API-Clients

Browser-Sitzungen behalten den CSRF-Schutz auch für zustandsändernde API-Aufrufe. Von der Anwendung ausgelieferter JavaScript-Code verwendet das CSRF-Token der Seite.

Eine REST-Anfrage gilt nur dann als zustandslos, wenn beide Bedingungen erfüllt sind:

1. Der Pfad beginnt mit `/api/`.
2. Die Anfrage enthält einen expliziten `Authorization: Basic ...`- oder `Authorization: Bearer ...`-Header.

Das bloße Fehlen einer Sitzung deaktiviert den CSRF-Schutz nicht.

## Passwort- und Login-Schutz

Der lokale Benutzermodus bietet:

- BCrypt-Passworthashing;
- konfigurierbare Begrenzung fehlgeschlagener Logins;
- temporäre IP-Sperren nach wiederholten Fehlversuchen;
- verpflichtenden Passwortwechsel für Bootstrap-Konten;
- Schutz vor dem Deaktivieren des letzten Administrators;
- optionales Security-Audit-Logging.

Wichtige Einstellungen:

| Umgebungsvariable | Zweck |
|---|---|
| `TAXONOMY_ADMIN_PASSWORD` | Initiales lokales Administratorpasswort; in Produktion erforderlich und geprüft. |
| `TAXONOMY_REQUIRE_PASSWORD_CHANGE` | Erzwingt den Wechsel eines explizit konfigurierten Initialpassworts. |
| `TAXONOMY_LOGIN_RATE_LIMIT` | Aktiviert die Begrenzung fehlgeschlagener Logins. |
| `TAXONOMY_LOGIN_MAX_ATTEMPTS` | Zulässige Fehlversuche vor der Sperre. |
| `TAXONOMY_LOGIN_LOCKOUT_SECONDS` | Dauer der Sperre. |
| `TAXONOMY_AUDIT_LOGGING` | Aktiviert Security-Ereignisprotokollierung. |
| `TAXONOMY_SWAGGER_PUBLIC` | Erlaubt öffentlichen Swagger-Zugriff, sofern SpringDoc aktiv ist. |

## Zugangsdaten für externes Git

Zugangsdaten eines externen kanonischen Git-Repositorys sind Deployment-Secrets und keine Repository-Daten.

| Umgebungsvariable | Zweck |
|---|---|
| `TAXONOMY_EXTERNAL_GIT_USERNAME` | Transport-Benutzername; Standard `oauth2`, wenn nicht gesetzt. |
| `TAXONOMY_EXTERNAL_GIT_TOKEN` | Zugriffstoken für den JGit-Transport. |

Das Token wird nicht in JPA-Entitäten gespeichert, nicht über Status-APIs zurückgegeben und nicht protokolliert. Historische Klartextwerte werden beim Start entfernt; kann die Bereinigung nicht gespeichert werden, bricht der Start ab.

Repository-URLs mit eingebetteten HTTP-Zugangsdaten oder Passwörtern werden abgewiesen. Remote-URL und Secret müssen getrennt konfiguriert werden.

## Security-Header

Die lokale Security-Konfiguration setzt unter anderem:

- `X-Content-Type-Options: nosniff`;
- Same-Origin-Frame-Schutz;
- HTTP Strict Transport Security;
- `Referrer-Policy: strict-origin-when-cross-origin`.

HSTS wirkt nur, wenn Clients die Bereitstellung über HTTPS erreichen.

## Anforderungen an Produktion

Verwenden Sie die unterstützte Produktionskonfiguration und prüfen Sie die [Deployment-Checkliste](DEPLOYMENT_CHECKLIST.md). Mindestens erforderlich sind:

- TLS-Terminierung über einen vertrauenswürdigen Reverse Proxy wie Caddy;
- kein öffentlicher Zugriff auf Anwendungsport, Datenbank, Git-Speicher, Lucene-Index und Backups;
- eindeutige Secrets aus dem Secret Store der Plattform;
- deaktivierter oder authentifizierter Swagger-Zugriff, sofern nicht ausdrücklich benötigt;
- geeignete Authentifizierungs- und Audit-Protokollierung;
- Prüfung von SBOM, Vulnerability Scan und Release-Provenienz;
- getestete Wiederherstellung von Datenbank, Git-Repository, Index, Konfiguration und Backups;
- Keycloak oder getrennte Benutzerkonten statt eines gemeinsam verwendeten Administratorkontos.

Das Produktionsprofil bricht bei unsicheren Bootstrap-Zugangsdaten bewusst fehlgeschlossen ab.

## Öffentliche Endpunkte

Die Erreichbarkeit von Health-, Actuator-, OpenAPI- und statischen Ressourcen hängt von aktivem Profil und Deployment-Konfiguration ab. Ein in der Entwicklung erreichbarer Endpunkt ist nicht automatisch für eine öffentliche Produktionsbereitstellung geeignet.

## Sicherheitsprüfung

Der kanonische Maven-Build prüft unter anderem Authentifizierung, Autorisierung, Passwort-Bootstrap und Migration, Produktionshärtung, Container-Start, Persistenz, Browser-Abläufe, Barrierefreiheit und ausgewählte Fehlerpfade:

```bash
./mvnw verify -Pci -DrunOnnxTests=true
```

Zusätzlich laufen CodeQL und Trivy-Prüfungen für Abhängigkeiten, Secrets und Fehlkonfigurationen. Diese Kontrollen ersetzen keine Bedrohungsanalyse, Deployment-Prüfung, Penetrationstests oder Incident Response.

## Weiterführende Dokumentation

- [Zentrale Security Policy](../../SECURITY.md)
- [Deployment-Anleitung](DEPLOYMENT_GUIDE.md)
- [Deployment-Checkliste](DEPLOYMENT_CHECKLIST.md)
- [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md)
- [Datenschutz](DATA_PROTECTION.md)
- [Keycloak-Anleitung](KEYCLOAK_SETUP.md)

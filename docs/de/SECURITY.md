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
- Nur durch unterstützte zustandslose HTTP-Basic- oder Keycloak-Bearer-Zugangsdaten authentifizierte Anfragen kommen für eine CSRF-Ausnahme infrage. Wiederhergestellte Browser-Sitzungen behalten den CSRF-Schutz; ein ignorierter `Authorization`-Header begründet keine Ausnahme.

### Keycloak/OIDC

Mit dem Spring-Profil `keycloak` wird die Authentifizierung an Keycloak delegiert.

- Browser-Anmeldung erfolgt über OAuth 2.0/OIDC.
- REST-Clients verwenden Bearer-JWTs.
- Der Ressourcenserver akzeptiert Bearer-Zugangsdaten standardmäßig bis zu einer Länge von 8.192 Zeichen. Über `TAXONOMY_BEARER_MAX_TOKEN_LENGTH` lässt sich eine positive Ganzzahl als Grenze setzen. Sie gilt auch für individuell konfigurierte Token-Resolver. Deklariert eine Anfrage Bearer-Authentifizierung und liefert ihr Resolver keine Zugangsdaten, antwortet der Server mit HTTP 401.
- Vertrauenswürdige Rollen-Claims werden auf READER, USER (Analyst), ARCHITECT und ADMIN abgebildet. Bearer-Tokens behalten zusätzlich explizite OAuth-Authorities aus `scope`/`scp`.
- Die lokale Passwortverwaltung ist in diesem Profil nicht das führende Identitätsmanagement.

Details stehen in der [Keycloak-Anleitung](KEYCLOAK_SETUP.md).

## Administrator-Bootstrap

Im lokalen Benutzermodus wird beim ersten Start einer neuen Datenbank das Konto `admin` angelegt. Es gibt **kein eingebautes oder dokumentiertes wiederverwendbares Passwort**.

| Situation | Verhalten |
|---|---|
| `TAXONOMY_ADMIN_PASSWORD` ist in einer Nicht-Produktionsumgebung leer | Ein hochentropisches einmaliges Bootstrap-Passwort wird in eine eindeutig benannte temporäre Datei geschrieben, die nur für den Prozesseigentümer zugänglich ist. Im Startprotokoll erscheint ausschließlich der absolute Dateipfad. Das Konto muss das Passwort ersetzen; nach dem erfolgreich committeten Administrator-Passwortwechsel wird die Datei entfernt. |
| `TAXONOMY_ADMIN_PASSWORD` ist gesetzt | Der konfigurierte Wert wird für die initiale Kontoerstellung verwendet. `TAXONOMY_REQUIRE_PASSWORD_CHANGE` steuert, ob beim ersten Login ein Wechsel erforderlich ist. |
| Eine historische Datenbank enthält noch das entfernte Kennwort `admin` | Das Konto wird einmalig zum Passwortwechsel verpflichtet, ohne normale Konten bei jedem Neustart erneut zu sperren. |
| Das Profil `production` ist aktiv | Der Start wird vor der Kontoerstellung abgebrochen, wenn das Passwort fehlt, einem bekannten Platzhalter entspricht oder weniger als 16 Zeichen enthält. |

Lokale Entwicklung mit explizitem Secret:

```bash
export TAXONOMY_ADMIN_PASSWORD='ein-eindeutiges-lokales-entwicklungssecret'
./mvnw -pl taxonomy-app -am spring-boot:run
```

Lesen Sie eine automatisch erzeugte Zugangsdaten-Datei ausschließlich mit dem vertrauenswürdigen Betriebssystemkonto des Anwendungsprozesses. Kann das Dateisystem weder POSIX-Eigentümerrechte noch eine Eigentümer-ACL erzwingen, bricht die Administratoranlage fail-closed ab. Übernehmen Sie niemals Passwörter aus Dokumentation, Tests, Screenshots oder Beispielen in eine Bereitstellung.

## Rollen und Berechtigungen

| Fähigkeit | Gespeicherte Rolle / Spring Authority | Vorgesehene Berechtigungen |
|---|---|---|
| Leser | `READER` / `ROLE_READER` | Architektur und bestehende Analysen lesen; erlaubte Exporte und autorisierte Backup-Downloads nutzen. |
| Analyst | `USER` / `ROLE_USER` | Lesen und exportieren; Analysen ausführen und persönliche Analyseentwürfe verwalten. Aus Kompatibilitätsgründen bleibt `USER` der gespeicherte Rollenname. |
| Architekt | `ARCHITECT` / `ROLE_ARCHITECT` | Lesen, analysieren, Fachdaten anlegen/ändern/löschen sowie Architekturänderungen importieren und veröffentlichen. |
| Administrator | `ADMIN` / `ROLE_ADMIN` | Lesen/exportieren sowie technische Administration, Benutzer-/Sitzungsverwaltung und Diagnose. Administration allein gewährt weder Analyseausführung noch fachliche Bearbeitung. |

Rollen werden unabhängig vergeben. Ein Administrator benötigt zur Architekturbearbeitung ausdrücklich `ROLE_ARCHITECT`, zur Analyseausführung `ROLE_USER` oder `ROLE_ARCHITECT`. Bestehende Konten behalten ihre expliziten Mehrfachzuweisungen. Der lokale Bootstrap-Administrator erhält ausdrücklich `ROLE_USER`, `ROLE_ARCHITECT` und `ROLE_ADMIN`; daraus ergibt sich keine ADMIN-Rollenhierarchie. Der lokale Start legt `ROLE_READER` idempotent an; die Benutzerverwaltung bietet alle vier Rollen an.

Spring Security verlangt für jede berechtigungsgeschützte REST-Operation eine erlaubte Anwendungsrolle in **jeder erforderlichen Berechtigungsgruppe**. Ein Bearer-JWT benötigt zusätzlich **alle aufgeführten OAuth-Scopes**: erlaubte Rolle innerhalb jeder Gruppe UND alle expliziten Scopes. Rollen-Tokens ohne Scope erhalten keinen Fallback; ein Scope ersetzt keine fehlende Rolle. Lokales HTTP Basic und Browser-Sitzungen verwenden Rollen mit den bestehenden CSRF-Regeln. Sind Fähigkeitsalternativen angegeben, muss eine vollständige Alternative passen; innerhalb dieser Alternative sind alle Rollengruppen und Scopes erforderlich. Bedingte Anforderungen gelten, sobald ihre Bedingung zutrifft. Eigene Authentifizierungs-/Passwort-Endpunkte und Preflight folgen den jeweiligen authentifizierten/öffentlichen Regeln des gemeinsamen Katalogs.

| OAuth-Scope | Erlaubte Anwendungsrollen |
|---|---|
| `architecture:read` | READER, USER, ARCHITECT, ADMIN |
| `architecture:write`, `architecture:delete`, `architecture:publish` | ARCHITECT |
| `analysis:read` | READER, USER, ARCHITECT, ADMIN |
| `analysis:run` | USER, ARCHITECT |
| `integration:import` | ARCHITECT |
| `integration:export` | READER, USER, ARCHITECT, ADMIN |
| `repository:manage` | ARCHITECT, ADMIN, zusätzlich OWNER im exakten Repository |
| `backup:read` | READER, USER, ARCHITECT, ADMIN |
| `backup:create` | USER, ARCHITECT, ADMIN |
| `admin:users`, `admin:configuration`, `admin:diagnostics` | ADMIN |

Scopes klassifizieren vollständige Endpunktoperationen, nicht einzelne Änderungen innerhalb einer Bearbeitung. `architecture:delete` schützt dedizierte REST-DELETE-Endpunkte. `architecture:write` autorisiert die vollständige Befehls-/DSL-Bearbeitung, die Inhalte entfernen oder ersetzen kann; dies ist keine Policy für semantische Differenzen. Lesende Scopes erlauben weiterhin keine fachlichen Änderungen.

Berechtigungsgruppen können gemeinsam erforderlich sein: `POST /api/workspace/external/push` und `/api/workspace/external/full-sync` benötigen bei Bearer-Aufrufen sowohl ARCHITECT mit `architecture:publish` **als auch** ADMIN mit `admin:configuration`. Sitzungen benötigen beide Rollen. Keine der Gruppen allein genügt.

Die HTTP-Methode allein bestimmt nicht die Wirkung. `GET /api/analyze-stream` und `GET /api/analyze-node` starten neue LLM-Arbeit und benötigen ANALYSIS_RUN (USER oder ARCHITECT; Bearer-Scope `analysis:run`). Auch das verzögerte Anlegen der Standard-Arbeitskopie bei `GET /api/workspace/current` benötigt ANALYSIS_RUN. Leser ohne bereite Arbeitskopie erhalten HTTP 204 und bleiben im zentralen Lesekontext; vorhandene bereite Arbeitskopien bleiben unter den Ressourcenprüfungen lesbar.

Workspace-Lebenszyklusoperationen verwenden explizite Berechtigungen; bestehende Eigentums- und Ressourcenprüfungen gelten weiter:

| Operation | Erforderliche Berechtigung (Bearer-Scope) |
|---|---|
| Eigenen Workspace anlegen, archivieren, umbenennen oder dessen Beschreibung ändern; eigenen Navigationsverlauf leeren | `ANALYSIS_RUN` (`analysis:run`; USER oder ARCHITECT) |
| Kontexte vergleichen, eigenen Workspace wechseln oder zu einem eigenen Navigationseintrag zurückkehren | `ARCHITECTURE_READ` (`architecture:read`) |
| Eigenen Workspace löschen | `ARCHITECTURE_DELETE` (`architecture:delete`; ARCHITECT) |
| Workspace-Zustand administrativ aus dem Arbeitsspeicher entfernen | `ADMIN_CONFIGURATION` (`admin:configuration`; ADMIN) |

Nicht klassifizierte Workspace-Schreibaufrufe werden abgewiesen.

Gespeicherte Projektanalyse-Jobs, Snapshots, Copilot-Status und noch nicht übernommene Neuformulierungsentwürfe verwenden `analysis:read`. Das Fortsetzen eines Jobs und die Verwaltung von Analyseentwürfen verwenden `analysis:run`. Copilot-/Autopilot-Start und ausdrückliches Copilot-Fortsetzen können auch Fachdaten ergänzen und benötigen deshalb sowohl `architecture:write` als auch `analysis:run`. Status-GETs beobachten gespeicherte Arbeit; sie starten weder Modellaufrufe erneut noch übernehmen sie Ergebnisse.

Ein Import mit `analyzeAfterImport=true` benötigt zusätzlich zur fachlichen Schreibberechtigung `analysis:run`; die Prüfung erfolgt vor dem Speichern des Imports. Bei false darf ein auf Schreiben begrenztes Token ohne Analyseberechtigung importieren. Eine konfigurierte automatische Analyse nach dem Speichern von Anforderungen wird ohne Ausführungsberechtigung übersprungen.

Dateidownloads wie Editor-SVG/PDF, Portfolio-DSL, gespeicherte Berichte und Neuformulierungsexporte benötigen `integration:export`; Format- und Fähigkeitsinformationen bleiben eigene Lesezugriffe. Die fachliche Veröffentlichung einer Integration benötigt `architecture:publish`. Ein Integrations-Retry verwendet die gespeicherte Vorgangsrichtung: INBOUND benötigt `integration:import`, OUTBOUND `integration:export`, PUSH/SYNCHRONIZE `architecture:publish`. Ein anderer erlaubter Retry-Scope berechtigt nicht zur Wiederholung einer anderen Vorgangsart. Swagger zeigt diese Alternativen und bedingten Anforderungen ausdrücklich.

Neue Leser und auf Lesen begrenzte Tokens können Git-, Kontext- und Projektionsinformationen abrufen, ohne Workspace-Metadaten, Navigationszustand oder Projektionsdatensätze anzulegen. Benötigt ein Lesezugriff eine isolierte Arbeitskopie, liefert er ohne fertige Kopie 403. Die bestehenden ausdrücklichen Wege für systemseitige Einrichtung bleiben verfügbar.

Die Veröffentlichung über einen konfigurierten Connector ist eine geprüfte fachliche Aktion mit serverseitig konfigurierten Profilen und Zugangsdaten sowie Prüfungen für Repository-Mitgliedschaft, Workspace-Eigentum und den exakten Branch. Sie ändert keine technische Transportkonfiguration. Externes Workspace-Push/Full-Sync behält die separate UND-Verknüpfung aus Veröffentlichung und Administration.

Dokument-KI-Extraktion/Regulierungszuordnung und Quell-/Editor-Fokusvorschläge benötigen `analysis:run`. Fokus-Endpunkte akzeptieren optional `useAi`; die vollständige Operation benötigt deshalb auch bei deterministischen Anfragen die Ausführungsberechtigung. Gewöhnlicher Dokumentupload und Bestätigung bleiben fachliche Schreibvorgänge. Diagrammrouten mit erneuter Analyse (`/api/diagram/visio`, `/archimate`, `/mermaid`, `/structurizr` und `/export/{formatId}` unter diesem Präfix) benötigen `analysis:run` UND `integration:export`; `/api/diagram/current/**` und Score-Downloads benötigen nur `integration:export`.

Eine angenommene explizite Pass-Fortsetzung autorisiert zugleich die Finalisierung ihrer gespeicherten Copilot-Operation, wenn fachliche Schreib- und Analyseausführungsberechtigung gemeinsam vorliegen. Eine reine Analysefortsetzung erhält keine fachliche Ergänzungsberechtigung. Laden, Polling und Wiederherstellung nach ungewissen Befehlsantworten bleiben beobachtend und wiederholen keinen Befehl automatisch.

`ApiPermission` und die geordnete `ApiAccessPolicy` sind die gemeinsame Grundlage für serverseitige Autorisierung und OpenAPI. Die Policy unterscheidet Lesen (einschließlich lesender POST-Aufrufe), Analyseausführung, Änderungen/Löschen/Veröffentlichen, Import/Export und Administration. Nicht klassifizierte Schreibaufrufe werden auch für Administratoren abgewiesen. Bestehende Prüfungen für Projekt-/Repository-Sichtbarkeit, Mitgliedschaften, Lebenszyklus und Eigentum am persönlichen Workspace gelten weiter. Globale Rollen und Scopes gewähren keinen Zugriff auf fremde private Ressourcen. Ein ausgeblendetes Bedienelement ist keine Autorisierungsgrenze.

## Organisationsgruppen und Repository-Berechtigungen

Organisationsgruppen sowie LDAP-/AD- und SAML-Federation werden beim Identitätsanbieter verwaltet. Ordnen Sie dort vertrauenswürdige Gruppenmitgliedschaften expliziten Anwendungsrollen zu. Taxonomy führt keine zusätzliche lokale Gruppendatenbank und interpretiert beliebige Gruppennamen nicht als Berechtigungen.

Ein validierter OIDC-/JWT-Claim kann dem **aktuell authentifizierten Akteur** zusätzlich Repository-Rollen gewähren:

```json
{
  "taxonomy_repository_roles": {
    "repository-a": "READER",
    "repository-b": "CONTRIBUTOR"
  }
}
```

Der Claim muss exakte Repository-IDs auf jeweils eine einzelne Rolle abbilden. Werte lauten exakt `READER`, `CONTRIBUTOR`, `MAINTAINER` oder `OWNER` (ohne `ROLE_`-Präfix oder Arrays); Schlüssel dürfen weder leer sein noch umgebende Leerzeichen haben. Schlüssel werden wörtlich abgeglichen; Wildcards werden nicht aufgelöst. Ein fehlerhafter Eintrag bewirkt, dass die gesamte Claim-Abbildung ignoriert wird. Grants werden neben gespeicherten Mitgliedschaften ausgewertet, nur für das exakte Repository und nur wenn der geprüfte Akteur dem Namen des authentifizierten Principals entspricht. Anfrageparameter und Attribute lokaler Anmeldungen liefern keine solchen Grants. Ein Repository-Grant ersetzt weder Anwendungsrolle noch OAuth-Scope oder Eigentum an einem fremden Workspace. Bestehende Kompatibilitätsregeln für öffentliche/primäre Repositorys bleiben erhalten.

## Swagger und API-Zugriffsbeschreibungen

Swagger gruppiert Operationen nach ihren fachlichen Tags und zeigt pro Operation eine Zugriffstabelle mit allen erforderlichen Berechtigungsgruppen, erlaubten Rollen innerhalb jeder Gruppe, allen benötigten Bearer-Scopes, Wirkung, Ressourcenbeschränkungen sowie dokumentierten HTTP-401-/403-Antworten. Die Angaben stammen aus der gemeinsamen Zugriffs-Policy. OpenAPI bietet nur die im Deployment aktiven Authentifizierungsschemata an: lokales Basic/Sitzung oder OIDC-OAuth/Bearer mit `sessionCookie` auch im OIDC-Profil als Alternative. Bestehende OIDC-Sitzungen verwenden Anwendungsrollen und CSRF-Schutz für Browser-Schreibaufrufe; JWT-Aufrufe benötigen Rollen und alle aufgeführten Scopes. Fehlende oder ungültige Zugangsdaten ergeben 401; fehlen einem authentifizierten Aufrufer Fähigkeit, Scope oder Ressourcenrecht, erhält er 403.

Im Keycloak-Modus verwendet Swagger einen separaten öffentlichen Client `taxonomy-swagger` mit Authorization Code und PKCE. Ein Client-Secret wird nicht im Browser verwendet. Der vertrauliche Client `taxonomy-app` bleibt für die serverseitige Sitzungsanmeldung zuständig. Dienstkonten verwenden einen separaten vertraulichen Client mit Client Credentials und expliziten Rollen, Scopes und Ressourcenrechten. Beachten Sie vor der Bereitstellung die [Keycloak-Einrichtung](KEYCLOAK_SETUP.md) und [Upgrade-Schritte](KEYCLOAK_MIGRATION.md).

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

Der verpflichtende Passwortwechsel berücksichtigt den Servlet-Kontextpfad. Eine Root-Bereitstellung verwendet `/change-password` und `/api/account/change-password`; unter `/taxonomy` lauten die Pfade `/taxonomy/change-password` und `/taxonomy/api/account/change-password`. Login, Logout, Fehlerbehandlung, Passwortwechsel-Seite und -API, CSS, JavaScript, Bilder und Webjars bleiben während der Einschränkung erreichbar.

Eine eingeschränkte API-Anfrage erhält HTTP `428 Precondition Required`, `Cache-Control: no-store`, den Fehlercode `PASSWORD_CHANGE_REQUIRED` und den Passwortwechsel-Endpunkt innerhalb des aktiven Kontextpfads. Der Erzwingungsfilter ist genau einmal in der lokalen Spring-Security-Kette registriert; die gewöhnliche Servlet-Registrierung ist deaktiviert. Im Keycloak-Profil bleibt dieser Lebenszyklus beim Identitätsanbieter.

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

<a id="user-management"></a>
## Lokale Benutzerverwaltung

Administratoren können in der **Benutzerverwaltung** lokale Konten anlegen, Rollen bearbeiten, Konten aktivieren oder deaktivieren und Kennwörter zurücksetzen. Die Bestätigungsformulare zeigen das betroffene Konto vor der Änderung. Externe Identitäten werden im Keycloak/OIDC-Modus beim Identitätsanbieter verwaltet.

<a id="browser-sessions"></a>
## Angemeldete Sitzungen

Administratoren können die Sitzungsübersicht öffnen, um registrierte, nicht abgelaufene Sitzungen dieser Anwendungsinstanz nach Benutzer zu sehen. **Aktualisieren** lädt einen neuen Messstand. Der Zeitpunkt wird in UTC angezeigt. Eine registrierte Sitzung belegt nicht, dass jemand gerade eine Seite betrachtet; die Übersicht ist keine clusterweite Anwesenheitsanzeige.

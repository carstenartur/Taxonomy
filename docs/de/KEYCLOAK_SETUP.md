# Keycloak- & SSO-Einrichtung

## Geführte Helm-Einrichtung und Produktionsstart

Für Rancher/Helm `authentication.mode=keycloak` wählen. Dadurch werden
`production,keycloak` zusätzlich zu den Datenbank-/Deployment-Profilen aktiviert.
`KEYCLOAK_CLIENT_SECRET` kommt aus dem gewählten Secret; Issuer und JWK-Endpunkt
benötigen HTTPS. Ein `TAXONOMY_ADMIN_PASSWORD` oder lokales Bootstrap-Konto wird dabei
nicht verlangt. Der Produktionsschutz prüft weiterhin die zentralen Anmeldedaten,
widersprüchliche lokale Verwaltung und einen optionalen Actuator-/Maschinentoken
(Umgebungsvariable `ADMIN_PASSWORD`, gewöhnlich aus dem getrennten Secret-Schlüssel
`ADMIN_TOKEN`). Keine Prüfungen deaktivieren und kein Dummy-Lokalpasswort ergänzen.
Die folgenden Entwicklungsbeispiele ohne `production` sind nicht die Vorgabe für
geführte Produktionsinstallationen.

Siehe [Einrichtungsanleitung](../INSTALLATION_SETUP.md) für Konfiguration und
Prüfbefehle sowie [separate Datenbank-Pods](../../deploy/helm/taxonomy/DATABASE_PODS.md)
für PostgreSQL-/MSSQL-Services. Ein erfolgreicher Vorabcheck oder Startschutz-Test
ersetzt keine echte OIDC-Anmeldung, Rollenprüfung und TLS-Abnahme im Zielcluster.

## Authentifizierung und Autorisierung

Das Spring-Profil `keycloak` implementiert bereits Browser-OIDC-Anmeldung, Bearer-JWT-Validierung und Rollenzuordnung. Aktivieren Sie das Profil und konfigurieren Sie den Identitätsanbieter; eine neue `SecurityConfig`, ein eigener Token-Filter oder ein HTTP-Basic-Fallback sind nicht erforderlich. Ohne dieses Profil bleiben lokales Form-Login und HTTP Basic verfügbar.

Die vier Anwendungsrollen heißen `ROLE_READER`, `ROLE_USER` (Analyst; gespeicherter Name bleibt erhalten), `ROLE_ARCHITECT` und `ROLE_ADMIN`. ADMIN ist eine unabhängige technische Administrationsfähigkeit und gewährt weder implizite Architekturbearbeitung noch Analyseausführung. Administratoren mit fachlichem Schreibbedarf benötigen ausdrücklich ARCHITECT. Explizite Mehrfachzuweisungen bleiben erhalten, einschließlich USER + ARCHITECT + ADMIN beim lokalen Bootstrap. Siehe [Rollen-/Scope-Matrix](SECURITY.md#rollen-und-berechtigungen).

Jede berechtigungsgeschützte Bearer-API-Operation erfordert eine erlaubte Anwendungsrolle innerhalb jeder erforderlichen Berechtigungsgruppe und alle zugehörigen expliziten OAuth-Scopes. Tokens mit Rollen ohne Scopes erhalten keinen Fallback. Projekt-/Repository-Sichtbarkeit, Mitgliedschaften und Eigentum am persönlichen Workspace bleiben zusätzliche Voraussetzungen. Lesende POST-Aufrufe, Analyseausführung, Import/Export, fachliche Änderungen und Administration haben getrennte Berechtigungen. Nicht klassifizierte Schreibaufrufe werden auch für Administratoren abgewiesen.

Der Katalog kann mehrere Berechtigungsgruppen gemeinsam verlangen. `POST /api/workspace/external/push` und `/api/workspace/external/full-sync` benötigen bei Bearer-Clients ARCHITECT + `architecture:publish` **und** ADMIN + `admin:configuration`; Sitzungen benötigen beide Rollen. Swagger zeigt alle erforderlichen Gruppen/Scopes. `architecture:delete` gilt für dedizierte REST-DELETE-Endpunkte, während `architecture:write` die vollständige Befehls-/DSL-Bearbeitung einschließlich Entfernen oder Ersetzen von Inhalten autorisiert. Dies sind Endpunktrechte, keine Beschränkungen semantischer Differenzen; lesende Scopes erlauben keine Bearbeitung.

`GET /api/analyze-stream` und `GET /api/analyze-node` führen neue LLM-Arbeit aus und benötigen ANALYSIS_RUN (USER oder ARCHITECT und bei Bearer-Aufrufen `analysis:run`). Dieselbe Berechtigung schützt das verzögerte Anlegen der Standard-Arbeitskopie über `GET /api/workspace/current`. Leser ohne bereite Arbeitskopie erhalten 204 und verwenden den zentralen Lesekontext; eine vorhandene bereite Arbeitskopie bleibt unter Ressourcenprüfungen lesbar.

## Schnellstart mit Docker Compose

```bash
docker compose -f docker-compose-keycloak.yml up -d
```

Diese Entwicklungskonfiguration stellt die Anwendung unter `http://localhost:8080` und Keycloak unter `http://localhost:8180` bereit. Importierte Entwicklungsbenutzer und Zugangsdaten sind keine Methode zur Produktionsbereitstellung. Eine geänderte Realm-Exportdatei migriert bestehende Realms nicht; beachten Sie vor dem Upgrade die [Migration](KEYCLOAK_MIGRATION.md).

## Keycloak-Realm-Konfiguration

Erstellen/verwenden Sie den Realm `taxonomy` und trennen Sie drei Client-Zwecke:

| Client | Typ und Flow | Redirect / Verwendung |
|---|---|---|
| `taxonomy-app` | Vertraulich; Authorization Code | Serverseitige Sitzungsanmeldung, Callback `https://app.example/login/oauth2/code/keycloak`; Secret bleibt auf dem Server. |
| `taxonomy-swagger` | Öffentlich; Authorization Code mit PKCE S256 | Swagger-Callback `https://app.example/swagger-ui/oauth2-redirect.html`; kein Client-Secret. |
| Deployment-spezifischer Dienstclient, z. B. `taxonomy-export-service` | Vertraulich; Client Credentials/Dienstkonten | Maschinelle API-Aufrufe; eigenes Secret und explizite Dienstkontorechte. |

Verwenden Sie exakte Redirect-URIs und Origins des Deployments einschließlich eines Servlet-Kontextpfads. Aktivieren Sie für die Browser-Clients den Standard-Authorization-Code-Flow. Deaktivieren Sie für `taxonomy-swagger` Client-Authentifizierung, Passwort-/Direct-Access-Grants und Dienstkonten; verlangen Sie PKCE S256. Das Secret der serverseitigen Anmeldung gehört nicht in Swagger. Der Dienstclient benötigt aktivierte Dienstkonten; interaktive Redirects und Passwort-Grants sind dafür nicht erforderlich.

### Rollen und Organisationsgruppen

Erstellen Sie die vier bekannten Realm-Rollen:

| Rolle | Fähigkeit |
|---|---|
| `ROLE_READER` | Daten und bestehende Analysen lesen; erlaubte Exporte. |
| `ROLE_USER` | Analyst: Lesen/Exportieren und Analysen ausführen. |
| `ROLE_ARCHITECT` | Architekturbearbeitung, Import, Veröffentlichung und Analyseausführung. |
| `ROLE_ADMIN` | Technische Administration, Benutzer/Sitzungen und Diagnose; keine impliziten Fachänderungen oder Analyseausführung. |

Weisen Sie Rollen direkt oder über beim Identitätsanbieter verwaltete Organisationsgruppen zu. Beispielsweise `taxonomy-readers` → READER, `taxonomy-analysts` → USER, `taxonomy-architects` → ARCHITECT und `taxonomy-admins` → ADMIN. Die Zuordnungen sind unabhängig; ADMIN soll keine Composite-Rolle sein, die unbemerkt alle fachlichen Fähigkeiten einschließt. Benötigt ein Konto mehrere Fähigkeiten, weisen Sie diese Rollen ausdrücklich zu. Wählen Sie Standardrollen bewusst: USER erlaubt Analyseausführung und ist keine reine Leserrolle.

Der Rollen-Mapper soll bekannte Rollenstrings unter `realm_access.roles` im Access-Token und in den Claims der Browser-OIDC-Anmeldung ausgeben. Browser und JWT verwenden `taxonomy.keycloak.role-claim-path` (Standard `realm_access.roles`). Akzeptiert werden nur die vier bekannten Rollenstrings; beliebige Gruppennamen und unbekannte Rollen gewähren keinen Zugriff.

### API-Client-Scopes

Legen Sie Client-Scopes mit den exakten Namen der [Berechtigungsmatrix](SECURITY.md#rollen-und-berechtigungen) an. Angeforderte Scopes müssen im `scope`-Claim des Access-Tokens erscheinen. Über Springs Scope-Konverter wird auch der Standard-Claim `scp` unterstützt. Rollen- und Scope-Claims sind getrennt; keiner ersetzt den anderen.

Weisen Sie `taxonomy-swagger` die benötigten API-Client-Scopes als **optionale** Scopes zu, damit Benutzer sie im Authorize-Dialog auswählen können. Konfigurieren Sie auch für Dienstclients die erlaubten API-Client-Scopes. OIDC-Anmeldescopes wie `openid`, `profile`, `email` und `roles` bleiben erhalten; sie erfüllen keine API-Berechtigungen. Beschränken Sie Rollen-Scope-Zuordnungen und API-Scopes auf die für Client und Konto vorgesehenen Fähigkeiten.

Für eine lesende Exportintegration verwenden Sie `ROLE_READER` mit `architecture:read integration:export`. Bestehende Analysen benötigen zusätzlich `analysis:read`; Backups haben eigene Scopes. Die Zugriffstabelle jeder Swagger-Operation nennt alle erforderlichen Gruppen/Scopes, Rollenalternativen und Ressourcenbeschränkungen.

### 6. API-Audience in Access-Tokens aufnehmen

Das Keycloak-Profil verlangt im Access-Token eine `aud`, die standardmäßig `taxonomy-app` enthält. Gültige Signatur, Issuer und `azp` allein genügen nicht; eine fehlende/falsche Audience ergibt HTTP 401. Eine Liste wird akzeptiert, wenn sie die konfigurierte Audience enthält.

Ergänzen Sie für jeden zum API-Zugriff berechtigten Client einschließlich `taxonomy-swagger` und Dienstclients einen Audience-Mapper: **Included Client Audience** = `taxonomy-app`, **Add to access token** = On, **Add to ID token** = Off. Behalten Sie Rollen-Mapper bei. Fordern Sie nach Mapper-/Scope-Änderungen frische Tokens an. Ein Entwicklungs-Realm-Import aktualisiert bestehende Realms nicht automatisch.

Die Anwendung setzt `spring.security.oauth2.resourceserver.jwt.audiences` standardmäßig auf `${KEYCLOAK_CLIENT_ID:taxonomy-app}`. Bei eigener `KEYCLOAK_CLIENT_ID` muss der Mapper dazu passen. Für eine separate Ressourcen-Audience setzen Sie `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=taxonomy-api` und **Included Custom Audience** im Mapper auf `taxonomy-api`. Die erforderliche Audience darf nicht leer sein. Ein alleiniger Standard-Spring-Override der Browser-Client-ID ändert den Platzhalter `KEYCLOAK_CLIENT_ID` nicht; konfigurieren Sie dann die API-Audience ausdrücklich. Eine andere Swagger-Client-ID ändert die API-Audience nicht.

## Anwendungskonfiguration

Halten Sie Deployment-Secrets im gewählten Secret Store. Eine eigenständige Konfiguration verwendet:

```bash
export SPRING_PROFILES_ACTIVE=postgres,keycloak
export KEYCLOAK_ISSUER_URI=https://idp.example/realms/taxonomy
export KEYCLOAK_JWK_SET_URI=https://idp.example/realms/taxonomy/protocol/openid-connect/certs
export KEYCLOAK_CLIENT_ID=taxonomy-app
export KEYCLOAK_CLIENT_SECRET='<server-login-client-secret>'
export KEYCLOAK_SWAGGER_CLIENT_ID=taxonomy-swagger
export KEYCLOAK_ADMIN_URL=https://idp.example
```

Konfigurieren Sie Datenbank und Produktionsprofile gemäß Deployment-Anleitung. `taxonomy.keycloak.principal-mode` ist standardmäßig `LEGACY` und erhält bestehendes benutzernamenbasiertes Eigentum. `STABLE` ist eine explizite Option, die Issuer/Subject auf einen gespeicherten Principal abbildet; sie ist für portable Backups erforderlich. Stellen Sie bestehende Installationen nicht ohne geprüfte Eigentumsmigration auf STABLE um; ein gleicher Anzeigename ist keine verifizierte Identitätsverknüpfung.

Im Modus `STABLE` prüfen geschützte Browser-/Sitzungs- und Bearer-Anfragen den aktuellen Principal und ihre genaue Issuer/Subject-Bindung im gemeinsamen Identitätsspeicher erneut. Eine bestätigte Deaktivierung des Principals oder dieser Bindung sperrt die nächste geschützte Anfrage auf allen Anwendungsinstanzen, auch wenn eine andere Bindung aktiv bleibt. Ist der Identitätsspeicher nicht verfügbar, wird der Zugriff verweigert. Nach erneuter Aktivierung derselben Bindung kann eine ansonsten gültige Sitzung fortgesetzt werden. Vor der Änderung zugelassene Anfragen dürfen noch abgeschlossen werden. Diese Grenze gilt für Anfragen; wartende Hintergrundaufgaben, Rollenänderungen beim Identitätsanbieter und Repository-Rollen aus älteren Sitzungen/Tokens benötigen eigene Berechtigungsprüfungen. `LEGACY`-Sitzungen behalten bis zur geprüften Migration ihr bisheriges Eigentumsverhalten.

## Swagger mit Authorization Code und PKCE

Die Anwendung konfiguriert:

```properties
springdoc.swagger-ui.oauth.client-id=${KEYCLOAK_SWAGGER_CLIENT_ID:taxonomy-swagger}
springdoc.swagger-ui.oauth.use-pkce-with-authorization-code-grant=true
```

Öffnen Sie Swagger UI und verwenden Sie **Authorize** mit dem öffentlichen Swagger-Client. Wählen Sie die API-Scopes der gewünschten Operationen, melden Sie sich beim Identitätsanbieter an und kehren Sie über die exakte Swagger-Redirect-URI zurück. Ein vertrauliches Client-Secret gehört nicht in den Browser. Erfolgreiche Anmeldung gewährt keine Operation, wenn Rolle, Scope oder Repository-/Workspace-Prüfung fehlen.

OpenAPI zeigt nur aktive Authentifizierungsschemata, einschließlich `sessionCookie` als Alternative im OIDC-Profil. Bestehende OIDC-Sitzungen verwenden Rollen und CSRF-Schutz für Browser-Schreibaufrufe; JWTs benötigen Rollen und alle Scopes. Operationen behalten ihre fachlichen Tags und zeigen eine aus der Policy abgeleitete Zugriffstabelle mit jeder erforderlichen Berechtigungsgruppe, ihren Rollenalternativen, allen Bearer-Scopes, Wirkung, Ressourcenbeschränkungen und 401-/403-Antworten. Die OAuth-Standard-URLs bestehen aus dem Issuer plus `/protocol/openid-connect/auth` beziehungsweise `/protocol/openid-connect/token`. Überschreiben Sie sie bei abweichenden Provider-Endpunkten oder öffentlichen Browser-URLs mit `taxonomy.keycloak.swagger.authorization-url` und `taxonomy.keycloak.swagger.token-url`.

## Dienstkonten und Client Credentials

Verwenden Sie für Automatisierung einen eigenen vertraulichen Client. Weisen Sie seinem Dienstkonto explizite Anwendungsrollen zu, erlauben Sie die angeforderten API-Scopes und ergänzen Sie die API-Audience. Private Repository-Operationen benötigen zusätzlich Mitgliedschaften für den authentifizierten Dienst-Principal oder vertrauenswürdige Repository-Rollen-Claims. Globales ADMIN umgeht diese Prüfungen nicht.

Für einen minimalen lesenden Exportclient konfigurieren Sie READER und `architecture:read integration:export` und fordern mit seinen eigenen Zugangsdaten ein Token an:

```bash
TOKEN=$(curl --fail --silent --show-error \
  'https://idp.example/realms/taxonomy/protocol/openid-connect/token' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'client_id=taxonomy-export-service' \
  --data-urlencode "client_secret=${TAXONOMY_SERVICE_CLIENT_SECRET}" \
  --data-urlencode 'scope=architecture:read integration:export' | jq -er '.access_token')
curl --fail -H "Authorization: Bearer $TOKEN" 'https://app.example/api/taxonomy'
```

Prüfen Sie Tokens lokal, ohne sie zu veröffentlichen. Ein passender Payload enthält verifizierten Issuer/Subject, `aud` mit `taxonomy-app`, `realm_access.roles` mit `ROLE_READER` und `scope` mit beiden angeforderten Scopes. Ein Token nur mit Scopes genügt nicht. Die Operationsbeschreibung nennt die zusätzlichen Ressourcenrechte für Exporte.

## Repository-Grants aus vertrauenswürdigen Claims

Für organisationsbezogenen Repository-Zugriff kann ein Mapper des Identitätsanbieters Folgendes ausgeben:

```json
{
  "taxonomy_repository_roles": {
    "repository-a": "READER",
    "repository-b": "CONTRIBUTOR"
  }
}
```

Jeder Schlüssel ist eine exakte Repository-ID; jeder Wert ein einzelner String `READER`, `CONTRIBUTOR`, `MAINTAINER` oder `OWNER`. Arrays, `ROLE_`-Präfixe, leere/gepolsterte Schlüssel und unbekannte Werte sind ungültig; ein fehlerhafter Eintrag verwirft die ganze Abbildung. Schlüssel werden wörtlich abgeglichen; Wildcards werden nicht aufgelöst. Übermitteln Sie den Claim ausschließlich über validierte JWT-/OIDC-Claims des Identitätsanbieters, gebunden an den aktuell authentifizierten Akteur. Er wird neben gespeicherten Mitgliedschaften ausgewertet und gewährt keine Rechte für einen im Request angegebenen fremden Benutzernamen. Repository-Grants ersetzen weder Anwendungsrolle, API-Scope, Lebenszyklusprüfung noch Eigentum am persönlichen Workspace. Siehe [Sicherheit](SECURITY.md#organisationsgruppen-und-repository-berechtigungen).

## Benutzer-Federation und SSO-Federation

Konfigurieren Sie LDAP-/Active-Directory-Benutzerföderation oder vorgelagerte SAML-/OIDC-Identitätsanbieter in Keycloak. Die Anwendung bleibt OIDC-Client und JWT-Ressourcenserver. Tauschen Sie für SAML Metadaten aus und prüfen Sie Signaturen im Broker; konfigurieren Sie für LDAP/AD Verzeichnisanbindung und Gruppenzuordnung dort. Taxonomy bietet keinen direkten SAML-Anmeldepfad und keine lokale Organisationsgruppendatenbank.

Ordnen Sie vertrauenswürdige vorgelagerte Gruppen/Attribute den expliziten Anwendungsrollen und bei Bedarf dem obigen Repository-Claim zu. MFA, IdP-Sitzungsrichtlinien, Deprovisionierung und Audit-Ereignisse werden beim Provider konfiguriert; prüfen Sie das daraus entstehende Verhalten von Anwendungssitzungen und Tokens im Deployment. Erfolgreiche Föderation allein belegt keinen Anwendungszugriff.

### Generischer OIDC-Provider

Das implementierte OIDC-Profil lässt sich für andere Provider konfigurieren. Überschreiben Sie die Spring-Client-/Provider-Einstellungen unter `spring.security.oauth2.client.registration.keycloak.*` und `spring.security.oauth2.client.provider.keycloak.*` sowie `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `jwk-set-uri` und `audiences`. Stimmen Sie den Benutzernamen-Claim mit dem Legacy-Eigentumsmodell ab und setzen Sie `taxonomy.keycloak.role-claim-path` für den verschachtelten Rollen-Claim des Providers. Ausgegeben werden weiterhin die vier bekannten `ROLE_*`-Strings. Überschreiben Sie beide Swagger-OAuth-URLs, wenn sie von den Keycloak-Standards abweichen. Auch providerspezifisches Account-Console-/Logout-Verhalten muss im Deployment geprüft werden.

## Integration testen und Fehler beheben

Prüfen Sie Browser-Anmeldung mit dem vertraulichen Serverclient und Swagger-Anmeldung mit dem separaten öffentlichen PKCE-Client. Testen Sie READER-Lesen/-Exporte mit Schreibverweigerung, USER-Analyseausführung, ARCHITECT-Fachänderungen und ADMIN-Administration mit verweigerten Fachänderungen ohne explizites ARCHITECT. Erfüllen Sie bei Bearer-Aufrufen jede erforderliche Berechtigungsgruppe und alle Scopes und entfernen Sie jede erforderliche Rolle/Gruppe beziehungsweise jeden Scope einzeln: Der Zugriff muss verweigert werden. Prüfen Sie dabei die Analyse-GETs und kombinierten Rechte für externes Push/Full-Sync. Prüfen Sie auch private Repositorys und fremde Workspaces.

| Ergebnis | Prüfung |
|---|---|
| HTTP 401 | Issuer, Signatur/JWK, Ablauf, Audience und Token-Gültigkeit; `azp` ist keine API-Audience. |
| HTTP 403 | Erfüllte Rollenanforderung jeder Berechtigungsgruppe, alle Operations-Scopes, Repository-Mitgliedschaft/-Claim und Workspace-Eigentum. |
| Swagger-Redirect-/PKCE-Fehler | Öffentlicher Swagger-Client, exakter Redirect/Origin, PKCE S256, öffentliche Authorization-/Token-URLs. |
| Anmeldung erfolgreich, Rollen fehlen | Vertrauenswürdige Gruppen-Rollen-Zuordnung, Rollen-Claim-Pfad und Token-/Client-Scope-Mapping. |
| Bestehende private Daten erscheinen nicht | Principal-Modus und Legacy-Benutzername; keine Verknüpfung allein anhand des Anzeigenamens. |

Verwenden Sie nach Berechtigungs-/Mapper-Änderungen frische Tokens. Synchronisieren Sie Uhren und vertrauen Sie der bereitgestellten HTTPS-Zertifikatskette. Schützen Sie Swagger/OpenAPI gemäß Deployment-Richtlinie; `TAXONOMY_SWAGGER_PUBLIC` steuert die Erreichbarkeit, nicht die API-Autorisierung. Prüfen Sie Anmelde-/Audit-Ereignisse, ohne Secrets oder vollständige Tokens zu protokollieren.

## Produktionshinweise

Verwenden Sie HTTPS, exakte Redirect-/Origin-Freigaben, getrennt verwaltete Client-Secrets sowie einen passend betriebenen IdP-Datenbank- und Backup-/Wiederherstellungsprozess. Prüfen Sie Provider-Verfügbarkeit, TLS, echte Browser-Anmeldung, Rollen, Scopes und Ressourcenbeschränkungen in der Zielumgebung. Ein Startcheck belegt diese Abläufe nicht.

Siehe [Sicherheit](SECURITY.md), [Migration](KEYCLOAK_MIGRATION.md), [Deployment-Checkliste](DEPLOYMENT_CHECKLIST.md) und [Konfigurationsreferenz](CONFIGURATION_REFERENCE.md).


**Startsperre für STABLE-Migrationen:** Vor dem Start mit STABLE prüft die Anwendung
die historischen Eigentümerbereiche gegen reservierte Principals und verifizierte
Bindungen, einschließlich nach Migration V3 neu geschriebener LEGACY-Eigentümer.
Fehlende Reservierungen, nicht aufgelöste historische Principals und unbestätigte
Eigentümerbindungen verhindern den Start. Das ist keine Freigabe für die Umstellung
einer bestehenden Installation: zunächst alle LEGACY-Schreiber stoppen, die
Identitätsfreigaben aus EXT-02 einholen sowie weitere benutzernamenbasierte Daten und
wartende Aufträge in der echten Installation prüfen. Eine neue leere Installation
kann STABLE verwenden.

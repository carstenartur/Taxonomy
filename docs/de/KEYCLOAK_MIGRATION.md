# Migration zu Keycloak und expliziten REST-Berechtigungen

Diese Anleitung behandelt den Wechsel von lokalem Form-Login zu Keycloak/OIDC und das Upgrade bestehender Installationen auf das Vier-Rollen-Modell mit expliziten REST-Scopes. Konfigurieren Sie Identitätsanbieter und Clients **vor** der Bereitstellung der Zugriffsänderung. Eine aktualisierte Realm-Exportdatei migriert bestehende Realms nicht automatisch.

## Rollen, Scopes und Clients vor der Bereitstellung vorbereiten

1. Erfassen Sie Anwendungsrollen und verwendete API-Operationen jeder Browserintegration und jedes Dienstkontos. Verwenden Sie die [Berechtigungsmatrix](SECURITY.md#rollen-und-berechtigungen) und die Zugriffstabelle der jeweiligen Swagger-Operation.
2. Ergänzen Sie `ROLE_READER` für Lesekonten. `ROLE_USER` bleibt der gespeicherte Analysten-Rollenname: Er erlaubt Analyseausführung und ist keine reine Leserrolle. `ROLE_ARCHITECT` und `ROLE_ADMIN` bleiben unabhängige Fähigkeiten.
3. Prüfen Sie Administratorzuweisungen. ADMIN allein gewährt keine Analyseausführung oder fachlichen Schreibrechte mehr. Weisen Sie Administratoren mit Architekturbearbeitung ausdrücklich `ROLE_ARCHITECT` zu; zur Analyseausführung USER oder ARCHITECT. Erhalten Sie gewünschte explizite Mehrfachzuweisungen. Das lokale Bootstrap-Konto erhält bereits ausdrücklich USER + ARCHITECT + ADMIN und behält diese Fähigkeiten; der Start legt die Leserrolle idempotent an.
4. Erstellen/zuweisen Sie die exakten API-Client-Scopes der Berechtigungsmatrix. Ein berechtigungsgeschützter Bearer-Aufruf benötigt **eine erlaubte Rolle in jeder erforderlichen Berechtigungsgruppe UND jeden aufgeführten Scope**. Tokens mit Rollen ohne API-Scopes erhalten keinen Fallback und für diese Operationen 403; Scopes allein genügen ebenfalls nicht. `openid profile email roles` ersetzen keine API-Scopes.
5. Behalten Sie `taxonomy-app` als vertraulichen Client der serverseitigen OIDC-Sitzungsanmeldung bei. Ergänzen Sie den separaten öffentlichen Client `taxonomy-swagger` für Authorization Code mit PKCE S256; konfigurieren Sie seine exakte Swagger-Redirect-URI und optionale API-Client-Scopes. Bei anderer ID setzen Sie `KEYCLOAK_SWAGGER_CLIENT_ID`. Das Server-Client-Secret gehört niemals in Swagger-/Browser-Konfiguration.
6. Geben Sie jeder Maschinenintegration einen eigenen vertraulichen Client-Credentials-Client mit expliziten Dienstkonto-Rollen/-Scopes und Repository-Mitgliedschaften oder vertrauenswürdigen Grants. Eine minimale lesende Exportintegration verwendet READER und `architecture:read integration:export`.
7. Ergänzen/prüfen Sie den API-Audience-Mapper für alle API-Clients einschließlich Swagger und Dienstkonten. Fordern Sie nach Rollen-/Scope-/Mapper-Änderungen **frische Access-Tokens** an und prüfen Sie vor dem Umschalten Issuer, Subject, Audience, bekannte Rollen und angeforderte Scopes. Bestehende Tokens erhalten neue Claims nicht automatisch.

Die [Keycloak-Einrichtung](KEYCLOAK_SETUP.md) beschreibt Clients, Scopes, PKCE und Client Credentials. Browser-Sitzungen und lokales Basic verwenden weiter Rollen mit den bestehenden CSRF-Regeln; dadurch entsteht kein Scope-Fallback für Bearer-Tokens.

## Operationswirkung und kombinierte Berechtigungen berücksichtigen

Klassifizieren Sie Operationen nicht allein nach HTTP-Methode: `GET /api/analyze-stream` und `GET /api/analyze-node` starten neue LLM-Arbeit und benötigen ANALYSIS_RUN (USER oder ARCHITECT; Bearer-Scope `analysis:run`). Auch das verzögerte Anlegen der Standard-Arbeitskopie über `GET /api/workspace/current` benötigt diese Berechtigung. Leser ohne bereite Arbeitskopie erhalten 204 und bleiben im zentralen Lesekontext; bestehende bereite Arbeitskopien bleiben unter Ressourcenprüfungen lesbar.

Weisen Sie Integrationen mit `POST /api/workspace/external/push` oder `/api/workspace/external/full-sync` vor dem Upgrade sowohl ARCHITECT als auch ADMIN zu und fordern Sie in Bearer-Tokens sowohl `architecture:publish` als auch `admin:configuration` an. Diese Berechtigungsgruppen sind gemeinsam erforderlich. Browser-Sitzungen benötigen beide Rollen; keine allein genügt.

`architecture:delete` schützt dedizierte REST-DELETE-Endpunkte. `architecture:write` erlaubt die gesamte Befehls-/DSL-Bearbeitung, die Inhalte entfernen oder ersetzen kann; Scopes filtern keine semantischen Differenzen innerhalb einer Bearbeitung. Lesende Scopes gewähren weiterhin keine fachlichen Änderungen.

Swagger dokumentiert jede erforderliche Berechtigungsgruppe und jeden Scope und bietet `sessionCookie` auch im OIDC-Modus als Alternative. Eine bestehende OIDC-Sitzung verwendet Rollen und CSRF für Browser-Schreibaufrufe; ein JWT benötigt die erforderlichen Rollen und alle Scopes. Prüfen Sie den vorgesehenen Authentifizierungspfad: Eine funktionierende Sitzung belegt keine ausreichenden Scopes eines Bearer-Tokens.

## Access-Token-Audience vorbereiten

Die Keycloak-API verlangt eine `aud` im Access-Token, die `KEYCLOAK_CLIENT_ID` enthält (Standard `taxonomy-app`). Tokens ohne diese Audience erhalten HTTP 401, auch bei gültigem Issuer, Signatur und Rollen. `azp` ersetzt `aud` nicht.

Konfigurieren Sie für jeden API-Client einen Audience-Mapper mit **Included Client Audience** = `taxonomy-app`, **Add to access token** = On und **Add to ID token** = Off. Behalten Sie Rollen-Mapper bei. Bei eigener `KEYCLOAK_CLIENT_ID` muss der Mapper dazu passen. Für eine separate API-Audience setzen Sie `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=taxonomy-api` und **Included Custom Audience** = `taxonomy-api`. Die Audience darf nicht leer sein. Ein alleiniger Standard-Spring-Override der Browser-Client-ID aktualisiert den Standard aus `KEYCLOAK_CLIENT_ID` nicht; setzen Sie dann die API-Audience ausdrücklich. Die öffentliche Swagger-Client-ID bleibt von der API-Audience getrennt.

## Eigentum und Repository-Zugriff erhalten

Übernehmen Sie bei Migration bestehender lokaler Konten in den standardmäßigen `LEGACY`-Principal-Modus ihre Benutzernamen und gewünschten expliziten Rollen. `Authentication.getName()` basiert bei Browser-Anmeldung weiterhin auf `preferred_username` (Bearer-Konvertierung fällt bei fehlendem/leerem Claim auf Subject zurück). Prüfen Sie den Zugriff auf bestehende private Repositorys und Workspaces mit der migrierten Identität.

`STABLE` löst ausdrücklich verifizierte Issuer/Subject auf einen gespeicherten Principal auf und ist für portable Backups erforderlich. Aktivieren Sie den Modus bei bestehenden Installationen nicht ohne geprüfte Eigentumsmigration; gleiche Anzeigenamen begründen keine Identitätsbindung.

Verwalten Sie Organisationsgruppen beim Identitätsanbieter und ordnen Sie dort explizite Rollen zu. Es gibt keine neue lokale Gruppendatenbank. Bestehende Repository-Mitgliedschaften, Sichtbarkeits-/Lebenszyklusprüfungen und Eigentum am persönlichen Workspace gelten weiter.

Bei Verwendung von `taxonomy_repository_roles` geben Sie für den authentifizierten Akteur eine validierte OIDC-/JWT-Abbildung **exakte Repository-ID → einzelner Rollenstring** aus, beispielsweise:

```json
{"taxonomy_repository_roles":{"repository-a":"READER","repository-b":"CONTRIBUTOR"}}
```

Nur `READER`, `CONTRIBUTOR`, `MAINTAINER` und `OWNER` sind gültige Werte. Arrays, `ROLE_`-Präfixe, leere/gepolsterte IDs und unbekannte Werte verwerfen die Claim-Abbildung. Repository-IDs werden wörtlich abgeglichen; Wildcards werden nicht aufgelöst. Claims gewähren keine Rechte für einen fremden Akteur im Request, ersetzen keine globalen Rollen/Scopes und umgehen kein fremdes Workspace-Eigentum. Prüfen Sie Mapper-Regeln vor der Bereitstellung gemeinsam mit gespeicherten Mitgliedschaften.

## Von lokaler Anmeldung wechseln

| Bereich | Lokaler Benutzermodus | Keycloak/OIDC-Modus |
|---|---|---|
| Browser-Anmeldung | Form-Login und Serversitzung | OIDC-Redirect und Serversitzung |
| API-Clients | HTTP Basic | Bearer-Access-Tokens mit expliziten Rollen/Scopes |
| Konto-/Passwortverwaltung | Lokale Benutzerverwaltung und Passwortwechsel-Seite | Identitätsanbieter |
| Konto-Bootstrap | `SecurityDataInitializer` | Bereitstellung beim Identitätsanbieter |
| Organisationsgruppen / LDAP / AD / SAML | Kein lokales Gruppen-/Föderationssystem | Föderation und Rollenzuordnung beim Identitätsanbieter |

Für Entwicklung startet `docker compose -f docker-compose-keycloak.yml up -d` die Beispielkonfiguration. Entwicklungszugangsdaten dürfen nicht in Produktion verwendet werden. Konfigurieren Sie bei eigenständiger Bereitstellung Datenbank-/Deployment-Profile und:

```bash
export SPRING_PROFILES_ACTIVE=postgres,keycloak
export KEYCLOAK_ISSUER_URI=https://idp.example/realms/taxonomy
export KEYCLOAK_JWK_SET_URI=https://idp.example/realms/taxonomy/protocol/openid-connect/certs
export KEYCLOAK_CLIENT_ID=taxonomy-app
export KEYCLOAK_CLIENT_SECRET='<server-login-client-secret>'
export KEYCLOAK_SWAGGER_CLIENT_ID=taxonomy-swagger
```

Für Produktion gelten die [geführten Helm-/Produktionsschritte](KEYCLOAK_SETUP.md#geführte-helm-einrichtung-und-produktionsstart). Ein generischer OIDC-Provider benötigt zusätzlich abgestimmte Spring-Provider-/Client-Einstellungen, den Rollen-Claim-Pfad und abweichende Swagger-Authorization-/Token-URLs; LDAP/AD und SAML werden weiterhin beim Provider föderiert.

## Vor Abschluss des Upgrades prüfen

Prüfen Sie echte Browser-Sitzungsanmeldung und Swagger-PKCE-Anmeldung getrennt. Swagger soll nur aktive Authentifizierungsschemata und pro Operation alle Berechtigungsgruppen, Rollenalternativen, Scopes, Wirkung, Ressourcenbeschränkungen und 401-/403-Antworten aus der gemeinsamen Policy zeigen. Prüfen Sie frische Bearer-Tokens an einer erlaubten Operation und entfernen Sie jede erforderliche Rolle/Gruppe beziehungsweise jeden Scope einzeln: Alle Fälle müssen abgewiesen werden. Beziehen Sie die Analyse-GETs, kombinierten Rechte für externes Push/Full-Sync und das Arbeitskopie-204-Verhalten für Leser ein. Prüfen Sie außerdem verweigerte ADMIN-Fachänderungen/-Analysen ohne Zusatzrolle, READER-Schreibverweigerung, gewünschte explizite Mehrfachrollen und Isolation privater Repositorys/fremder Workspaces. Nicht klassifizierte Schreibaufrufe bleiben für jede Rolle gesperrt.

HTTP 401 weist auf Authentifizierung, Token-Gültigkeit oder Audience hin; HTTP 403 auf fehlende Operationsrolle, Scope oder Ressourcenberechtigung. Dies sind durchzuführende Deployment-Prüfungen, keine Aussage über bereits bestandene Prüfungen einer Zielinstallation.

## Zu lokaler Anmeldung zurückkehren

Entfernen Sie `keycloak` aus `SPRING_PROFILES_ACTIVE`, behalten Sie erforderliche Datenbank-/Deployment-Profile bei. Lokale Kontokomponenten werden wieder aktiv; prüfen Sie deren Zugangsdaten, Produktions-Bootstrap-Anforderungen und Eigentumsdaten vor dem Umschalten. Das Entfernen des Profils kopiert externe Identitäten oder Gruppen-Grants nicht in lokale Konten.

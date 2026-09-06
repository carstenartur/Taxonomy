# Browser-session inventory / Angemeldete Sitzungen

## English

Administrators can open **Administration → Health Dashboard → Signed-in sessions**
or `/admin/sessions`. The corresponding read-only endpoint is
`GET /api/admin/sessions`. Both responses use `Cache-Control: no-store`; existing
`/admin/**` and `/api/admin/**` role rules and controller method authorization apply.

The inventory uses Spring Security's `SessionRegistryImpl`,
`HttpSessionEventPublisher` and the standard concurrent-session infrastructure with
`maximumSessions(-1)`. Unlimited concurrent logins and the default session-fixation
protection are retained. Both form-login and OIDC security chains bind the same
local registry. No separate login system, database table, timer, heartbeat, custom
activity filter, Redis service or telemetry exporter is introduced.

Each row contains only a display name, sign-in type, session count and last registered
request time. Local users are grouped by their canonical UserDetails username. OIDC
users are grouped internally by issuer and subject, not by mutable/colliding display
names or token values. Unsupported principal types contribute an explicit unidentified
session count; their toString() is never exposed. The response contains at most 200
user rows and indicates truncation; counts are for the entire local inventory.

### Scope and limits

- This is **one application instance**, not cluster-wide presence or Keycloak's SSO inventory.
- It describes registered, non-expired sessions, not proof that a human is online.
- Last request includes automatic polling. Closing a tab/browser need not end its session.
- Logout and container expiration events remove registrations. Expiration cleanup is
  subject to the servlet container's lifecycle timing.
- An application restart resets this in-memory inventory. Persisted/restored sessions
  are not claimed to be a complete historical inventory. Shared session storage would
  require a separately reviewed integration.
- Stateless Basic/Bearer calls and database-pool connections are not counted as new
  browser logins. No session is created by the inventory service or controller.
- There is no force-logout/eviction endpoint, change to user permissions or new login limit.
- The snapshot is a concurrent observation, not an atomic cluster census.
- Session IDs, tokens, passwords, emails, roles and client addresses are not serialized;
  identity information must not become metrics labels or span attributes.

### Verification

`BrowserSessionInventoryTest` covers grouping, expiration/removal, OIDC stable identity,
unknown principal safety and bounded output. `BrowserSessionAuthorizationTest` checks
method-level ADMIN authorization independently of the production URL role rules.
The existing `DiagnosticsContainerIT` adds a real cookie/form-login sequence against
its existing application container: Basic-only baseline, two concurrent logins,
session-fixation protection, read-only API/page access, CSRF-protected logout and
removal of only the logged-out session. No additional container or application start
is required.

These tests are acceptance requirements, not an assertion that an unexecuted suite
has passed. The authoritative command remains:

```sh
./mvnw verify -DexcludedGroups="real-llm"
```

OIDC provider end-to-end behavior, browser screenshots and clustered session storage
require their own evidence before claiming those deployment topologies are accepted.

## Deutsch

Unter **Administration → Gesundheitsübersicht → Angemeldete Sitzungen** zeigt die
lesende Administratoransicht Benutzer, Anmeldeart, Sitzungszahl und die letzte
registrierte Anfrage. Die Zahl der Benutzer wird von der Zahl ihrer Sitzungen getrennt.
Mehrfachanmeldungen bleiben erlaubt. Die vorhandene Spring-Security-Sitzungsverwaltung
übernimmt Anmeldung, Schutz vor Session-Fixation, Aktualisierung und Ablauf.

Die Anzeige gilt nur für die aktuelle Anwendungsinstanz. Eine Sitzung bedeutet nicht,
dass jemand gerade arbeitet. Automatische Anfragen zählen zur letzten Aktivität;
das Schließen des Browsers beendet nicht sofort die Sitzung. Abmeldung und vom
Container gemeldeter Ablauf entfernen Einträge. Ein vollständiges Cluster- oder
Keycloak-Anmeldeverzeichnis wird nicht behauptet.

Passwörter, Tokens, rohe Sitzungskennungen und Netzwerkadressen bleiben verborgen.
Identitäten werden nicht als Telemetrie-Labels ausgegeben. Die Seite führt keine
Abmeldung anderer Benutzer aus und verändert keine Anmelde- oder Berechtigungsregeln.
Die neue Registry-Anbindung erfordert trotzdem die vollständige Sicherheits- und
Integrationsprüfung, einschließlich OIDC, bevor eine Freigabe behauptet werden darf.

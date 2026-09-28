# Guided installation / Geführte Einrichtung

This is a bounded installation foundation, not a new configuration server. Helm,
external Spring configuration and the existing application remain authoritative.
No public first-run endpoint is installed. A database/login failure never enables
setup or a fallback identity provider.

## Rancher / Helm

The chart's `questions.yaml` now groups database settings, authentication, AI,
storage, upgrade safety and memory. See `deploy/helm/taxonomy/values-guided.yaml`.
Use `authentication.mode=existing` to preserve explicit profile configuration;
select `local` for the existing local-user-management profile, or `keycloak` for
central OIDC. AD/LDAP federation is configured in Keycloak, not in Taxonomy.
Guided authentication adds the production profile without discarding the selected
database/deployment profiles. Conflicting login profiles are rejected.

Keep the password/API keys in the existing Kubernetes Secret. Optional
`database.url` and `database.username` replace only their respective Secret mappings;
leaving them blank preserves the old mapping. Select `database.type=postgres` or
`database.type=mssql` for PostgreSQL or SQL Server, including a database in a separate
Pod/namespace addressed through its Kubernetes Service. `existing` preserves manual
profile selection. The chart does not install the database. See
[separate database Pods](../deploy/helm/taxonomy/DATABASE_PODS.md) for Service DNS,
TLS/CA, network policies, persistence and the remaining MSSQL qualification limits.
No password may be placed in the guided JDBC URL. Local login uses Secret key
`ADMIN_PASSWORD` through `TAXONOMY_ADMIN_PASSWORD`; monitoring uses the distinct
`ADMIN_TOKEN`. In central-login mode the local bootstrap mapping is removed and
`KEYCLOAK_CLIENT_SECRET` is required. The form asks for the **Secret key name**,
never its value. A client with HTTPS issuer, role claims and a tested real
administrator login must already exist. Network-policy egress and ingress/TLS
remain cluster-specific values; selecting an endpoint does not open the network.

With `production,keycloak`, the startup guard checks the central-login settings
instead of demanding an unused local password. Missing issuer/client ID/client
secret or conflicting local-user settings still stop startup. An optional machine
token is still checked for strength in **both** modes. Local production login still
requires its strong bootstrap password and a distinct machine token when configured.
Do not supply a dummy local password or disable `production` to make OIDC start.

`config.CUSTOM_LLM_URL` is validated before rendering, including in `existing` mode.
Use HTTPS; HTTP is allowed only for the exact loopback hosts `localhost`, `127.0.0.1`
or `[::1]`, for an explicitly local provider. No embedded user/password, query,
fragment, whitespace or backslash is accepted. Supply the provider API key through
`secretEnv.CUSTOM_LLM_API_KEY`, never in the endpoint. Set the endpoint through
`config.CUSTOM_LLM_URL`, not `extraEnv`; the latter cannot bypass validation.
A provider in another Pod is **not** loopback and requires HTTPS and network access.

The chart's JSON schema validates values even without Rancher. The existing
render tests stay in place. `SetupInfrastructureTest` additionally executes
`deploy/helm/taxonomy/verify-setup.sh` and `verify-setup-security.sh` under Maven
(Helm is required in canonical CI). It also passes the rendered Keycloak Deployment
environment to a real Spring startup of `ProductionSecurityGuard`, resolving the
central Secret with a test-only value. This checks ConfigData, dependency injection
and the ApplicationRunner lifecycle without starting a database or an identity
provider; it is not a full SSO acceptance test.
Neither successful chart rendering nor a Secret reference proves connectivity.
Do not bypass existing Recreate/migration/storage safety guards.

## Gemeinsame Konfigurationsprüfung

Die folgenden Befehle verwenden dasselbe ausführbare Taxonomy-JAR wie der normale
Betrieb. `--setup-help` benötigt keine Datenbank und keine gültige Konfiguration.
Die Prüfung startet keinen Spring-Anwendungskontext, keinen Webserver, keine
Migration und keine KI. Sie liest Spring ConfigData einschließlich Profilen,
Umgebungsvariablen, JSON-Konfiguration, configtree und Kommandozeilenüberschreibungen.

```sh
java -jar taxonomy-app.jar --setup-help
java -jar taxonomy-app.jar --check-configuration=static
java -jar taxonomy-app.jar --check-configuration=connections
```

Run these **inside the target pod** or as the intended operating-system account,
with the same mounted files and environment as the real process. Exit code 2 means
configuration/probe failure; 0 means the implemented checks passed, not complete
installation acceptance. Findings are `OK`, `WARNING`, `ERROR`, or `NOT_CHECKED`.
Values, passwords and raw exception strings are never included in findings.

Static checks cover destructive persistent-schema settings, volatile server data,
login conflicts, port range and explicitly selected provider/model requirements.
Connection checks are opt-in: bounded external JDBC connection validation with
read-only hint, HTTP discovery reachability without redirects, readable local
model files. They do **not** verify effective DB write/migration permissions,
certificate policy beyond the configured client's behavior, OIDC metadata
semantics, actual login/roles, inference quality, paid requests or model downloads.
For local models, `model.onnx` and `tokenizer.json` are required readable inputs.
`serving.properties` is generated/repaired by `LocalEmbeddingService` during actual
model startup. Its absence alone is not an error: preflight reports metadata
generation as `NOT_CHECKED`, does not create the file and does not claim the model
will load. A read-only mount may require operator-provided metadata; writable
metadata generation, model validity and inference still need runtime acceptance.
Embedded HSQLDB is not opened because doing so can create/lock files. No temporary
DDL is executed. HTTP 200 is reported only as discovery reachability, not SSO success.

Kubernetes deployments keep their existing startup behavior; the check is explicit.
Native launchers additionally run static validation before normal startup. Operators
still need backup/restore, real sign-in and application acceptance tests.

## Lokale Einrichtung ohne Container

Die lokale Voreinstellung ist eine dauerhafte HSQLDB-Dateidatenbank mit lokaler
Benutzerverwaltung und Bindung an `127.0.0.1`. Sie ist kein öffentlich erreichbarer
Serverstandard. Programmdateien und Nutzerdaten werden strikt getrennt.

```sh
java -jar taxonomy-app.jar --configure
```

The console requests the initial admin password without echoing it. A console is
required. By default the command creates `${user.home}/.taxonomy/` containing
`application.properties`, `data/`, and a private `secrets/` configtree. The parent
must exist. `--configure=/absolute/new/directory` selects another directory.
An existing directory is **never overwritten**, including an incomplete installation;
inspect/move an incomplete directory deliberately before retrying. Password files
are written only after private POSIX permissions or Windows ACLs are established.
Configuration is atomically published last. Unicode and spaces in data paths are
preserved using escaped Java properties.

For unattended initial setup, inject `TAXONOMY_ADMIN_PASSWORD` via the deployment's
secret mechanism and use `--configure-local`. Do not put passwords in command-line
arguments or shell history. The command does not print generated credentials.
The password must contain at least 12 characters and no more than 72 UTF-8 bytes.

For an ordinary JAR, point Spring at the generated file explicitly:

```sh
java -jar taxonomy-app.jar --check-configuration=static --spring.config.additional-location=file:/absolute/config/application.properties
java -jar taxonomy-app.jar --spring.config.additional-location=file:/absolute/config/application.properties
```

Native launchers use that per-user file automatically and fail closed when it is
missing or unreadable. The required file is loaded even when additional locations
are supplied via CLI, environment, JVM properties or Spring application JSON. Those
locations are overlays, not replacements for the installation. An optional missing
overlay must never cause a fallback to development defaults; native startup also
rejects in-memory HSQLDB, even if an empty installation file exists.

For an installation in another directory, set the JVM option
`-Dtaxonomy.config-directory=/absolute/config` for **all three launchers**. The
`--configure=/absolute/config` argument only chooses where setup writes; it does not
change the default directory of future processes. Ordinary `java -jar` use keeps
its existing developer behavior and normal Spring location handling. The local initializer
currently creates **HSQLDB + local authentication only**. Other database/OIDC/AI
settings use the same normal Spring properties and protected configtree; they are
not a new GUI wizard. See the existing configuration reference and Keycloak guide.
A bootstrap-secret change does not reset an already-created administrator account.

## Linux / Windows packages

See [native packaging](../deploy/native/README.md). The manual `Native packages`
workflow builds one application JAR, then creates an app image and DEB/MSI on their
respective platforms. It produces review artifacts, not a published/signed release.
The build helper also supports RPM and EXE on suitable target runners. Canonical
Maven CI remains the merge acceptance gate; a package build alone is not that gate.

The app image includes three launchers: `Taxonomy`, `Taxonomy-Configure`, and
`Taxonomy-Check`. There is no separate desktop application and no browser-open
requirement. Open the configured loopback port in a browser after normal startup.
Missing configuration does not launch a public installer. Choose the configure
launcher explicitly. Linux requires a console for interactive configuration.

## Updates, Dienste und verbleibende Abnahme

Installationspakete enthalten keine Nutzerdaten. Ein Paketwechsel schreibt nicht
in `.taxonomy`; Konfiguration und Daten sind separat zu sichern. Der Einrichtungs-
befehl wird beim Update nicht erneut ausgeführt. Ein Helm- oder Paket-Rollback
macht Datenbankmigrationen **nicht** rückgängig. Vor Updates: Sicherung erstellen,
Wiederherstellung testen und den bestehenden Migrationsleitfaden beachten.

Für den Serverbetrieb sind Dienstkonto, systemd beziehungsweise ein geeigneter
Windows-Dienstmechanismus, TLS/Reverse Proxy, Netzwerkfreigaben und Sicherung durch
den Betreiber einzurichten. Automatische Dienstregistrierung, ein lokaler grafischer
Voll-Wizard, signierte Veröffentlichungen und eine vollständige Installieren–Update–
Deinstallieren-Abnahme sind **nicht** Bestandteil dieses ersten PRs. Die Installer
sind ohne diese Plattformabnahme noch nicht als produktionsfertig freigegeben.

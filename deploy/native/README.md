# Native Taxonomy packages

Builds wrap the **same repackaged Spring Boot JAR**, using JDK 21 `jpackage` and an
included runtime. There is no second application or mutable installed configuration.
Read [installation setup](../../docs/INSTALLATION_SETUP.md) before using the result.

```sh
java deploy/native/PackageTaxonomy.java taxonomy-app/target/taxonomy-app-1.4.1-SNAPSHOT.jar 1.4.1 app-image target/native-image
java deploy/native/PackageTaxonomy.java taxonomy-app/target/taxonomy-app-1.4.1-SNAPSHOT.jar 1.4.1 deb target/native-installer
```

Pass the exact executable JAR and a numeric package version matching the intended
release. The helper verifies the Boot Main-Class/Start-Class, preserves space-containing
paths as individual process arguments and records the input JAR SHA-256. `app-image`
requires only the JDK; DEB requires `fakeroot`/dpkg; RPM requires RPM build tools;
MSI/EXE with JDK 21 require WiX 3 `candle.exe` and `light.exe` on Windows. Builds must
run on the target OS. No signing keys, package manager downloads or releases are
created by the helper. The GitHub workflow is explicitly/manual-dispatch only.

The app-image check executes the supplied application's `--setup-help` using its
bundled runtime. This verifies launcher entry, not DB migration/login/restart.
Command-construction contracts run from Maven's `SetupInfrastructureTest`; tests
of the pure setup code and Spring property precedence live in `SetupCommandTest`.

Windows packages are per-user (not a machine-wide service) and retain a fixed
upgrade UUID. All launchers carry a JVM option selecting native mode, so passing
application arguments cannot accidentally enable development defaults. The default
configuration location is `${user.home}/.taxonomy/application.properties`; a missing
or unreadable file blocks normal startup and native checks, even with
`--spring.config.additional-location=optional:file:/missing.properties`. The
mandatory installation is loaded first; additional locations retain their normal
Spring precedence as overlays. Native mode rejects in-memory HSQLDB, including a
silent fallback caused by an empty configuration. Help and configuration commands
remain available before installation.

Select a different installation directory with the JVM option
`-Dtaxonomy.config-directory=/absolute/config` (Windows example:
`-Dtaxonomy.config-directory=C:\Taxonomy\config`) for every launcher. For packaged
launchers this can be supplied through `JAVA_TOOL_OPTIONS` or a managed launcher
configuration; do not put secrets in JVM options. `--configure=/absolute/config`
selects the write destination only and does not persist a new launcher default.

Configure explicitly with `Taxonomy-Configure` or run
`Taxonomy --configure-local` with `TAXONOMY_ADMIN_PASSWORD` injected securely.
`Taxonomy-Check` does not create a web server or database. The normal `Taxonomy`
launcher starts the server, after static validation. Use the browser at the
configured local address. No automatic OS service registration is included.

Static validation checks every supplied `custom.llm.url` even when `llm.provider`
is blank (automatic selection) or another provider is selected. Use HTTPS, or HTTP
only on exact loopback, without URL credentials, query or fragment. Automatic
custom configuration requires `custom.llm.model` alongside the URL; authentication
belongs in the separate configtree. No inference or provider request is performed
by this validation.

Changing packages must not delete `.taxonomy`. Back up that directory and any
external database before updating. Never run initial setup over an existing directory.
A package rollback cannot undo a migrated schema. Installer signing, real Windows
installation/uninstallation and previous-release upgrade tests remain explicit
release requirements; generated artifacts do not assert those requirements passed.

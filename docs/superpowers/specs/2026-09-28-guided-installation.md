# Guided installation

Implement the installation design approved in the conversation: extend the existing
Rancher/Helm interface, provide shared configuration diagnostics, and package the
same Spring Boot application for Linux/Windows. No new server, identity provider,
or independently stored application configuration.

## Contract

- Preserve explicit existing Helm configuration by default. Guided local-account
  and Keycloak choices map to the existing profiles; AD remains Keycloak federation.
- Keep passwords/API keys in existing Secrets or a protected configtree, never in
  ordinary Helm values, diagnostics, command-line arguments or generated examples.
- An explicit CLI check loads the effective Spring configuration before creating
  the application context. It must not initialize JPA, Flyway, the web server,
  accounts or AI. Static and opt-in connection checks are distinct. Unperformed
  checks are reported as NOT_CHECKED rather than successful.
- Local configuration is generated only on explicit request and never overwrites
  an existing installation. Default binding is loopback. File HSQLDB uses durable
  storage and non-destructive schema settings; PostgreSQL retains its migration
  contract. No automatic public first-run wizard or fallback login.
- Native packages include the same executable JAR and a Java runtime. Configuration
  and data live outside the installation directory and survive package replacement.
  No automatic update, database rollback, service registration or destructive uninstall.
- Installation-as-a-service is a separate operator action. Do not claim a tested
  Windows service without a service wrapper and platform verification.

## Acceptance

Tests cover authentication conflicts, persisted-data destruction, missing provider
settings, credential redaction, explicit checks, configtree generation, refusal to
overwrite, profile projection and a headless local setup. Helm negative cases and
native package smoke tests are executable in CI. Report platform tests not run.

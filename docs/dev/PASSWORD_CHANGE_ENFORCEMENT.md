# Temporary password enforcement

Local form-login and HTTP Basic accounts can be required to replace a bootstrap or administrator-assigned password before using the application.

## Configuration

```properties
taxonomy.security.require-password-change=true
taxonomy.security.change-password-enabled=true
```

The production Compose configuration enables enforcement. Keycloak deployments do not use this local lifecycle; the identity provider remains responsible for required actions and password policy.

## Account lifecycle

When enforcement is active, `must_change_password` is set for:

- the initial administrator created during bootstrap;
- an existing administrator still using the legacy `admin` credential when enforcement is enabled later;
- newly created local users;
- users whose password is reset by an administrator.

A successful self-service replacement updates the password hash and clears the flag in one transaction.

## Browser behavior

An authenticated restricted user is redirected to `/change-password`. Login, logout, password replacement, error handling, and static assets remain reachable so the user cannot become trapped behind the enforcement filter. After a successful replacement, the browser returns to the application.

## REST behavior

Protected API calls authenticated with HTTP Basic return:

- HTTP `428 Precondition Required`;
- error code `PASSWORD_CHANGE_REQUIRED`;
- the replacement endpoint `/api/account/change-password`.

The client submits the current password, new password, and confirmation to that endpoint using the temporary HTTP Basic credential. After success, subsequent API calls use the replacement credential normally.

## Persistence migration

The idempotent schema-contract migration creates `app_user.must_change_password` with a non-null false default on HSQLDB, PostgreSQL, Microsoft SQL Server, and Oracle. Existing accounts are not unexpectedly locked unless the explicit legacy-admin upgrade condition applies.

## Verification

Regression tests cover browser redirection, the real HTTP Basic 428-to-success flow, flag clearing, administrator-created/reset credentials, validation failures, and Keycloak separation. Full CI also exercises database compatibility, container startup, UI, accessibility, CodeQL, Trivy, and reactor-wide coverage.

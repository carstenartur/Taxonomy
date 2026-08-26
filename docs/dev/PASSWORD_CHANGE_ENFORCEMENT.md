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

An authenticated restricted user is redirected to the password-change page inside the active servlet context. At the root context this is `/change-password`; below a prefix such as `/taxonomy` it is `/taxonomy/change-password`.

Route comparisons use the application path after removing the servlet context. Login, logout, password replacement, error handling, CSS, JavaScript, images, and webjars therefore remain reachable under both root and prefixed deployments, so the user cannot become trapped behind the enforcement filter. After a successful replacement, the browser returns to the application.

## REST behavior

Protected API calls authenticated with HTTP Basic return:

- HTTP `428 Precondition Required`;
- `Cache-Control: no-store`;
- error code `PASSWORD_CHANGE_REQUIRED`;
- a context-aware `changePasswordEndpoint`.

The endpoint is `/api/account/change-password` at the root context and `/taxonomy/api/account/change-password` below the example prefix. The client submits the current password, new password, and confirmation to that endpoint using the temporary HTTP Basic credential. After success, subsequent API calls use the replacement credential normally.

## Filter registration

`PasswordChangeRequiredFilter` is registered exactly once inside the local-user Spring Security chain, after form-login and HTTP-Basic authentication can establish the authoritative principal. Ordinary servlet-container registration is explicitly disabled. The filter and its registration configuration remain excluded from the `keycloak` profile.

## Persistence migration

The idempotent schema-contract migration creates `app_user.must_change_password` with a non-null false default on HSQLDB, PostgreSQL, Microsoft SQL Server, and Oracle. Existing accounts are not unexpectedly locked unless the explicit legacy-admin upgrade condition applies.

## Verification

Regression tests cover root and prefixed browser redirection, reachable lifecycle and static paths, root and prefixed real HTTP Basic 428-to-success flows, non-cacheable context-aware API responses, exactly one Security-chain registration, disabled servlet registration, flag clearing, administrator-created and reset credentials, validation failures, and Keycloak separation. Full CI also exercises database compatibility, container startup, UI, accessibility, CodeQL, Trivy, reactor-wide coverage, and the strict bounded-context architecture gate.

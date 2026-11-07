# Migrating to Keycloak and explicit REST permissions

This guide covers both switching from local form login to Keycloak/OIDC and upgrading an existing deployment to the four-role, explicit-scope REST model. Configure the identity provider and clients **before** deploying the access-policy change. A realm-export update does not migrate an existing realm automatically.

## Prepare roles, scopes and clients before deployment

1. Inventory application roles and the API operations used by each browser integration and service account. Use the [permission matrix](SECURITY.md#roles-and-permissions) and each operation's Swagger access table.
2. Add `ROLE_READER` for read-only accounts. Keep `ROLE_USER` as the stored Analyst role: it permits analysis execution and is not a read-only role. Retain `ROLE_ARCHITECT` and `ROLE_ADMIN` as independent capabilities.
3. Review administrator assignments. ADMIN alone no longer grants analysis execution or business writes. Assign `ROLE_ARCHITECT` explicitly to administrators who need architecture editing; assign USER or ARCHITECT for analysis execution. Preserve intended existing multi-role assignments. The local bootstrap account already receives USER + ARCHITECT + ADMIN explicitly and retains those capabilities; startup seeds the reader role idempotently.
4. Create/assign the exact API client scopes from the permission matrix. A permission-protected Bearer call requires **an allowed role in every required permission group AND every listed scope**. Tokens containing roles but no API scopes have no fallback and receive 403 for those operations; scopes alone also fail. `openid profile email roles` do not replace API scopes.
5. Keep `taxonomy-app` as the confidential server-side OIDC session-login client. Add the separate public `taxonomy-swagger` client for Authorization Code with PKCE S256; configure its exact Swagger redirect URI and optional API client scopes. Configure `KEYCLOAK_SWAGGER_CLIENT_ID` if using another ID. Never put the server client secret into Swagger/browser configuration.
6. Give each machine integration a separate confidential Client Credentials client with explicit service-account roles/scopes and repository memberships or trusted grants. A minimal read-only export integration uses READER and `architecture:read integration:export`.
7. Add/verify the API audience mapper on all API clients, including Swagger and service accounts. Issue **fresh access tokens** after role/scope/mapper changes and verify issuer, subject, audience, known roles and requested scopes before switching traffic. Existing tokens do not acquire new claims automatically.

See [Keycloak setup](KEYCLOAK_SETUP.md) for client, scope, PKCE and Client Credentials configuration. Browser sessions and local Basic callers continue to use roles with their existing CSRF rules; this does not provide a scope fallback for Bearer tokens.

## Account for operation effects and combined permissions

Do not classify operations from HTTP method alone: `GET /api/analyze-stream` and `GET /api/analyze-node` start new LLM work and require ANALYSIS_RUN (USER or ARCHITECT; Bearer scope `analysis:run`). Lazy/default working-copy creation from `GET /api/workspace/current` needs that permission too. Readers without a ready working copy receive 204 and continue in the central read-only context; existing ready working copies remain readable under resource checks.

Before upgrading integrations that call `POST /api/workspace/external/push` or `/api/workspace/external/full-sync`, assign both ARCHITECT and ADMIN, and request both `architecture:publish` and `admin:configuration` in Bearer tokens. These permission groups are conjunctive. Browser sessions need both roles; neither role alone is sufficient.

`architecture:delete` protects dedicated REST DELETE endpoints. `architecture:write` permits the entire command/DSL edit operation, which may remove or replace content; scopes do not filter the semantic differences inside an edit. Read-only scopes still grant no business edits.

Swagger documents every required permission group and scope and offers `sessionCookie` as an alternative in OIDC mode too. An existing OIDC session uses roles and CSRF for browser writes; a JWT must carry the required roles and all scopes. Verify the intended authentication path instead of treating a working session as proof that a Bearer token has sufficient scopes.

## Prepare the access-token audience

The Keycloak API requires an access-token `aud` containing `KEYCLOAK_CLIENT_ID` (default `taxonomy-app`). Tokens without that audience receive HTTP 401 even with a valid issuer, signature and roles. `azp` does not replace `aud`.

For each API client, configure an Audience mapper with **Included Client Audience** = `taxonomy-app`, **Add to access token** = On and **Add to ID token** = Off. Keep the role mappers. For a custom `KEYCLOAK_CLIENT_ID`, align the mapper. For a distinct API audience, set `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=taxonomy-api` and **Included Custom Audience** = `taxonomy-api`. Keep the audience nonempty. Overriding only the standard Spring browser client ID does not update the `KEYCLOAK_CLIENT_ID` default; set the API audience explicitly in that case. The public Swagger client ID remains separate from the API audience.

## Preserve ownership and repository access

When moving existing local accounts to OIDC in default `LEGACY` principal mode, preserve their existing usernames and intended explicit roles. `Authentication.getName()` remains based on `preferred_username` for browser login (Bearer conversion falls back to subject when that claim is absent/blank). Verify access to existing private repositories and workspaces with the migrated identity.

`STABLE` mode explicitly resolves verified issuer/subject to a persisted principal and is required for portable backups. Do not enable it on an existing installation without a verified ownership migration; matching display names do not establish an identity binding.

Manage organizational groups in the identity provider and map them to explicit roles there. There is no new local group database. Existing stored repository memberships, visibility/lifecycle checks and personal-workspace ownership continue to apply.

If using `taxonomy_repository_roles`, emit a validated OIDC/JWT map of **exact repository ID → single role string** for the authenticated actor, for example:

```json
{"taxonomy_repository_roles":{"repository-a":"READER","repository-b":"CONTRIBUTOR"}}
```

Only `READER`, `CONTRIBUTOR`, `MAINTAINER` and `OWNER` are valid values. Arrays, `ROLE_` prefixes, blank/padded IDs and unknown values invalidate the claim mapping. Repository IDs are matched literally; wildcards are not expanded. Claims cannot grant permissions for another actor named in a request, replace global roles/scopes or override foreign-workspace ownership. Review mapper rules alongside stored memberships before deployment.

## Switching from local login

| Area | Local-user mode | Keycloak/OIDC mode |
|---|---|---|
| Browser login | Form login and server session | OIDC redirect and server session |
| API clients | HTTP Basic | Bearer access tokens with explicit roles/scopes |
| Account/password administration | Local user management and password-change page | Identity provider |
| Account bootstrap | `SecurityDataInitializer` | Identity-provider provisioning |
| Organizational groups / LDAP / AD / SAML | No local group/federation subsystem | Configure federation and role mapping at the identity provider |

For development, `docker compose -f docker-compose-keycloak.yml up -d` starts the example composition. Development credentials must not be used in production. For standalone deployment, configure database/deployment profiles and:

```bash
export SPRING_PROFILES_ACTIVE=postgres,keycloak
export KEYCLOAK_ISSUER_URI=https://idp.example/realms/taxonomy
export KEYCLOAK_JWK_SET_URI=https://idp.example/realms/taxonomy/protocol/openid-connect/certs
export KEYCLOAK_CLIENT_ID=taxonomy-app
export KEYCLOAK_CLIENT_SECRET='<server-login-client-secret>'
export KEYCLOAK_SWAGGER_CLIENT_ID=taxonomy-swagger
```

Use the guided Helm/production instructions in [setup](KEYCLOAK_SETUP.md#guided-helm-and-production-startup) for production. A generic OIDC provider additionally needs aligned Spring provider/client settings, the configured role-claim path and Swagger authorization/token URL overrides; LDAP/AD and SAML federation remain at the provider.

## Verify before completing the upgrade

Verify real browser session login and Swagger PKCE login separately. Confirm Swagger shows only active authentication schemes and all per-operation permission groups, role alternatives, scopes, effects, resource restrictions and 401/403 responses from the shared policy. Check fresh Bearer tokens against a permitted operation, then remove each required role/group and scope independently and confirm denial. Include the analysis-running GETs, combined external push/full-sync requirements and reader working-copy 204 behavior. Check ADMIN-only business-write/analysis denial, READER write denial, intended explicit multi-role access and private-repository/foreign-workspace isolation. Unclassified writes remain denied for every role.

HTTP 401 points to authentication/token validity or audience; HTTP 403 points to a missing operation role, scope or resource permission. These are deployment checks to perform, not a claim that a target deployment has already passed them.

## Reverting to local login

Remove `keycloak` from `SPRING_PROFILES_ACTIVE` while retaining the required database/deployment profiles. Local account components become active again; ensure their credentials, production bootstrap requirements and ownership data are valid before switching traffic. Removing the profile does not copy external identities or group grants into local accounts.

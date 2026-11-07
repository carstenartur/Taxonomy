# Keycloak & SSO Setup

## Guided Helm and production startup

Use `authentication.mode=keycloak` for the guided Rancher/Helm setup. It activates
`production,keycloak` while preserving the database/deployment profiles. Supply
`KEYCLOAK_CLIENT_SECRET` through the selected Secret and HTTPS issuer/JWK endpoints;
no `TAXONOMY_ADMIN_PASSWORD` or local bootstrap account is required. The production
guard still rejects missing central-login settings, conflicting local management,
and an unsafe optional Actuator/admin machine token (`ADMIN_PASSWORD` environment
variable, normally mapped from the separate `ADMIN_TOKEN` Secret key). Do not disable
production checks or introduce a dummy local password. The ordinary non-production
Keycloak development examples below are not the guided production contract.

See [guided installation](../INSTALLATION_SETUP.md) for exact configuration/check
commands and [database Pods](../../deploy/helm/taxonomy/DATABASE_PODS.md) for external
PostgreSQL/MSSQL Services. A successful preflight or guard startup is not proof of a
successful OIDC login, role mapping or TLS setup in the target cluster.

## Authentication and authorization

The `keycloak` Spring profile already implements browser OIDC login, Bearer JWT validation and role mapping. Activate the profile and configure the identity provider; no replacement `SecurityConfig`, custom token filter or HTTP Basic fallback is required. Without this profile, local form login and HTTP Basic remain available.

The four application roles are `ROLE_READER`, `ROLE_USER` (Analyst; stored name retained), `ROLE_ARCHITECT` and `ROLE_ADMIN`. ADMIN is an independent technical-administration capability, not an implicit architecture editor or analysis runner. Assign ARCHITECT explicitly to administrators who need business writes. Existing explicit multi-role accounts, including the local bootstrap USER + ARCHITECT + ADMIN assignment, keep those capabilities. See the [role/scope matrix](SECURITY.md#roles-and-permissions).

Every permission-protected Bearer API operation requires an allowed application role within each required permission group and all its explicit OAuth scopes. There is no fallback for role-bearing tokens without scopes. Project/repository visibility, membership and personal-workspace ownership checks remain additional requirements. Read-only POST, analysis execution, import/export, business writes and administration have separate permissions. Unclassified writes remain denied, including for administrators.

The catalogue can require several permission groups together. `POST /api/workspace/external/push` and `/api/workspace/external/full-sync` require ARCHITECT + `architecture:publish` **and** ADMIN + `admin:configuration` for Bearer clients; sessions need both roles. Swagger displays all required groups/scopes. `architecture:delete` covers dedicated REST DELETE endpoints, while `architecture:write` authorizes a complete command/DSL edit, including removing or replacing content. These are endpoint permissions, not semantic-diff restrictions; read-only scopes do not authorize edits.

`GET /api/analyze-stream` and `GET /api/analyze-node` execute new LLM work and require ANALYSIS_RUN (USER or ARCHITECT and, for Bearer calls, `analysis:run`). The same permission guards lazy/default working-copy creation from `GET /api/workspace/current`. Readers without a ready working copy receive 204 and use the central read-only context; an existing ready working copy remains readable subject to resource checks.

## Quick Start with Docker Compose

```bash
docker compose -f docker-compose-keycloak.yml up -d
```

This is a development composition, with the application at `http://localhost:8080` and Keycloak at `http://localhost:8180`. The imported development users and credentials are not a production provisioning method. Existing realms are not migrated by changing the realm-export file; follow [migration](KEYCLOAK_MIGRATION.md) before upgrading.

## Keycloak Realm Configuration

Create/use the `taxonomy` realm and configure three separate client purposes:

| Client | Type and flow | Redirect / use |
|---|---|---|
| `taxonomy-app` | Confidential; Authorization Code | Server-side session login, callback `https://app.example/login/oauth2/code/keycloak`; secret stays on the server. |
| `taxonomy-swagger` | Public; Authorization Code with PKCE S256 | Swagger callback `https://app.example/swagger-ui/oauth2-redirect.html`; no client secret. |
| A deployment-specific service client, e.g. `taxonomy-export-service` | Confidential; Client Credentials/service accounts | Machine API calls; separate secret and explicit service-account permissions. |

Use exact deployment redirect URIs and origins, including any servlet context path. Enable the standard authorization-code flow for the browser clients. On `taxonomy-swagger`, disable client authentication, password/direct-access grants and service accounts, and require PKCE S256. Do not reuse the server session-login secret in Swagger. The service client needs service accounts enabled; interactive redirects and password grants are unnecessary for that purpose.

### Roles and organizational groups

Create the four known realm roles:

| Role | Capability |
|---|---|
| `ROLE_READER` | Read data and existing analyses; permitted exports. |
| `ROLE_USER` | Analyst: read/export and execute analyses. |
| `ROLE_ARCHITECT` | Architecture editing, import, publication and analysis execution. |
| `ROLE_ADMIN` | Technical administration, users/sessions and diagnostics; no implicit business writes or analysis execution. |

Assign roles directly or through identity-provider-managed organizational groups. For example, map `taxonomy-readers` to READER, `taxonomy-analysts` to USER, `taxonomy-architects` to ARCHITECT, and `taxonomy-admins` to ADMIN. These mappings are independent; do not make ADMIN a composite that silently includes all business capabilities. If one account needs several capabilities, assign those roles explicitly. Choose any default role deliberately; USER allows analysis execution and is not a read-only default.

Configure the role mapper to emit known role strings under `realm_access.roles` in access tokens and the claims used for browser OIDC login. Both browser and JWT role extraction use `taxonomy.keycloak.role-claim-path` (default `realm_access.roles`). Only the four known role strings are accepted; arbitrary group names and unknown roles do not grant access.

### API client scopes

Create client scopes with the exact names in the [permission matrix](SECURITY.md#roles-and-permissions), and ensure requested scopes are included in the access-token `scope` claim. The application also supports the standard `scp` claim through Spring's scope converter. Role claims and scope claims are separate: neither supplies the other.

Assign the needed API client scopes as **optional** scopes to `taxonomy-swagger`, so the user can select them in Swagger's Authorize dialog. Configure the authorized API client scopes for service clients as well. Keep OIDC login scopes such as `openid`, `profile`, `email` and `roles`; they do not satisfy API permissions. Limit role scope mappings and API scopes to the capabilities intended for each client/account.

For a read-only export integration, use `ROLE_READER` with `architecture:read integration:export`. Reading existing analyses additionally needs `analysis:read`; backups have separate scopes. The access table on each Swagger operation gives all required groups/scopes, role alternatives and resource restrictions.

### 6. Include the API audience in access tokens

The Keycloak profile requires an access-token `aud` containing `taxonomy-app` by default. A valid signature, issuer and `azp` alone are insufficient; a missing/wrong audience produces HTTP 401. A list is accepted when it contains the configured audience.

Add an Audience mapper for every client authorized to call the API, including `taxonomy-swagger` and service clients: **Included Client Audience** = `taxonomy-app`, **Add to access token** = On, **Add to ID token** = Off. Keep the role mappers. Obtain a fresh token after mapper/scope changes. Importing a development realm does not update an existing realm automatically.

The application defaults `spring.security.oauth2.resourceserver.jwt.audiences` to `${KEYCLOAK_CLIENT_ID:taxonomy-app}`. For a custom `KEYCLOAK_CLIENT_ID`, align the mapper. To use a separate resource audience, set `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=taxonomy-api` and the mapper's **Included Custom Audience** to `taxonomy-api`. Keep the required audience nonempty. A standard Spring override of only the browser client ID does not change the `KEYCLOAK_CLIENT_ID` placeholder; configure the API audience explicitly in that case. Changing the Swagger client ID does not change the API audience.

## Application Configuration

Keep deployment secrets in the selected secret store. A standalone configuration uses:

```bash
export SPRING_PROFILES_ACTIVE=postgres,keycloak
export KEYCLOAK_ISSUER_URI=https://idp.example/realms/taxonomy
export KEYCLOAK_JWK_SET_URI=https://idp.example/realms/taxonomy/protocol/openid-connect/certs
export KEYCLOAK_CLIENT_ID=taxonomy-app
export KEYCLOAK_CLIENT_SECRET='<server-login-client-secret>'
export KEYCLOAK_SWAGGER_CLIENT_ID=taxonomy-swagger
export KEYCLOAK_ADMIN_URL=https://idp.example
```

Configure database and production profiles as described in the deployment guide. `taxonomy.keycloak.principal-mode` defaults to `LEGACY`, preserving existing username-based ownership. `STABLE` is an explicit opt-in resolving issuer/subject to a persisted principal, and is required for portable backups. Do not switch an existing installation to STABLE without a verified ownership migration; changing the display name is not a verified identity link.

## Swagger Authorization Code with PKCE

The application configures:

```properties
springdoc.swagger-ui.oauth.client-id=${KEYCLOAK_SWAGGER_CLIENT_ID:taxonomy-swagger}
springdoc.swagger-ui.oauth.use-pkce-with-authorization-code-grant=true
```

Open Swagger UI and use **Authorize** with the public Swagger client. Select the API scopes needed for the operations, sign in at the identity provider and return through the exact Swagger redirect URI. Do not enter a confidential client secret in the browser. A successful login does not grant operations whose role, scope or repository/workspace check is missing.

OpenAPI advertises only active authentication schemes, including `sessionCookie` as an alternative in the OIDC profile. Existing OIDC sessions use roles and CSRF protection for browser writes; JWTs require roles and all scopes. Operations retain functional tags and show a policy-derived access table with every required permission group, its role alternatives, all Bearer scopes, operation effect, resource restrictions and 401/403 responses. Default OAuth URLs are the issuer plus `/protocol/openid-connect/auth` and `/protocol/openid-connect/token`. Override them with `taxonomy.keycloak.swagger.authorization-url` and `taxonomy.keycloak.swagger.token-url` when the provider uses other endpoints or the browser needs different public URLs.

## Service accounts and Client Credentials

Use a separate confidential client for automation. Assign its service account explicit application roles, authorize the requested API scopes and include the API audience. Private repository operations also require memberships for the authenticated service principal or trusted repository-role claims. Global ADMIN does not bypass these checks.

For a minimal read-only export client, configure READER and `architecture:read integration:export`, then obtain a token using its own credentials:

```bash
TOKEN=$(curl --fail --silent --show-error \
  'https://idp.example/realms/taxonomy/protocol/openid-connect/token' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'client_id=taxonomy-export-service' \
  --data-urlencode "client_secret=${TAXONOMY_SERVICE_CLIENT_SECRET}" \
  --data-urlencode 'scope=architecture:read integration:export' | jq -er '.access_token')
curl --fail -H "Authorization: Bearer $TOKEN" 'https://app.example/api/taxonomy'
```

Inspect tokens locally without publishing them. A suitable payload includes the verified issuer/subject, `aud` containing `taxonomy-app`, `realm_access.roles` containing `ROLE_READER`, and `scope` containing the two requested scopes. A scope-only token is insufficient. Use the operation description to determine the additional resource access required for exports.

## Repository grants from trusted claims

For organization-specific repository access, an identity-provider mapper may issue:

```json
{
  "taxonomy_repository_roles": {
    "repository-a": "READER",
    "repository-b": "CONTRIBUTOR"
  }
}
```

Each key is an exact repository ID and each value is one `READER`, `CONTRIBUTOR`, `MAINTAINER` or `OWNER` string. Arrays, `ROLE_` prefixes, blank/padded keys and unknown values are invalid; a malformed entry invalidates the whole map. Keys are matched literally; wildcard expansion is not supported. Supply the claim only through validated JWT/OIDC identity-provider claims, bound to the currently authenticated actor. It is evaluated alongside stored memberships; it cannot grant access on behalf of another username supplied in a request. Repository grants do not replace application roles, API scopes, lifecycle checks or personal-workspace ownership. See [Security](SECURITY.md#organizational-groups-and-repository-grants).

## User Federation and SSO Federation

Configure LDAP/Active Directory user federation or upstream SAML/OIDC identity providers in Keycloak. The application remains an OIDC client and JWT resource server. For SAML, exchange metadata and validate signatures at the broker; for LDAP/AD, configure directory connectivity and group mapping there. There is no direct SAML login path or local organizational-group database in Taxonomy.

Map trusted upstream groups/attributes to the explicit application roles and, where needed, the repository claim above. Configure MFA, identity-provider session policies, deprovisioning and audit events at the provider; verify the resulting application session and token behavior in the deployment. Do not infer application access merely from successful federation.

### Generic OIDC provider

The implemented OIDC profile can be configured for another provider. Override the Spring client/provider settings under `spring.security.oauth2.client.registration.keycloak.*` and `spring.security.oauth2.client.provider.keycloak.*`, plus `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `jwk-set-uri` and `audiences`. Align the username claim with the legacy ownership contract, and set `taxonomy.keycloak.role-claim-path` for the provider's nested role claim. The emitted roles must still be the four known `ROLE_*` strings. Override both Swagger OAuth URLs when they differ from the Keycloak defaults. Provider-specific account-console/logout behavior also needs deployment verification.

## Testing the Integration and Troubleshooting

Validate browser login with the confidential server client and Swagger login with the separate public PKCE client. Test READER reads/exports and write denial; USER analysis execution; ARCHITECT business writes; ADMIN administration with business writes denied unless ARCHITECT is explicitly assigned. For Bearer clients, satisfy every required permission group and all scopes, then remove each required role/group or scope independently and expect denial. Include the analysis-running GETs and the combined external push/full-sync permissions. Exercise private-repository and foreign-workspace denials as well.

| Result | Check |
|---|---|
| HTTP 401 | Issuer, signature/JWK, expiry, audience and token validity; `azp` is not the API audience. |
| HTTP 403 | Role satisfaction for every permission group, all operation scopes, repository membership/claim and workspace ownership. |
| Swagger redirect/PKCE failure | Public Swagger client, exact redirect/origin, PKCE S256, public authorization/token URLs. |
| Login succeeds but roles are absent | Trusted group-to-role mapping, role claim path and token/client scope mapping. |
| Existing private data appears absent | Principal mode and legacy username alignment; do not relink by display name. |

Use fresh tokens after permissions/mappers change. Keep clocks synchronized and trust the deployed HTTPS certificate chain. Protect Swagger/OpenAPI according to deployment policy; `TAXONOMY_SWAGGER_PUBLIC` controls exposure, not API authorization. Review authentication/audit events without logging secrets or complete tokens.

## Production Considerations

Use HTTPS, exact redirect/origin allowlists, separately managed client secrets, an appropriately operated identity-provider database and backup/recovery process. Verify provider availability, TLS, real browser login, roles, scopes and resource restrictions in the target environment. A startup check does not prove those flows.

See [Security](SECURITY.md), [Migration](KEYCLOAK_MIGRATION.md), [Deployment Checklist](DEPLOYMENT_CHECKLIST.md) and [Configuration Reference](CONFIGURATION_REFERENCE.md).

# REST permissions and visible OpenAPI security

The user approved implementing the four-role access model and the Swagger documentation proposed in this conversation. Build on existing Spring Security, OIDC/JWT authentication and repository/workspace authorization.

## Behavior

- Add `ROLE_READER` for read-only access. Retain `ROLE_USER` as the analyst role for compatibility, and `ROLE_ARCHITECT` and `ROLE_ADMIN`. Administration does not imply architecture writes or analysis execution. Existing accounts with multiple roles retain their explicitly assigned capabilities.
- Centralize ordered REST access rules and named permissions. The same rule must drive server authorization and visible operation descriptions, OAuth scopes, role requirements, resource restrictions, and 401/403 responses in OpenAPI.
- For bearer JWT calls require BOTH an allowed application role and the operation's OAuth scope. Scope possession alone never grants an application role or access to another user's repository/workspace. Local Basic and browser sessions use application roles and retain their existing CSRF rules.
- Preserve existing object-level checks. Support trusted OIDC group-to-role assignments through the identity provider. Repository-specific group grants may be conveyed in a validated `taxonomy_repository_roles` claim, bound to the current authenticated principal and checked alongside stored memberships; never trust request parameters as grants.
- Expose only the authentication schemes active in the deployment. OAuth Authorization Code with PKCE is the interactive Swagger path. Document Client Credentials for service accounts with explicit roles, scopes and repository membership. Keep local Basic and sessions supported. LDAP/AD and SAML federation belong at the identity provider.
- Group Swagger operations by existing functional tags and show readable access tables. State-changing, analysis execution, read-only POST, import/export and administration are distinct. Unknown writes remain denied, including for administrators. Descriptions must not advertise unimplemented grants.
- Use existing JUnit, Spring Security and OpenAPI contract infrastructure; run the canonical Maven build as far as the environment permits. Update German and English security and identity-provider setup/migration documentation.

## Approach

Extend the shared authorization boundary with an immutable ordered access catalogue rather than adding a parallel security filter or repeating permissions in controller annotations. Use that catalogue from the existing authorization configurer and a Springdoc customizer. Extend current role converters and repository membership evaluation rather than introduce another account database or authorization server.

An annotation-only design would duplicate the existing request rules. A gateway-only design would not protect internal calls or repository objects. Neither is used.

## Compatibility and limits

`USER` remains the stored analyst role name. Existing local bootstrap administrators already carry USER, ARCHITECT and ADMIN explicitly. Administrators needing business editing must be assigned ARCHITECT; bearer clients need explicit API scopes after upgrade. Optional independent approval is not added without a defined approval workflow. Organizational groups remain managed by the identity provider; no new local group-management subsystem is introduced.

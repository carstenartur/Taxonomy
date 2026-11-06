# REST access and OpenAPI implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development to implement this plan task by task.

**Goal:** Enforce four-role REST access and limited OAuth tokens while making the effective requirements visible in Swagger.

**Architecture:** A shared immutable request-policy catalogue drives both Spring Security and Springdoc. Identity converters recognize the reader role and token scopes; existing repository checks retain responsibility for individual objects.

**Tech Stack:** Java 25, Spring Boot 4, Spring Security, Springdoc, JUnit 6, Maven.

**Spec:** `docs/superpowers/specs/2026-11-06-rest-access-design.md`

## Global Constraints

- Retain `ROLE_USER` as the analyst role for compatibility.
- For bearer JWT calls require BOTH an allowed application role and the operation's OAuth scope.
- Unknown writes remain denied, including for administrators.
- Organizational groups remain managed by the identity provider; no new local group-management subsystem is introduced.
- Existing repository/workspace and CSRF checks remain additional requirements.

## Review Focus

- A token with read scope and an architect role must not modify data.
- A reader cannot execute analyses, create drafts or create working copies.
- An administrator without ARCHITECT cannot modify business data.
- A group grant for one repository must not authorize another repository or another principal.
- Swagger in each authentication profile must describe the same requirement enforced by the server, including read-only POST and unknown writes.

### Task 1: Shared request permissions

**Files:** Create `security/access/ApiPermission.java`, `security/access/ApiAccessPolicy.java`; modify `security/config/AuthorizationRulesConfigurer.java`; add focused HTTP security tests under `taxonomy-app/src/test/java/com/taxonomy/security/access/`.

**Interfaces:** `ApiAccessPolicy.resolve(String method, String path)` returns a rule carrying access kind, permission, role names, effect and resource restriction; `ApiAccessPolicy.authorize(Authentication, Rule)` evaluates the role/scope intersection. `ApiPermission` carries OAuth scope and permitted application roles.

- [x] Write tests proving read-only READER, denied analyst/reader writes, denied ADMIN-only business writes, JWT scope intersection, allowed read-only POST and deny-by-default writes.
- [x] Run selected tests and observe expected authorization failures.
- [x] Replace duplicated REST rules with the ordered catalogue while retaining page/static-resource rules.
- [x] Run focused security tests; expected no failures.

### Task 2: Role and identity integration

**Files:** Security data initializer, local user form/page/i18n, JWT/OIDC converters and their tests; Keycloak realm and setup/migration documentation.

**Interfaces:** Four known roles are `ROLE_READER`, `ROLE_USER`, `ROLE_ARCHITECT`, `ROLE_ADMIN`. JWT converters retain explicit `SCOPE_` authorities from `scope`/`scp`; browser role extraction uses the configured role claim path.

- [x] Add failing tests for READER and mixed roles/scopes.
- [x] Seed/offer the reader role and make labels describe the four capabilities accurately.
- [x] Recognize READER and scopes in trusted token conversion, retaining stable principal behavior.
- [x] Run converter, bootstrap and user-management tests; expected no failures.

### Task 3: Repository group grants

**Files:** Repository membership service and a small trusted-grant adapter; tests in taxonomy-workspace and taxonomy-app as appropriate.

**Interfaces:** Optional repository grant provider resolves an effective `RepositoryRole` for the currently authenticated principal from validated OIDC claims only. `taxonomy_repository_roles` maps exact repository IDs to READER/CONTRIBUTOR/MAINTAINER/OWNER.

- [x] Write failing tests for read-only group access, write denial, wrong repository, wrong principal and malformed/untrusted claims.
- [x] Combine explicit memberships with exact trusted grants without bypassing global API permissions or existing owner-retention checks.
- [x] Run repository authorization tests; expected no failures.

### Task 4: Visible OpenAPI requirements

**Files:** OpenAPI config, new security customizer, Swagger Keycloak config, application properties and OpenAPI/Swagger tests.

**Interfaces:** Consume the catalogue from Task 1, never a second endpoint permission map. Emit operation `security`, readable Markdown access tables, metadata, and 401/403 responses. OAuth scopes come from `ApiPermission`.

- [x] Add failing document-generation tests in local and Keycloak profiles.
- [x] Document active authentication schemes, OAuth scopes, Authorization Code and Client Credentials; enable Swagger PKCE without a browser client secret.
- [x] Generate visible role/effect/resource descriptions from the policy and preserve existing endpoint explanations and response schemas.
- [x] Run existing and extended OpenAPI contract checks; expected no failures.

### Task 5: Documentation, validation and review

**Files:** German/English SECURITY, KEYCLOAK_SETUP/MIGRATION and developer guidance; existing security tests whose ADMIN fixtures intentionally require business roles.

- [x] Update docs with the role matrix, group assignments, per-repository access, service-account scope examples, migration steps and Swagger usage.
- [x] Complete final affected suites and available local core test batches; record commands and environmental blockers.
- [x] Obtain one independent whole-branch code review and fix material findings with regression tests.
- [x] Commit the tested change.
- [ ] Publish the feature branch, open the pull request and check GitHub CI.

### Validation outcome

The final HTTP/JWT/Swagger/search regression selection passed 123 tests. The unchanged product demo passed four tests in an isolated JVM. The additional quota and architecture-boundary regression selection passed 41 tests, and the application-owned test placement check passed after registering the real full-application provisioning fixture. Core batches also exercised existing feature and build-policy tests; every failure found in those batches was corrected and its affected test rerun successfully.

Local Maven execution used a startup Mockito agent because this sandbox disallows runtime self-attachment, and omitted the unchanged native GPG-agent interoperability method because the agent IPC is unavailable. Dedicated browser, container, external-database, scenario and live-IdP deployment suites remain separate from the local core selection. The published feature branch must also pass GitHub CI.

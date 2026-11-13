package com.taxonomy.security.webdav;

import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.backup.IdentityBinding;
import com.taxonomy.security.keycloak.KeycloakPrincipalMode;
import com.taxonomy.security.keycloak.PrincipalJwtAuthenticationToken;
import com.taxonomy.security.model.PrincipalBinding;
import org.hsqldb.jdbc.JDBCDataSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.taxonomy.templates.DocumentTemplateWebDavLockManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebDavCredentialOwnershipTest {
    @Test
    void aLocalWriteGrantIsCheckedAgainAfterTheIssuingAccountLosesItsRole() {
        var database = database();
        var jdbc = new JdbcTemplate(database);
        jdbc.execute("create table app_user(id bigint primary key, username varchar(255), enabled boolean)");
        jdbc.execute("create table app_role(id bigint primary key, name varchar(255) not null)");
        jdbc.execute("create table user_roles(user_id bigint, role_id bigint)");
        jdbc.update("insert into app_user values (1, 'editor', true)");
        jdbc.update("insert into app_role values (1, 'ROLE_ADMIN'), (2, 'ROLE_USER')");
        jdbc.update("insert into user_roles values (1, 1), (1, 2)");
        PrincipalSchemaMigration.migrate(database);
        var identities = new PrincipalIdentityService(database);
        var account = identities.local(1);
        var roles = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
        var owner = UsernamePasswordAuthenticationToken.authenticated(
                new PrincipalUserDetails(account.id(), "editor", "", true, roles), null, roles);
        var repository = mock(WebDavApplicationCredentialRepository.class);
        var row = new AtomicReference<WebDavApplicationCredential>();
        when(repository.save(any())).thenAnswer(invocation -> {
            WebDavApplicationCredential credential = invocation.getArgument(0);
            row.set(credential);
            return credential;
        });
        when(repository.findByCredentialIdAndUsername(any(), any())).thenAnswer(invocation -> Optional.of(row.get()));
        var credentials = new WebDavApplicationCredentialService(repository, new BCryptPasswordEncoder(4),
                authority(identities, null));
        var issued = credentials.create(owner, "Desktop", true, true, 1);
        assertThat(credentials.authenticate("editor", issued.secret()).orElseThrow().writeAllowed()).isTrue();
        jdbc.update("delete from user_roles where user_id=1 and role_id=1");
        var reduced = credentials.authenticate("editor", issued.secret()).orElseThrow();
        assertThat(reduced.writeAllowed()).isFalse();
        assertThat(reduced.readAllowed()).isTrue();
        assertThat(reduced.authorities()).extracting(a -> a.getAuthority())
                .doesNotContain("ROLE_ADMIN", "SCOPE_template:write");
        assertThatThrownBy(() -> credentials.create(owner, "Another desktop", true, true, 1))
                .isInstanceOf(AccessDeniedException.class);
        jdbc.update("update app_user set enabled=false where id=1");
        assertThat(credentials.authenticate("editor", issued.secret())).isEmpty();
    }

    @Test
    void stableOidcChecksTheIssuingBindingEvenIfAnotherLoginForTheSamePrincipalSurvives() {
        var database = database();
        PrincipalSchemaMigration.migrate(database);
        var identities = new PrincipalIdentityService(database);
        var jdbc = new JdbcTemplate(database);
        String issuer = "https://issuer.example/realm";
        var account = identities.oidc(issuer, "subject-1");
        var second = new IdentityBinding(IdentityBinding.Kind.OIDC, issuer, "subject-2");
        jdbc.update("insert into principal_binding values (?, ?, 'OIDC', ?, ?, 1)",
                PrincipalBinding.key(second), account.id().value().toString(), second.issuer(), second.subject());
        var authority = authority(identities, KeycloakPrincipalMode.STABLE);
        var jwt = token(issuer, "subject-1");
        var login = new PrincipalJwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), account);
        var owner = authority.owner(login);
        var row = retained(owner);
        assertThat(owner.actor()).isEqualTo(account.id().value().toString());
        assertThat(owner.assertionExpiresAt()).isEqualTo(jwt.getExpiresAt());
        assertThat(authority.currentRoles(row, List.of("ROLE_ADMIN"))).containsExactly("ROLE_ADMIN");
        jdbc.update("update principal_binding set enabled=0 where binding_key=?", owner.binding());
        assertThat(identities.isEnabled(account.id())).isTrue();
        assertThat(authority.currentRoles(row, List.of("ROLE_ADMIN"))).isNull();
        assertThatThrownBy(() -> authority.owner(login)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void legacyBrowserAndBearerShareOnlyTheSameIssuerAndSubjectUntilTheAssertionExpires() {
        var database = database();
        PrincipalSchemaMigration.migrate(database);
        var identities = new PrincipalIdentityService(database);
        var legacy = authority(identities, KeycloakPrincipalMode.LEGACY);
        var roles = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var token = token("https://one.example/realm", "42");
        var bearer = new JwtAuthenticationToken(token, roles, "same-name");
        var idToken = new OidcIdToken("test", token.getIssuedAt(), token.getExpiresAt(), token.getClaims());
        var browser = new OAuth2AuthenticationToken(new DefaultOidcUser(roles, idToken), roles, "keycloak");
        var owner = legacy.owner(bearer);
        assertThat(legacy.owner(browser)).isEqualTo(owner);
        assertThat(legacy.owner(new JwtAuthenticationToken(token("https://two.example/realm", "42"), roles, "same-name")).actor())
                .isNotEqualTo(owner.actor());
        assertThat(legacy.owner(new JwtAuthenticationToken(token("https://one.example/realm", "43"), roles, "same-name")).actor())
                .isNotEqualTo(owner.actor());

        var repository = mock(WebDavApplicationCredentialRepository.class);
        var credentials = new WebDavApplicationCredentialService(repository, new BCryptPasswordEncoder(4), legacy);
        assertThat(credentials.create(browser, "Word", true, true, 30).credential().expiresAt()).isEqualTo(token.getExpiresAt());
        var past = Instant.now().minusSeconds(20);
        var expired = Jwt.withTokenValue("test").header("alg", "RS256").issuer("https://one.example/realm")
                .subject("42").issuedAt(past.minusSeconds(60)).expiresAt(past).build();
        assertThatThrownBy(() -> credentials.create(new JwtAuthenticationToken(expired, roles), "Word", true, true, 1))
                .isInstanceOf(AccessDeniedException.class);
        var retained = retained(owner);
        assertThat(legacy.currentRoles(retained, List.of("ROLE_ADMIN"))).containsExactly("ROLE_ADMIN");
        assertThat(authority(identities, KeycloakPrincipalMode.STABLE).currentRoles(retained, List.of("ROLE_ADMIN"))).isNull();
        identities.oidc("https://one.example/realm", "42");
        assertThatThrownBy(() -> legacy.owner(bearer)).isInstanceOf(AccessDeniedException.class);
        assertThat(legacy.currentRoles(retained, List.of("ROLE_ADMIN"))).isNull();
        new JdbcTemplate(database).update("update principal_binding set enabled=0 where binding_key=?", owner.binding());
        assertThat(legacy.currentRoles(retained, List.of("ROLE_ADMIN"))).isNull();
    }

    @Test
    void anExpiredOidcBrowserAssertionCannotListOrRevokeApplicationPasswords() {
        var database = database();
        PrincipalSchemaMigration.migrate(database);
        var ids = new PrincipalIdentityService(database);
        var roles = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var end = Instant.now().minusSeconds(20);
        var expired = new OidcIdToken("test", end.minusSeconds(60), end,
                Map.of("iss", "https://issuer.example", "sub", "reader", "exp", end, "iat", end.minusSeconds(60)));
        var login = new OAuth2AuthenticationToken(new DefaultOidcUser(roles, expired), roles, "keycloak");
        var repository = mock(WebDavApplicationCredentialRepository.class);
        var credentials = new WebDavApplicationCredentialService(repository, new BCryptPasswordEncoder(4),
                authority(ids, KeycloakPrincipalMode.LEGACY));
        assertThatThrownBy(() -> credentials.list(login)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> credentials.revoke(login, "a".repeat(24))).isInstanceOf(AccessDeniedException.class);
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void sameDisplayNameAcrossIssuersProducesDistinctWebDavLeaseOwners() throws Exception {
        var database = database();
        PrincipalSchemaMigration.migrate(database);
        var ids = new PrincipalIdentityService(database);
        var repository = mock(WebDavApplicationCredentialRepository.class);
        var rows = new java.util.HashMap<String, WebDavApplicationCredential>();
        when(repository.save(any())).thenAnswer(invocation -> {
            WebDavApplicationCredential row = invocation.getArgument(0);
            rows.put(row.getCredentialId(), row);
            return row;
        });
        when(repository.findByCredentialIdAndUsername(any(), any())).thenAnswer(invocation ->
                Optional.ofNullable(rows.get(invocation.getArgument(0, String.class))));
        var credentials = new WebDavApplicationCredentialService(repository, new BCryptPasswordEncoder(4),
                authority(ids, KeycloakPrincipalMode.LEGACY));
        var filter = new WebDavApplicationCredentialFilter(credentials);
        var locks = new DocumentTemplateWebDavLockManager(database);
        var roles = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var actors = new java.util.ArrayList<String>();
        for (String issuer : List.of("https://one.example", "https://two.example")) {
            var login = new JwtAuthenticationToken(token(issuer, "42"), roles, "same-label");
            var issued = credentials.create(login, "Word", true, true, 1);
            var request = new MockHttpServletRequest("LOCK", "/dav/templates/sample.dotx");
            String header = java.util.Base64.getEncoder().encodeToString(
                    ("same-label:" + issued.secret()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            request.addHeader("Authorization", "Basic " + header);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) ->
                    actors.add(SecurityContextHolder.getContext().getAuthentication().getName()));
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(actors.getLast()).isEqualTo(rows.get(issued.credential().id()).getOwnerId());
        }
        assertThat(actors).doesNotHaveDuplicates();
        var lease = locks.acquire("sample", actors.getFirst(), "a".repeat(40), java.time.Duration.ofMinutes(5), null);
        assertThat(locks.require("sample", lease.token(), actors.getFirst()).owner()).isEqualTo(actors.getFirst());
        assertThatThrownBy(() -> locks.require("sample", lease.token(), actors.getLast()))
                .isInstanceOf(DocumentTemplateWebDavLockManager.LockConflictException.class);
    }

    @Test
    void upgradeDoesNotAssignOrReactivateHistoricalCredentialsByUsername() {
        var database = database();
        var jdbc = new JdbcTemplate(database);
        jdbc.execute("create table webdav_application_credential (username varchar(255), secret_hash varchar(100))");
        jdbc.update("insert into webdav_application_credential values ('old-name','original-verifier')");
        PrincipalSchemaMigration.migrate(database);
        PrincipalSchemaMigration.migrate(database);
        assertThat(jdbc.queryForObject("select count(*) from webdav_application_credential where username='old-name' "
                + "and secret_hash='original-verifier' and owner_id is null and owner_binding is null", Integer.class)).isOne();
        var authority = authority(new PrincipalIdentityService(database), null);
        assertThat(authority.currentRoles(new WebDavApplicationCredential(), List.of("ROLE_ADMIN"))).isNull();
        var unverified = UsernamePasswordAuthenticationToken.authenticated("old-name", "", List.of());
        assertThatThrownBy(() -> authority.owner(unverified)).isInstanceOf(AccessDeniedException.class);
    }

    private static JDBCDataSource database() {
        var database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:webdav-owner-" + UUID.randomUUID());
        database.setUser("sa");
        return database;
    }

    private static WebDavCredentialAuthority authority(PrincipalIdentityService identities, KeycloakPrincipalMode mode) {
        var beans = new StaticListableBeanFactory();
        if (mode != null) beans.addBean("keycloakPrincipalMode", mode);
        return new WebDavCredentialAuthority(identities, beans.getBeanProvider(KeycloakPrincipalMode.class));
    }

    private static WebDavApplicationCredential retained(WebDavCredentialAuthority.Owner owner) {
        var row = new WebDavApplicationCredential();
        row.setOwnerId(owner.actor());
        row.setOwnerBinding(owner.binding());
        return row;
    }

    private static Jwt token(String issuer, String subject) {
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return Jwt.withTokenValue("test").headers(header -> header.putAll(Map.of("alg", "RS256")))
                .issuer(issuer).subject(subject).issuedAt(now).expiresAt(now.plusSeconds(240)).build();
    }

}

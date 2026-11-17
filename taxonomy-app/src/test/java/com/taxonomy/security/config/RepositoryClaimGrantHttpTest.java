package com.taxonomy.security.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.taxonomy.composition.architecture.RepositoryGrantIdentity;
import com.taxonomy.security.access.RepositoryClaimGrantProvider;
import com.taxonomy.security.access.WorkspaceFetchAuthorityAdapter;
import com.taxonomy.security.keycloak.KeycloakPrincipalMode;
import com.taxonomy.security.keycloak.PrincipalOidcUser;
import com.taxonomy.security.model.AppPrincipal;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.workspace.controller.ArchitectureRepositoryController;
import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryRole;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.RepositoryMembershipRepository;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import com.taxonomy.workspace.service.WorkspaceActorAccess;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RepositoryClaimGrantHttpTest {
    private static final Instant EXPIRES_AT = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS);
    private static final String ISSUER = "https://idp.test";
    private static final String SUBJECT = "architect";
    private static final String REPOSITORIES = "/api/repositories";

    private static final Map<String, String> GRANTS = Map.of("claim-only", "READER", "stored", "OWNER");

    @ParameterizedTest(name = "claim expiry in repository requests (bearer={0})")
    @ValueSource(booleans = {false, true})
    void claimGrantsExpireAtUseAndStoredRolesRemainAvailable(boolean bearer) throws Exception {
        try (var context = start(null)) {
            var actor = context.getBean(PrincipalIdentityService.class).oidc(ISSUER, SUBJECT);
            seedRepositories(context.getBean(SessionFactory.class), actor);
            var request = bearer ? get("/").header("Authorization", "Bearer " + signedAccessToken(SUBJECT, GRANTS))
                    : get("/").session(retainedSession(context, actor, GRANTS));
            var http = MockMvcBuilders.webAppContextSetup(context).defaultRequest(request).apply(springSecurity()).build();

            http.perform(get(REPOSITORIES)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].repositoryId", containsInAnyOrder("claim-only", "stored")));
            http.perform(get(REPOSITORIES + "/claim-only")).andExpect(status().isOk());
            http.perform(get(REPOSITORIES + "/stored/members")).andExpect(status().isOk());

            context.getBean(ClaimClock.class).now = EXPIRES_AT;
            http.perform(get(REPOSITORIES)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].repositoryId", containsInAnyOrder("stored")));
            http.perform(get(REPOSITORIES + "/claim-only")).andExpect(status().isNotFound());
            http.perform(get(REPOSITORIES + "/stored")).andExpect(status().isOk());
            http.perform(get(REPOSITORIES + "/stored/members")).andExpect(status().isForbidden());
            http.perform(get("/api/account/me")).andExpect(status().isOk());
        }
    }

    private static AnnotationConfigWebApplicationContext start(CurrentPrincipalAuthoritySecurityTest.Registry shared) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("keycloak");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "taxonomy.keycloak.principal-mode", "STABLE", "taxonomy.features.multi-repository-api.enabled", "true",
                "test.schema", shared == null ? "create-drop" : "validate")));
        if (shared != null) context.addBeanFactoryPostProcessor(beans -> beans.registerSingleton("registry", shared));
        context.register(CurrentPrincipalAuthoritySecurityTest.RegistryConfiguration.class,
                CurrentPrincipalAuthoritySecurityTest.OidcChain.class, CatalogueConfiguration.class);
        context.refresh();
        return context;
    }

    @Test
    void auditedMembershipRemovalTakesEffectOnTheNextRequestToEitherInstance() throws Exception {
        try (var first = start(null); var second = start(first.getBean(CurrentPrincipalAuthoritySecurityTest.Registry.class))) {
            var identities = first.getBean(PrincipalIdentityService.class);
            var reader = identities.oidc(ISSUER, SUBJECT);
            var owner = identities.oidc(ISSUER, "catalogue-owner");
            seedRepositories(first.getBean(SessionFactory.class), reader);
            var jdbc = new JdbcTemplate(first.getBean(CurrentPrincipalAuthoritySecurityTest.Registry.class));
            var admin = MockMvcBuilders.webAppContextSetup(first).apply(springSecurity()).build();
            var browser = MockMvcBuilders.webAppContextSetup(first).apply(springSecurity())
                    .defaultRequest(get("/").session(retainedSession(first, reader, Map.of("claim-only", "READER")))).build();
            var bearer = MockMvcBuilders.webAppContextSetup(second).apply(springSecurity())
                    .defaultRequest(get("/").header("Authorization", "Bearer " + signedAccessToken(SUBJECT, Map.of("claim-only", "READER")))).build();
            for (var client : List.of(browser, bearer)) {
                client.perform(get(REPOSITORIES)).andExpect(status().isOk())
                        .andExpect(jsonPath("$[*].repositoryId", containsInAnyOrder("claim-only", "stored")));
                client.perform(get(REPOSITORIES + "/stored")).andExpect(status().isOk());
            }

            admin.perform(delete(REPOSITORIES + "/stored/members/" + reader.scopeKey())
                    .param("expectedVersion", "0").header("X-Audit-Rationale", "Reviewed access ended")
                    .header("Authorization", "Bearer " + signedAccessToken("catalogue-owner", Map.of("stored", "OWNER"))))
                    .andExpect(status().isNoContent());
            assertThat(jdbc.queryForObject("select count(*) from principal_access_audit where actor_principal=? and decision_kind='REPOSITORY_MEMBERSHIP'",
                    Integer.class, owner.id().value().toString())).isOne();
            for (var client : List.of(browser, bearer)) {
                client.perform(get(REPOSITORIES)).andExpect(status().isOk())
                        .andExpect(jsonPath("$[*].repositoryId", containsInAnyOrder("claim-only")));
                client.perform(get(REPOSITORIES + "/stored")).andExpect(status().isNotFound());
                client.perform(get(REPOSITORIES + "/claim-only")).andExpect(status().isOk());
            }
        }
    }

    private static String signedAccessToken(String subject, Map<String, String> grants) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject)
                .issueTime(new Date()).expirationTime(Date.from(EXPIRES_AT))
                .claim("realm_access", Map.of("roles", List.of("ROLE_ARCHITECT")))
                .claim("scope", "architecture:read repository:manage")
                .claim("taxonomy_repository_roles", grants).build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        token.sign(new MACSigner(KeycloakStablePrincipalHttpTest.KEY));
        return token.serialize();
    }

    private static MockHttpSession retainedSession(AnnotationConfigWebApplicationContext context, AppPrincipal actor,
                                                  Map<String, String> grants) {
        var roles = List.of(new SimpleGrantedAuthority("ROLE_ARCHITECT"));
        var token = new OidcIdToken("test-id-token", EXPIRES_AT.minusSeconds(60), EXPIRES_AT, Map.of(
                "iss", ISSUER, "sub", SUBJECT, "taxonomy_repository_roles", grants));
        var principal = new PrincipalOidcUser(roles, new DefaultOidcUser(roles, token), actor);
        var login = new OAuth2AuthenticationToken(principal, roles, "keycloak");
        var session = new MockHttpSession(context.getServletContext());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(login));
        return session;
    }

    private static void seedRepositories(SessionFactory database, AppPrincipal actor) {
        database.inTransaction(session -> {
            for (var repositoryId : List.of("claim-only", "stored")) {
                var repository = new SystemRepository();
                repository.setRepositoryId(repositoryId);
                repository.setDisplayName(repositoryId);
                repository.setOwnerId("repository-owner");
                repository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
                repository.setCreatedAt(EXPIRES_AT.minusSeconds(60));
                session.persist(repository);
            }
            var stored = new RepositoryMembership();
            stored.setRepositoryId("stored");
            stored.setUsername(actor.scopeKey());
            stored.setHolderPrincipalId(actor.id().value().toString());
            stored.setRole(RepositoryRole.READER);
            stored.setCreatedBy("repository-owner");
            stored.setCreatedAt(EXPIRES_AT.minusSeconds(60));
            session.persist(stored);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class CatalogueConfiguration {
        @Bean ClaimClock claimClock() { return new ClaimClock(); }

        @Bean SessionFactory catalogueDatabase(CurrentPrincipalAuthoritySecurityTest.Registry registry,
                                                org.springframework.core.env.Environment properties) {
            var config = new org.hibernate.cfg.Configuration().addAnnotatedClass(SystemRepository.class)
                    .addAnnotatedClass(RepositoryMembership.class).setProperty("hibernate.hbm2ddl.auto", properties.getRequiredProperty("test.schema"))
                    .setProperty("hibernate.search.enabled", "false");
            config.getProperties().put("hibernate.connection.datasource", registry);
            return config.buildSessionFactory();
        }

        @Bean JpaTransactionManager transactionManager(SessionFactory database, CurrentPrincipalAuthoritySecurityTest.Registry registry) {
            var manager = new JpaTransactionManager(database);
            manager.setDataSource(registry);
            return manager;
        }

        @Bean WorkspaceActorAccess workspaceActors() { return WorkspaceActorAccess.unavailable(); }

        @Bean RepositoryMembershipService memberships(SessionFactory database, ClaimClock clock,
                PrincipalIdentityService identities, KeycloakPrincipalMode mode, CurrentPrincipalAuthoritySecurityTest.Registry registry) {
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(database);
            var repositories = new JpaRepositoryFactory(entityManager);
            var grants = new RepositoryGrantIdentity(identities, Optional.of(mode), new WorkspaceFetchAuthorityAdapter(identities), new JdbcTemplate(registry));
            return new RepositoryMembershipService(repositories.getRepository(RepositoryMembershipRepository.class),
                    Optional.of(new RepositoryClaimGrantProvider(clock)), grants);
        }

        @Bean ArchitectureRepositoryController catalogue(SessionFactory database, RepositoryMembershipService memberships) {
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(database);
            var repository = new JpaRepositoryFactory(entityManager).getRepository(SystemRepositoryRepository.class);
            var catalogue = new SystemRepositoryService(repository, entityManager);
            return new ArchitectureRepositoryController(catalogue, null, null, memberships, new WorkspaceResolver(null));
        }
    }

    static final class ClaimClock extends Clock {
        private Instant now = EXPIRES_AT.minusNanos(1);

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}

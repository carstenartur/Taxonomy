package com.taxonomy.security.config;

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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RepositoryClaimGrantHttpTest {
    private static final Instant EXPIRES_AT = Instant.parse("2026-11-01T00:00:00Z");
    private static final String ISSUER = "https://idp.test";
    private static final String SUBJECT = "architect";
    private static final String REPOSITORIES = "/api/repositories";

    @Test
    void retainedOidcSessionLosesClaimAccessAtExpiryAndKeepsItsStoredRole() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().setActiveProfiles("keycloak");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "taxonomy.keycloak.principal-mode", "STABLE", "taxonomy.features.multi-repository-api.enabled", "true")));
            context.register(CurrentPrincipalAuthoritySecurityTest.RegistryConfiguration.class,
                    CurrentPrincipalAuthoritySecurityTest.OidcChain.class, CatalogueConfiguration.class);
            context.refresh();

            var actor = context.getBean(PrincipalIdentityService.class).oidc(ISSUER, SUBJECT);
            seedRepositories(context.getBean(SessionFactory.class), actor);
            var session = retainedSession(context, actor);
            var http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

            http.perform(get(REPOSITORIES).session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].repositoryId", containsInAnyOrder("claim-only", "stored")));
            http.perform(get(REPOSITORIES + "/claim-only").session(session)).andExpect(status().isOk());
            http.perform(get(REPOSITORIES + "/stored/members").session(session)).andExpect(status().isOk());

            context.getBean(ClaimClock.class).now = EXPIRES_AT;
            http.perform(get(REPOSITORIES).session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].repositoryId", containsInAnyOrder("stored")));
            http.perform(get(REPOSITORIES + "/claim-only").session(session)).andExpect(status().isNotFound());
            http.perform(get(REPOSITORIES + "/stored").session(session)).andExpect(status().isOk());
            http.perform(get(REPOSITORIES + "/stored/members").session(session)).andExpect(status().isForbidden());
            http.perform(get("/api/account/me").session(session)).andExpect(status().isOk());
        }
    }

    private static MockHttpSession retainedSession(AnnotationConfigWebApplicationContext context, AppPrincipal actor) {
        var roles = List.of(new SimpleGrantedAuthority("ROLE_ARCHITECT"));
        var token = new OidcIdToken("test-id-token", EXPIRES_AT.minusSeconds(60), EXPIRES_AT, Map.of(
                "iss", ISSUER, "sub", SUBJECT, "taxonomy_repository_roles", Map.of("claim-only", "READER", "stored", "OWNER")));
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
    static class CatalogueConfiguration {
        @Bean ClaimClock claimClock() { return new ClaimClock(); }

        @Bean SessionFactory catalogueDatabase(CurrentPrincipalAuthoritySecurityTest.Registry registry) {
            var config = new org.hibernate.cfg.Configuration().addAnnotatedClass(SystemRepository.class)
                    .addAnnotatedClass(RepositoryMembership.class).setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .setProperty("hibernate.search.enabled", "false");
            config.getProperties().put("hibernate.connection.datasource", registry);
            return config.buildSessionFactory();
        }

        @Bean ArchitectureRepositoryController catalogue(SessionFactory database, ClaimClock clock,
                PrincipalIdentityService identities, KeycloakPrincipalMode mode, CurrentPrincipalAuthoritySecurityTest.Registry registry) {
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(database);
            var repositories = new JpaRepositoryFactory(entityManager);
            var catalogue = new SystemRepositoryService(repositories.getRepository(SystemRepositoryRepository.class), entityManager);
            var grants = new RepositoryGrantIdentity(identities, Optional.of(mode), new WorkspaceFetchAuthorityAdapter(identities), new JdbcTemplate(registry));
            var memberships = new RepositoryMembershipService(repositories.getRepository(RepositoryMembershipRepository.class),
                    Optional.of(new RepositoryClaimGrantProvider(clock)), grants);
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

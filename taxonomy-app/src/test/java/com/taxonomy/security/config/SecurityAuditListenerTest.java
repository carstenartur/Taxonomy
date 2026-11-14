package com.taxonomy.security.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.keycloak.PrincipalJwtAuthenticationToken;
import com.taxonomy.security.keycloak.PrincipalOidcUser;
import com.taxonomy.security.model.AppPrincipal;
import com.taxonomy.security.model.PrincipalUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityAuditListenerTest {
    private final SecurityAuditListener listener = new SecurityAuditListener();
    private final Logger logger = (Logger) LoggerFactory.getLogger(SecurityAuditListener.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    @BeforeEach void captureAudit() {
        events.start();
        logger.addAppender(events);
    }

    @AfterEach void releaseAudit() {
        logger.detachAppender(events);
        events.stop();
    }

    @Test void failedBearerRetainsAnAuditEventWithoutTreatingTheCredentialAsAnIdentity() {
        String bearer = "synthetic-unverified-bearer-credential";
        var authentication = new BearerTokenAuthenticationToken(bearer);
        listener.onFailure(new AuthenticationFailureBadCredentialsEvent(authentication,
                new BadCredentialsException("synthetic-exception-credential")));

        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getFormattedMessage()).startsWith("LOGIN_FAILED ").contains("ip=unknown");
        assertNoSecret(bearer);
        assertNoSecret("synthetic-exception-credential");
        assertThat(events.list.getFirst().getFormattedMessage()).doesNotContain("principalId=");
    }

    @Test void failedLocalLoginRetainsItsNameWithoutItsPasswordOrException() {
        listener.onFailure(new AuthenticationFailureBadCredentialsEvent(
                UsernamePasswordAuthenticationToken.unauthenticated("local-operator", "synthetic-local-password"),
                new BadCredentialsException("synthetic-exception-credential")));

        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getFormattedMessage()).contains("LOGIN_FAILED user=local-operator");
        assertNoSecret("synthetic-local-password");
        assertNoSecret("synthetic-exception-credential");
        assertThat(events.list.getFirst().getFormattedMessage()).doesNotContain("principalId=");
    }

    @Test void successfulLocalAuthenticationRetainsItsVerifiedNameWithoutCredentials() {
        listener.onSuccess(new AuthenticationSuccessEvent(UsernamePasswordAuthenticationToken.authenticated(
                "local-operator", "synthetic-local-password", AuthorityUtils.createAuthorityList("ROLE_USER"))));

        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getFormattedMessage()).isEqualTo("LOGIN_SUCCESS user=local-operator ip=unknown");
        assertNoSecret("synthetic-local-password");
    }

    @Test void successfulJwtAuthenticationRetainsItsVerifiedSubjectWithoutTheToken() {
        String bearer = "synthetic-verified-bearer-credential";
        var jwt = Jwt.withTokenValue(bearer).header("alg", "RS256").subject("verified-subject")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        listener.onSuccess(new AuthenticationSuccessEvent(new JwtAuthenticationToken(jwt)));

        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getFormattedMessage()).isEqualTo("LOGIN_SUCCESS user=verified-subject ip=unknown");
        assertNoSecret(bearer);
    }

    @Test void sameLoginLabelRetainsTheActualStableActorOfEachSuccessfulLocalLogin() {
        var first = PrincipalId.create();
        var second = PrincipalId.create();
        var roles = AuthorityUtils.createAuthorityList("ROLE_USER");
        for (var id : List.of(first, second)) {
            var local = new PrincipalUserDetails(id, "operator", "synthetic-stored-password", true, roles);
            listener.onSuccess(new AuthenticationSuccessEvent(
                    UsernamePasswordAuthenticationToken.authenticated(local, "synthetic-local-password", roles)));
        }

        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "LOGIN_SUCCESS user=operator ip=unknown principalId=" + first.value(),
                "LOGIN_SUCCESS user=operator ip=unknown principalId=" + second.value());
        assertNoSecret("synthetic-local-password");
        assertNoSecret("synthetic-stored-password");
    }

    @Test void browserAndBearerSuccessRetainTheSameStablePrincipalWithoutLoggingEitherCredential() {
        var principal = new AppPrincipal(PrincipalId.create(), "legacy-owner-scope", true);
        var roles = AuthorityUtils.createAuthorityList("ROLE_USER");
        var now = Instant.now();
        var jwt = Jwt.withTokenValue("synthetic-jwt-token").header("alg", "RS256").subject("provider-subject")
                .issuer("https://issuer.example").issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        var oidc = new OidcIdToken("synthetic-browser-token", now, now.plusSeconds(300), jwt.getClaims());
        var browser = new PrincipalOidcUser(roles, new DefaultOidcUser(roles, oidc), principal);

        listener.onSuccess(new AuthenticationSuccessEvent(new OAuth2AuthenticationToken(browser, roles, "keycloak")));
        listener.onSuccess(new AuthenticationSuccessEvent(new PrincipalJwtAuthenticationToken(jwt, roles, principal)));

        String expected = "LOGIN_SUCCESS user=legacy-owner-scope ip=unknown principalId=" + principal.id().value();
        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(expected, expected);
        assertNoSecret("synthetic-jwt-token");
        assertNoSecret("synthetic-browser-token");
    }

    @Test void unauthenticatedIdentityDoesNotSupplyAStableActor() {
        var local = new PrincipalUserDetails(PrincipalId.create(), "operator", "synthetic-stored-password",
                true, AuthorityUtils.createAuthorityList("ROLE_USER"));
        var pending = UsernamePasswordAuthenticationToken.unauthenticated(local, "synthetic-local-password");

        listener.onSuccess(new AuthenticationSuccessEvent(pending));

        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("LOGIN_SUCCESS user=operator ip=unknown");
        assertNoSecret("synthetic-local-password");
        assertNoSecret("synthetic-stored-password");
    }

    private void assertNoSecret(String secret) {
        assertThat(events.list.stream().anyMatch(event -> event.getFormattedMessage().contains(secret)
                || Arrays.toString(event.getArgumentArray()).contains(secret)
                || event.getThrowableProxy() != null && ThrowableProxyUtil.asString(event.getThrowableProxy()).contains(secret)))
                .as("Audit messages, structured arguments and throwables must exclude credentials").isFalse();
    }
}

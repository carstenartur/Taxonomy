package com.taxonomy.security.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Arrays;

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
    }

    @Test void failedLocalLoginRetainsItsNameWithoutItsPasswordOrException() {
        listener.onFailure(new AuthenticationFailureBadCredentialsEvent(
                UsernamePasswordAuthenticationToken.unauthenticated("local-operator", "synthetic-local-password"),
                new BadCredentialsException("synthetic-exception-credential")));

        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getFormattedMessage()).contains("LOGIN_FAILED user=local-operator");
        assertNoSecret("synthetic-local-password");
        assertNoSecret("synthetic-exception-credential");
    }

    @Test void successfulLocalAuthenticationRetainsItsVerifiedNameWithoutCredentials() {
        listener.onSuccess(new AuthenticationSuccessEvent(UsernamePasswordAuthenticationToken.authenticated(
                "local-operator", "synthetic-local-password", AuthorityUtils.createAuthorityList("ROLE_USER"))));

        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getFormattedMessage()).contains("LOGIN_SUCCESS user=local-operator");
        assertNoSecret("synthetic-local-password");
    }

    @Test void successfulJwtAuthenticationRetainsItsVerifiedSubjectWithoutTheToken() {
        String bearer = "synthetic-verified-bearer-credential";
        var jwt = Jwt.withTokenValue(bearer).header("alg", "RS256").subject("verified-subject")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        listener.onSuccess(new AuthenticationSuccessEvent(new JwtAuthenticationToken(jwt)));

        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getFormattedMessage()).contains("LOGIN_SUCCESS user=verified-subject");
        assertNoSecret(bearer);
    }

    private void assertNoSecret(String secret) {
        assertThat(events.list.stream().anyMatch(event -> event.getFormattedMessage().contains(secret)
                || Arrays.toString(event.getArgumentArray()).contains(secret)
                || event.getThrowableProxy() != null && ThrowableProxyUtil.asString(event.getThrowableProxy()).contains(secret)))
                .as("Audit messages, structured arguments and throwables must exclude credentials").isFalse();
    }
}

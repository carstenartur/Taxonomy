package com.taxonomy.security.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialSafeBearerTokenAuthenticationConverterTest {
    private static final String CREDENTIAL = "synthetic.jwt.credential";

    @Test void defaultParserRetainsProviderCredentialAndRequestDetailsWithoutAnUnverifiedPrincipal() {
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            var request = request("Bearer " + CREDENTIAL);
            request.setRemoteAddr("192.0.2.10");
            var converted = (BearerTokenAuthenticationToken) CredentialSafeBearerTokenAuthenticationConverter
                    .forContext(context).convert(request);

            assertThat(converted.isAuthenticated()).isFalse();
            assertThat(converted.getPrincipal()).isEqualTo("[unverified-bearer]");
            assertThat(converted.getName()).isEqualTo("[unverified-bearer]");
            assertThat(converted.getToken()).isEqualTo(CREDENTIAL);
            assertThat(converted.getCredentials()).isEqualTo(CREDENTIAL);
            assertThat(converted.toString()).doesNotContain(CREDENTIAL);
            assertThat(converted.getDetails()).isInstanceOf(WebAuthenticationDetails.class);
            assertThat(((WebAuthenticationDetails) converted.getDetails()).getRemoteAddress()).isEqualTo("192.0.2.10");
        }
    }

    @Test void absentAndUnrelatedAuthorizationRemainAbsentAndQueryTokensStayDisabled() {
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            var converter = CredentialSafeBearerTokenAuthenticationConverter.forContext(context);
            assertThat(converter.convert(request(null))).isNull();
            assertThat(converter.convert(request("Basic dGVzdDp0ZXN0"))).isNull();
            var query = request(null);
            query.addParameter("access_token", CREDENTIAL);
            assertThat(converter.convert(query)).isNull();
        }
    }

    @Test void malformedBearerHeadersKeepTheFrameworkRejection() {
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            var converter = CredentialSafeBearerTokenAuthenticationConverter.forContext(context);
            assertThatThrownBy(() -> converter.convert(request("Bearer not a token")))
                    .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                            failure -> assertThat(failure.getError().getErrorCode()).isEqualTo("invalid_token"));
        }
    }

    @Test void configuredBearerResolverStillSuppliesTheExactProviderToken() {
        try (var context = new GenericApplicationContext()) {
            context.registerBean(BearerTokenResolver.class, () -> request -> request.getHeader("X-Test-Credential"));
            context.refresh();
            var request = request(null);
            request.addHeader("X-Test-Credential", CREDENTIAL);
            var converted = (BearerTokenAuthenticationToken) CredentialSafeBearerTokenAuthenticationConverter
                    .forContext(context).convert(request);
            assertThat(converted.getToken()).isEqualTo(CREDENTIAL);
            assertThat(converted.getName()).isEqualTo("[unverified-bearer]");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void configuredConverterKeepsPrecedenceOverResolverAndItsRequestDetails(boolean authenticated) {
        try (var context = new GenericApplicationContext()) {
            var resolverCalls = new AtomicInteger();
            Object details = new Object();
            context.registerBean(BearerTokenResolver.class, () -> request -> {
                resolverCalls.incrementAndGet();
                return "wrong-resolver-token";
            });
            context.registerBean(AuthenticationConverter.class, () -> request -> {
                var token = new BearerTokenAuthenticationToken(CREDENTIAL);
                token.setDetails(details);
                token.setAuthenticated(authenticated);
                return token;
            });
            context.refresh();
            var converted = (BearerTokenAuthenticationToken) CredentialSafeBearerTokenAuthenticationConverter
                    .forContext(context).convert(request(null));
            assertThat(converted.getToken()).isEqualTo(CREDENTIAL);
            assertThat(converted.getDetails()).isSameAs(details);
            assertThat(converted.isAuthenticated()).isEqualTo(authenticated);
            assertThat(converted.getName()).isEqualTo("[unverified-bearer]");
            assertThat(resolverCalls).hasValue(0);
        }
    }

    @Test void configuredNonBearerAuthenticationIsNotReinterpreted() {
        try (var context = new GenericApplicationContext()) {
            var authentication = UsernamePasswordAuthenticationToken.unauthenticated("other-protocol", "other-credential");
            context.registerBean(AuthenticationConverter.class, () -> request -> authentication);
            context.refresh();
            assertThat(CredentialSafeBearerTokenAuthenticationConverter.forContext(context).convert(request(null)))
                    .isSameAs(authentication);
        }
    }

    @Test void configuredConverterExceptionsArePropagatedUnchanged() {
        try (var context = new GenericApplicationContext()) {
            var failure = new OAuth2AuthenticationException(new OAuth2Error("invalid_request"));
            context.registerBean(AuthenticationConverter.class, () -> request -> {throw failure;});
            context.refresh();
            var converter = CredentialSafeBearerTokenAuthenticationConverter.forContext(context);
            assertThatThrownBy(() -> converter.convert(request(null))).isSameAs(failure);
        }
    }

    @Test void configuredTokenBudgetIsAppliedBeforeTheProviderIncludingForCustomResolvers() {
        try (var context = new GenericApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "test", java.util.Map.of("taxonomy.security.bearer.max-token-length", "8")));
            context.registerBean(BearerTokenResolver.class, () -> input -> input.getHeader("X-Fixture-Token"));
            context.refresh();
            var converter = CredentialSafeBearerTokenAuthenticationConverter.forContext(context);
            var accepted = request(null);
            accepted.addHeader("X-Fixture-Token", "abcdefgh");
            assertThat(((BearerTokenAuthenticationToken) converter.convert(accepted)).getToken()).isEqualTo("abcdefgh");
            var oversized = request(null);
            oversized.addHeader("X-Fixture-Token", "abcdefghi");
            assertThatThrownBy(() -> converter.convert(oversized))
                    .isInstanceOf(OAuth2AuthenticationException.class).hasMessageNotContaining("abcdefghi");
        }
    }

    @Test void aDeclaredBearerCredentialRequiresAnAuthenticationResultFromTheConfiguredResolver() {
        try (var context = new GenericApplicationContext()) {
            context.registerBean(BearerTokenResolver.class, () -> input -> null);
            context.refresh();
            var converter = CredentialSafeBearerTokenAuthenticationConverter.forContext(context);
            assertThat(converter.convert(request(null))).isNull();
            assertThatThrownBy(() -> converter.convert(request("Bearer " + CREDENTIAL)))
                    .isInstanceOf(OAuth2AuthenticationException.class).hasMessageNotContaining(CREDENTIAL);
        }
    }

    @Test void oversizedStandardCredentialIsRejectedBeforeTheConfiguredConverterRuns() {
        try (var context = new GenericApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "test", java.util.Map.of("taxonomy.security.bearer.max-token-length", "8")));
            var calls = new AtomicInteger();
            context.registerBean(AuthenticationConverter.class, () -> input -> {
                calls.incrementAndGet();
                return new BearerTokenAuthenticationToken("abcdefgh");
            });
            context.refresh();
            var converter = CredentialSafeBearerTokenAuthenticationConverter.forContext(context);
            assertThat(converter.convert(request("Bearer abcdefgh"))).isInstanceOf(BearerTokenAuthenticationToken.class);
            assertThatThrownBy(() -> converter.convert(request("Bearer abcdefghi")))
                    .isInstanceOf(OAuth2AuthenticationException.class).hasMessageNotContaining("abcdefghi");
            assertThat(calls).hasValue(1);
        }
    }

    private static MockHttpServletRequest request(String authorization) {
        var request = new MockHttpServletRequest("GET", "/api/account/me");
        if (authorization != null) request.addHeader("Authorization", authorization);
        return request;
    }
}

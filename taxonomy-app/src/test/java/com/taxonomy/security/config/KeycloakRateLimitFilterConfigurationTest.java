package com.taxonomy.security.config;

import com.taxonomy.security.keycloak.KeycloakAuthenticationEntryPoint;
import com.taxonomy.security.keycloak.KeycloakJwtAuthConverter;
import com.taxonomy.security.keycloak.KeycloakLogoutHandler;
import com.taxonomy.security.keycloak.KeycloakOidcUserService;
import com.taxonomy.shared.config.RateLimitFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.configurers.LogoutConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies that the Keycloak chain applies the same post-authorization quota boundary. */
@ExtendWith(MockitoExtension.class)
class KeycloakRateLimitFilterConfigurationTest {

    @Mock
    private AuthorizationRulesConfigurer authorizationRules;
    @Mock
    private KeycloakJwtAuthConverter jwtAuthConverter;
    @Mock
    private KeycloakOidcUserService oidcUserService;
    @Mock
    private KeycloakLogoutHandler logoutHandler;
    @Mock
    private KeycloakAuthenticationEntryPoint authenticationEntryPoint;
    @Mock
    private RateLimitFilter rateLimitFilter;

    @Test
    void keycloakChainPlacesLimiterAfterAuthorizationFilter() throws Exception {
        StaticApplicationContext context = new StaticApplicationContext();
        HttpSecurity http = mock(HttpSecurity.class, Answers.RETURNS_SELF);
        DefaultSecurityFilterChain chain = mock(DefaultSecurityFilterChain.class);

        CsrfConfigurer<HttpSecurity> csrf = new CsrfConfigurer<>(context);
        HeadersConfigurer<HttpSecurity> headers = new HeadersConfigurer<>();
        OAuth2LoginConfigurer<HttpSecurity> oauth2Login = new OAuth2LoginConfigurer<>();
        OAuth2ResourceServerConfigurer<HttpSecurity> resourceServer =
                new OAuth2ResourceServerConfigurer<>(context);
        LogoutConfigurer<HttpSecurity> logout = new LogoutConfigurer<>();

        doAnswer(customize(null, http)).when(http).authorizeHttpRequests(any());
        doAnswer(customize(csrf, http)).when(http).csrf(any());
        doAnswer(customize(headers, http)).when(http).headers(any());
        doAnswer(customize(oauth2Login, http)).when(http).oauth2Login(any());
        doAnswer(customize(resourceServer, http)).when(http).oauth2ResourceServer(any());
        doAnswer(customize(logout, http)).when(http).logout(any());
        when(http.build()).thenReturn(chain);

        KeycloakSecurityConfig config = new KeycloakSecurityConfig(
                authorizationRules,
                jwtAuthConverter,
                oidcUserService,
                logoutHandler,
                authenticationEntryPoint,
                null,
                rateLimitFilter);

        assertThat(config.securityFilterChain(http)).isSameAs(chain);
        verify(authorizationRules).configure(null);
        verify(http).addFilterAfter(rateLimitFilter, AuthorizationFilter.class);
    }

    private static org.mockito.stubbing.Answer<HttpSecurity> customize(
            Object target,
            HttpSecurity http) {
        return invocation -> {
            @SuppressWarnings("unchecked")
            Customizer<Object> customizer = invocation.getArgument(0);
            customizer.customize(target);
            return http;
        };
    }
}

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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.configurers.LogoutConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProtocolClientSecurityConfigTest {

    @Mock
    private AuthorizationRulesConfigurer authorizationRules;
    @Mock
    private PasswordChangeRequiredFilter passwordChangeRequiredFilter;
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
    void formLoginTreatsOnlyExplicitlyAuthenticatedApiAndWebDavCallsAsStateless() {
        assertThat(SecurityConfig.isStatelessProtocolClient(
                request("/api/projects", "Basic dGVzdDp0ZXN0"))).isTrue();
        assertThat(SecurityConfig.isStatelessProtocolClient(
                request("/dav/templates", "Bearer token"))).isTrue();
        assertThat(SecurityConfig.isStatelessProtocolClient(
                request("/dav/templates/decision-rationale-report.dotx", "Basic dGVzdDp0ZXN0")))
                .isTrue();

        assertThat(SecurityConfig.isStatelessProtocolClient(
                request("/admin/document-templates", "Basic dGVzdDp0ZXN0"))).isFalse();
        assertThat(SecurityConfig.isStatelessProtocolClient(
                request("/dav/templates/report.dotx", null))).isFalse();
        assertThat(SecurityConfig.isStatelessProtocolClient(
                request("/dav/templates/report.dotx", "Digest value"))).isFalse();
    }

    @Test
    void keycloakChainConfiguresBearerWebDavBrowserAndRateLimitRules() throws Exception {
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
        verify(http).addFilterAfter(
                rateLimitFilter,
                SecurityContextHolderAwareRequestFilter.class);

        assertThat(invokeBoolean(
                "isStatelessBearerProtocolClient",
                request("/api/projects", "Bearer token"))).isTrue();
        assertThat(invokeBoolean(
                "isStatelessBearerProtocolClient",
                request("/dav/templates", "Bearer token"))).isTrue();
        assertThat(invokeBoolean(
                "isStatelessBearerProtocolClient",
                request("/dav/templates/report.dotx", "Bearer token"))).isTrue();
        assertThat(invokeBoolean(
                "isStatelessBearerProtocolClient",
                request("/dav/templates/report.dotx", "Basic dGVzdDp0ZXN0"))).isFalse();
        assertThat(invokeBoolean(
                "isStatelessBearerProtocolClient",
                request("/admin/document-templates", "Bearer token"))).isFalse();
        assertThat(invokeBoolean(
                "isStatelessBearerProtocolClient",
                request("/api/projects", null))).isFalse();

        assertThat(invokeBoolean(
                "isOAuth2Callback",
                request("/login/oauth2/code/keycloak", null))).isTrue();
        assertThat(invokeBoolean(
                "isOAuth2Callback",
                request("/login", null))).isFalse();

        @SuppressWarnings("unchecked")
        ObjectPostProcessor<HeaderWriterFilter> postProcessor =
                (ObjectPostProcessor<HeaderWriterFilter>) invokePrivateStatic(
                        KeycloakSecurityConfig.class,
                        "eagerHeaderWriter",
                        new Class<?>[0]);
        HeaderWriterFilter filter = mock(HeaderWriterFilter.class);
        assertThat(postProcessor.postProcess(filter)).isSameAs(filter);
        verify(filter).setShouldWriteHeadersEagerly(true);
    }

    private static MockHttpServletRequest request(String uri, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
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

    private static boolean invokeBoolean(String method, MockHttpServletRequest request)
            throws Exception {
        return (boolean) invokePrivateStatic(
                KeycloakSecurityConfig.class,
                method,
                new Class<?>[]{jakarta.servlet.http.HttpServletRequest.class},
                request);
    }

    private static Object invokePrivateStatic(
            Class<?> type,
            String name,
            Class<?>[] parameterTypes,
            Object... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }
}

package com.taxonomy.security.config;

import com.taxonomy.security.access.ApiAccessPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

/** Shared page rules and the single REST policy used by local and OIDC authentication. */
@Component
public class AuthorizationRulesConfigurer {
    @Value("${taxonomy.security.swagger-public:true}")
    private boolean swaggerPublic;

    public void configure(
            AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>
                    .AuthorizationManagerRequestMatcherRegistry auth) {
        auth.requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**", "/webjars/**",
                        "/login/oauth2/**", "/oauth2/**")
                .permitAll();
        auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
        if (swaggerPublic) {
            auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
        } else {
            auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").authenticated();
        }
        auth.requestMatchers("/api/**", "/oslc/**").access((authentication, context) -> {
            var request = context.getRequest();
            String path = request.getRequestURI().substring(request.getContextPath().length());
            var rule = ApiAccessPolicy.resolve(request.getMethod(), path);
            return new AuthorizationDecision(ApiAccessPolicy.authorize(
                    rule.access() == ApiAccessPolicy.Access.PUBLIC ? null : authentication.get(), rule));
        });
        auth.requestMatchers("/admin/**").hasRole("ADMIN");
        auth.requestMatchers(HttpMethod.GET, "/backup-jobs").authenticated();
        auth.requestMatchers("/**").authenticated();
    }
}

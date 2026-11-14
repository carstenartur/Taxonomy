package com.taxonomy.security.config;

import com.taxonomy.security.model.StablePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * Listens to Spring Security authentication events and logs them for audit purposes.
 * <p>
 * Enabled with {@code TAXONOMY_AUDIT_LOGGING=true}. Disabled by default to avoid log noise.
 */
@Component
@ConditionalOnProperty(name = "taxonomy.security.audit-logging", havingValue = "true")
public class SecurityAuditListener {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditListener.class);

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        var authentication = event.getAuthentication();
        String username = authentication.getName();
        String ip = extractIp(authentication.getDetails());
        Object actor = authentication instanceof StablePrincipal ? authentication : authentication.getPrincipal();
        if (authentication.isAuthenticated() && actor instanceof StablePrincipal stable) {
            log.info("LOGIN_SUCCESS user={} ip={} principalId={}", username, ip, stable.principalId().value());
        } else {
            log.info("LOGIN_SUCCESS user={} ip={}", username, ip);
        }
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        // Before JWT verification this token's principal/name is the raw credential.
        String username = event.getAuthentication() instanceof BearerTokenAuthenticationToken
                ? "[unverified-bearer]" : event.getAuthentication().getName();
        String ip = extractIp(event.getAuthentication().getDetails());
        log.warn("LOGIN_FAILED user={} ip={}", username, ip);
    }

    private String extractIp(Object details) {
        if (details instanceof WebAuthenticationDetails webDetails) {
            return webDetails.getRemoteAddress();
        }
        return "unknown";
    }
}

package com.taxonomy.security.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.firewall.FirewalledRequest;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import java.util.List;
import java.util.Set;

/** Allows the implemented WebDAV verbs only on the virtual template collection. */
@Configuration(proxyBeanMethods = false)
public class DocumentTemplateFirewallConfig {

    @Bean
    HttpFirewall documentTemplateHttpFirewall() {
        return new TemplateHttpFirewall();
    }

    /** Keeps all StrictHttpFirewall URL, header, parameter and response checks. */
    static final class TemplateHttpFirewall extends StrictHttpFirewall {
        private static final Set<String> DAV_METHODS = Set.of("PROPFIND", "LOCK", "UNLOCK");

        TemplateHttpFirewall() {
            setAllowedHttpMethods(List.of(
                    "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT",
                    "PROPFIND", "LOCK", "UNLOCK"));
        }

        @Override
        public FirewalledRequest getFirewalledRequest(HttpServletRequest request) {
            // Validate first; never normalize an unsafe URL into an allowed DAV path.
            FirewalledRequest checked = super.getFirewalledRequest(request);
            if (DAV_METHODS.contains(checked.getMethod())) {
                String path = checked.getRequestURI().substring(checked.getContextPath().length());
                if (!path.equals("/dav/templates") && !path.startsWith("/dav/templates/")) {
                    throw new RequestRejectedException("WebDAV method outside template collection");
                }
            }
            return checked;
        }
    }
}

package com.taxonomy.mail;

import com.taxonomy.provenance.service.DocumentParserService;
import com.taxonomy.shared.features.ConditionalOnFeature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import java.util.Locale;

@Configuration(proxyBeanMethods = false)
@ConditionalOnFeature({"analysis", "architecture", "reporting"})
@ConditionalOnProperty(prefix = "taxonomy.mail", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MailTransportProperties.class)
class MailTransportConfiguration {

    @Bean
    SmtpMailAnalysisDelivery smtpMailAnalysisDelivery(MailTransportProperties properties) {
        return new SmtpMailAnalysisDelivery(properties);
    }

    @Bean
    FilterRegistrationBean<CorsFilter> mailStatusPortalCors(MailTransportProperties properties) {
        var source = new UrlBasedCorsConfigurationSource();
        var registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.addUrlPatterns("/mail-analysis/jobs/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        var portal = properties.statusPortalUrl();
        registration.setEnabled(portal != null);
        if (portal != null) {
            var policy = new CorsConfiguration();
            String port = portal.getPort() < 0 || portal.getPort() == 443 ? "" : ":" + portal.getPort();
            policy.setAllowedOrigins(List.of("https://" + portal.getHost().toLowerCase(Locale.ROOT) + port));
            policy.setAllowedMethods(List.of("GET"));
            policy.setAllowedHeaders(List.of("Accept", "Cache-Control", "Pragma"));
            source.registerCorsConfiguration("/mail-analysis/jobs/{token:"
                    + MailAnalysisStatusController.TOKEN.pattern() + "}/status", policy);
        }
        return registration;
    }

    @Bean
    @ConditionalOnProperty(prefix = "taxonomy.mail.imap", name = "poll-enabled", havingValue = "true", matchIfMissing = true)
    ImapMailAnalysisPoller imapMailAnalysisPoller(MailTransportProperties properties,
                                                  DocumentParserService documents,
                                                  MailAnalysisQueue queue,
                                                  SmtpMailAnalysisDelivery delivery) {
        return new ImapMailAnalysisPoller(properties.imap(), new MailRequestParser(documents, properties), queue, delivery);
    }
}

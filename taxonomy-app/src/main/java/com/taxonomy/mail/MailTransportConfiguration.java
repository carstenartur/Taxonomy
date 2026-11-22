package com.taxonomy.mail;

import com.taxonomy.provenance.service.DocumentParserService;
import com.taxonomy.shared.features.ConditionalOnFeature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    @ConditionalOnProperty(prefix = "taxonomy.mail.imap", name = "poll-enabled", havingValue = "true", matchIfMissing = true)
    ImapMailAnalysisPoller imapMailAnalysisPoller(MailTransportProperties properties,
                                                  DocumentParserService documents,
                                                  MailAnalysisQueue queue,
                                                  SmtpMailAnalysisDelivery delivery) {
        return new ImapMailAnalysisPoller(properties.imap(), new MailRequestParser(documents, properties), queue, delivery);
    }
}

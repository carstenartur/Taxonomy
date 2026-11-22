package com.taxonomy.mail;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("taxonomy.mail")
record MailTransportProperties(
        URI publicBaseUrl,
        List<String> allowedSenders,
        @DefaultValue("10485760") int maxMessageBytes,
        @DefaultValue("8") int maxAttachments,
        @DefaultValue("5000") int maxRequirementCharacters,
        @DefaultValue Imap imap,
        @DefaultValue Smtp smtp) {

    MailTransportProperties {
        if (publicBaseUrl == null || publicBaseUrl.getHost() == null || publicBaseUrl.getUserInfo() != null
                || publicBaseUrl.getQuery() != null || publicBaseUrl.getFragment() != null
                || !("https".equalsIgnoreCase(publicBaseUrl.getScheme())
                    || "http".equalsIgnoreCase(publicBaseUrl.getScheme())
                        && List.of("localhost", "127.0.0.1", "[::1]").contains(publicBaseUrl.getHost()))) {
            throw new IllegalArgumentException("Taxonomy mail requires a public application base URL using HTTPS");
        }
        allowedSenders = allowedSenders == null ? List.of() : List.copyOf(allowedSenders);
        if (allowedSenders.isEmpty()) {
            throw new IllegalArgumentException("Taxonomy mail requires an explicit sender allowlist");
        }
        if (maxMessageBytes < 1024 || maxMessageBytes > 32 * 1024 * 1024
                || maxAttachments < 1 || maxAttachments > 32
                || maxRequirementCharacters < 100 || maxRequirementCharacters > 100_000) {
            throw new IllegalArgumentException("Taxonomy mail input limits are outside the supported range");
        }
    }

    record Imap(String host, @DefaultValue("993") int port, String username, String password,
                @DefaultValue("INBOX") String folder, @DefaultValue("PT30S") Duration pollInterval,
                @DefaultValue("20") int batchSize, @DefaultValue("true") boolean pollEnabled) {
        Imap {
            if (pollEnabled && (missing(host) || missing(username) || missing(password) || missing(folder)
                    || port < 1 || port > 65535 || pollInterval.compareTo(Duration.ofSeconds(1)) < 0
                    || batchSize < 1 || batchSize > 100)) {
                throw new IllegalArgumentException("Taxonomy mail IMAP configuration is incomplete or invalid");
            }
        }

        @Override
        public String toString() {
            return "IMAP configuration";
        }
    }

    record Smtp(String host, @DefaultValue("587") int port, String username, String password, String from,
                @DefaultValue("false") boolean implicitTls) {
        Smtp {
            if (missing(host) || missing(username) || missing(password) || missing(from) || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Taxonomy mail SMTP configuration is incomplete or invalid");
            }
        }

        @Override
        public String toString() {
            return "SMTP configuration";
        }
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }
}

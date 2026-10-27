package com.taxonomy.portfolio.workbench;

import com.taxonomy.visio.VisioProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Trusted application identity, authenticated actor and one timestamp per Visio download. */
@Service
public class VisioExportContextService {
    private static final String INVALID_BASE = "taxonomy.export.public-base-url must be an absolute HTTP(S) URL with an unambiguous deployment path and no credentials, query or fragment";
    private final BuildProperties buildProperties;
    private final String fallbackVersion;
    private final String publicBaseUrl;
    private final Clock clock;

    @Autowired
    public VisioExportContextService(ObjectProvider<BuildProperties> buildProperties,
            @Value("${app.display-version:${info.app.version:unknown}}") String fallbackVersion,
            @Value("${taxonomy.export.public-base-url:}") String publicBaseUrl) {
        this(buildProperties.getIfAvailable(), fallbackVersion, publicBaseUrl, Clock.systemUTC());
    }

    /** Explicit clock supports reproducible serialization of the same export context. */
    public VisioExportContextService(BuildProperties buildProperties, String fallbackVersion, String publicBaseUrl, Clock clock) {
        this.buildProperties = buildProperties;
        this.fallbackVersion = normalized(fallbackVersion, "unknown");
        this.publicBaseUrl = validateBase(publicBaseUrl);
        this.clock = Objects.requireNonNull(clock, "export clock");
    }

    public ExportContext create(Long projectId, Long requirementId, String username) {
        if (projectId == null || projectId <= 0 || requirementId == null || requirementId <= 0) {
            throw new IllegalArgumentException("Saved requirement links require positive project and requirement IDs");
        }
        String url = publicBaseUrl == null ? null
                : publicBaseUrl + "/projects/" + projectId + "/requirements/" + requirementId;
        return context(username, url, url == null
                ? "Unavailable: public base URL is not configured."
                : "Sign in and select the source workspace and branch to edit this requirement.");
    }

    /** Working views have no persisted requirement identity, even when a public base is configured. */
    public ExportContext create(String username) {
        return context(username, null, "Unavailable: this working view has no saved requirement editing target.");
    }

    private ExportContext context(String username, String requirementUrl, String status) {
        String actor = username == null || username.isBlank() ? null : username.strip();
        String version = buildProperties == null ? fallbackVersion
                : normalized(buildProperties.getVersion(), fallbackVersion);
        return new ExportContext("Taxonomy Architecture Analyzer", version, actor,
                clock.instant(), requirementUrl, status);
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String validateBase(String value) {
        if (value == null || value.isEmpty()) return null;
        // No normalization of unsafe input: operator mistakes must not become a different origin/path.
        if (value.codePoints().anyMatch(Character::isISOControl) || !value.equals(value.strip()) || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(INVALID_BASE);
        }
        try {
            URI uri = new URI(value);
            String path = uri.getRawPath();
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() > 65535 || path == null || path.contains("//") || path.contains("%")) {
                throw new IllegalArgumentException(INVALID_BASE);
            }
            if (!path.isEmpty()) {
                if (!path.startsWith("/") || !path.matches("/[A-Za-z0-9._~/-]*")) throw new IllegalArgumentException(INVALID_BASE);
                for (String segment : path.split("/")) {
                    if (segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException(INVALID_BASE);
                }
            }
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        } catch (URISyntaxException exception) {
            // Do not include the configured value or the parser cause: they may contain credentials.
            throw new IllegalArgumentException(INVALID_BASE);
        }
    }

    public record ExportContext(String generatorName, String generatorVersion, String exportedBy,
            Instant exportedAt, String requirementUrl, String requirementUrlStatus) {
        public Map<String, VisioProperty> documentProperties() {
            Map<String, VisioProperty> properties = new LinkedHashMap<>();
            put(properties, "generatorName", generatorName);
            put(properties, "generatorVersion", generatorVersion);
            put(properties, "exportedBy", exportedBy);
            put(properties, "exportedAt", exportedAt);
            put(properties, "requirementUrl", requirementUrl);
            put(properties, "requirementUrlStatus", requirementUrlStatus);
            return Map.copyOf(properties);
        }

        private static void put(Map<String, VisioProperty> properties, String field, Object value) {
            if (value != null) properties.put("taxonomy." + field, VisioProperty.text(value));
        }
    }
}

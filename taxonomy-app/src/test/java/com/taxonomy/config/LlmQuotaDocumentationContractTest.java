package com.taxonomy.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps the incoming LLM quota contract consistent across code-facing documentation and UI text. */
class LlmQuotaDocumentationContractTest {

    @Test
    void stableAuthenticatedIdentityContractReplacesObsoletePerIpWording()
            throws Exception {
        Path root = repositoryRoot();

        String englishPreferences = read(root, "docs/en/PREFERENCES.md");
        String germanPreferences = read(root, "docs/de/PREFERENCES.md");
        String englishConfiguration = read(root, "docs/en/CONFIGURATION_REFERENCE.md");
        String germanConfiguration = read(root, "docs/de/CONFIGURATION_REFERENCE.md");
        String englishArchitecture = read(root, "docs/en/ARCHITECTURE.md");
        String germanArchitecture = read(root, "docs/de/ARCHITECTURE.md");
        String englishProviders = read(root, "docs/en/AI_PROVIDERS.md");
        String germanProviders = read(root, "docs/de/AI_PROVIDERS.md");
        String englishOperations = read(root, "docs/en/OPERATIONS_GUIDE.md");
        String germanOperations = read(root, "docs/de/OPERATIONS_GUIDE.md");
        String template = read(root,
                "taxonomy-app/src/main/resources/templates/index.html");
        String englishMessages = read(root,
                "taxonomy-app/src/main/resources/i18n/messages.properties");
        String germanMessages = read(root,
                "taxonomy-app/src/main/resources/i18n/messages_de.properties");
        String properties = read(root,
                "taxonomy-app/src/main/resources/application.properties");

        assertThat(englishPreferences)
                .contains("stable authenticated identity")
                .contains("issuer/subject (`iss`/`sub`)")
                .contains("per application instance")
                .contains("`Retry-After`")
                .doesNotContain("analysis endpoints (per IP)");
        assertThat(germanPreferences)
                .contains("stabiler authentifizierter Identität")
                .contains("Issuer/Subject-Identität (`iss`/`sub`)")
                .contains("je laufender Anwendungsinstanz")
                .contains("`Retry-After`")
                .doesNotContain("Analyse-Endpunkte (pro IP)");

        assertThat(englishConfiguration)
                .contains("negative values fail closed to `1`")
                .contains("immutable `iss`/`sub` pair")
                .contains("scoped to one application instance")
                .doesNotContain("Per-client limit for LLM-backed API requests");
        assertThat(germanConfiguration)
                .contains("negative Werte wirken fehlersicher als `1`")
                .contains("unveränderliche Paar `iss`/`sub`")
                .contains("gelten je Anwendungsinstanz")
                .doesNotContain("Clientlimit LLM-gestützter API-Aufrufe");

        assertThat(englishArchitecture)
                .contains("after `AuthorizationFilter`")
                .contains("10 admitted requests per stable authenticated identity")
                .contains("multi-replica deployment multiplies the aggregate allowance")
                .doesNotContain("per-IP rate limiter")
                .doesNotContain("requests per IP per minute");
        assertThat(germanArchitecture)
                .contains("nach dem `AuthorizationFilter`")
                .contains("10 zugelassene Aufrufe je stabiler authentifizierter Identität")
                .contains("Mehrere Replikate vervielfachen das Gesamtkontingent")
                .doesNotContain("Ratenbegrenzer pro IP")
                .doesNotContain("Anfragen pro IP pro Minute");

        assertThat(englishProviders)
                .contains("immutable `iss`/`sub`")
                .contains("per application instance")
                .doesNotContain("requests per client");
        assertThat(germanProviders)
                .contains("Keycloak-Browser- und Bearer-Zugriffe teilen `iss`/`sub`")
                .contains("je Anwendungsinstanz")
                .doesNotContain("Aufrufe pro Client");
        assertThat(englishOperations)
                .contains("per authenticated identity and per application instance")
                .contains("distributed ingress quota");
        assertThat(germanOperations)
                .contains("Je authentifizierter Identität und Anwendungsinstanz")
                .contains("verteilter Ingress-Begrenzer");

        assertThat(template)
                .contains("LLM Quota per Authenticated Identity (requests/minute)")
                .doesNotContain("Server Rate Limit (requests/IP/minute)");
        assertThat(englishMessages)
                .contains("preferences.llm.ratelimit=LLM Quota per Authenticated Identity")
                .doesNotContain("requests/IP/minute");
        assertThat(germanMessages)
                .contains("preferences.llm.ratelimit=LLM-Kontingent je authentifizierter "
                        + "Identit" + "\\u00E4" + "t")
                .doesNotContain("Anfragen/IP/Minute");
        assertThat(properties)
                .contains("Maximum admitted LLM-backed requests per stable authenticated identity")
                .contains("Counters are per instance")
                .doesNotContain("Maximum LLM-backed API requests per IP");
    }

    @Test
    void retryAndDataProtectionTextDistinguishLlmQuotaFromPeerLockouts()
            throws Exception {
        Path root = repositoryRoot();

        assertThat(read(root, "docs/en/USER_GUIDE.md"))
                .contains("number of seconds given by the `Retry-After` response header")
                .doesNotContain("Wait 60 seconds and retry");
        assertThat(read(root, "docs/de/USER_GUIDE.md"))
                .contains("Antwort-Header `Retry-After`")
                .doesNotContain("60 Sekunden warten und erneut versuchen");
        assertThat(read(root, "docs/en/DATA_PROTECTION.md"))
                .contains("the incoming LLM quota does not use IP addresses")
                .contains("authentication/WebDAV brute-force detection");
        assertThat(read(root, "docs/de/DATA_PROTECTION.md"))
                .contains("das eingehende LLM-Kontingent verwendet keine IP-Adressen")
                .contains("Brute-Force-Erkennung für Anmeldung/WebDAV");
    }

    private static String read(Path root, String relative) throws IOException {
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("taxonomy-app"))
                    && Files.isDirectory(current.resolve("docs/de"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}

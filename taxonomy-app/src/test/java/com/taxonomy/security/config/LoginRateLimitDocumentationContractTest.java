package com.taxonomy.security.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps the operator-facing login lockout contract aligned in English and German. */
class LoginRateLimitDocumentationContractTest {

    @Test
    void documentationDescribesAuthoritativeBoundedPeerLockout() throws Exception {
        Path root = repositoryRoot();
        String english = read(root, "docs/en/LOGIN_BRUTE_FORCE_PROTECTION.md");
        String german = read(root, "docs/de/LOGIN_BRUTE_FORCE_PROTECTION.md");

        assertThat(english)
                .contains("after `SecurityContextHolderFilter`")
                .contains("before `UsernamePasswordAuthenticationFilter`")
                .contains("explicit `Authorization: Basic ...` header")
                .contains("framework-resolved `HttpServletRequest.getRemoteAddr()`")
                .contains("hard-capped at 10,000 entries")
                .contains("fail-closed overflow budget")
                .contains("`Retry-After`")
                .contains("`Cache-Control: no-store`")
                .contains("`/taxonomy`")
                .doesNotContain("trusts `X-Forwarded-For`");
        assertThat(german)
                .contains("nachdem `SecurityContextHolderFilter`")
                .contains("bevor `UsernamePasswordAuthenticationFilter`")
                .contains("Header `Authorization: Basic ...`")
                .contains("`HttpServletRequest.getRemoteAddr()`")
                .contains("hart auf 10.000 Einträge begrenzt")
                .contains("fehlgeschlossenes Überlaufkontingent")
                .contains("`Retry-After`")
                .contains("`Cache-Control: no-store`")
                .contains("`/taxonomy`")
                .doesNotContain("vertraut `X-Forwarded-For`");
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

package com.taxonomy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureExceptionLedgerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void checkedLedgerMatchesExactlyTheExceptionsUsedByArchUnit() throws Exception {
        Path ledgerPath = findRepositoryRoot().resolve(".github/architecture-exceptions.json");
        JsonNode root = objectMapper.readTree(Files.readString(ledgerPath));

        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        JsonNode exceptions = root.path("exceptions");
        assertThat(exceptions.isArray()).isTrue();

        Set<String> ids = new LinkedHashSet<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (JsonNode entry : exceptions) {
            String id = requiredText(entry, "id");
            assertThat(ids.add(id)).as("duplicate exception id %s", id).isTrue();
            requiredText(entry, "kind");
            requiredText(entry, "fromPackage");
            requiredText(entry, "toPackage");
            requiredText(entry, "owner");
            requiredText(entry, "rationale");
            requiredText(entry, "removalCondition");
            LocalDate expiry = LocalDate.parse(requiredText(entry, "expiresOn"));
            assertThat(expiry)
                    .as("architecture exception %s must not be expired", id)
                    .isAfterOrEqualTo(today);
        }

        assertThat(ids)
                .as("ledger entries and hard-coded ArchUnit exceptions must match exactly")
                .containsExactlyInAnyOrderElementsOf(
                        ArchitectureCycleBoundaryTest.DOCUMENTED_EXCEPTION_IDS);
    }

    private static String requiredText(JsonNode entry, String field) {
        String value = entry.path(field).asText();
        assertThat(value)
                .as("ledger field %s must be present and non-blank", field)
                .isNotBlank();
        return value;
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(".github/architecture-exceptions.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root");
    }
}

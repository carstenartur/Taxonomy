package com.taxonomy.portfolio.workbench;

import com.taxonomy.architecture.decision.DecisionReportBuildMetadataService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;

class VisioExportContextServiceTest {
    private static final Instant EXPORTED = Instant.parse("2026-10-27T12:34:56Z");
    private static final Clock CLOCK = Clock.fixed(EXPORTED, ZoneOffset.UTC);

    @Test void capturesActualBuildActorAndOneExportTimeWithTrustedDeploymentPath() {
        var service = service("https://taxonomy.example:8443/taxonomy/");
        var context = service.create(42L, 90L, "reviewer");
        assertThat(context).isNotNull();
        assertThat(context.generatorName()).isEqualTo("Taxonomy Architecture Analyzer");
        assertThat(context.generatorVersion()).isEqualTo("1.5.3-test");
        assertThat(context.exportedBy()).isEqualTo("reviewer");
        assertThat(context.exportedAt()).isEqualTo(EXPORTED);
        assertThat(context.requirementUrl()).isEqualTo("https://taxonomy.example:8443/taxonomy/projects/42/requirements/90");
        assertThat(context.documentProperties().get("taxonomy.exportedAt").value()).isEqualTo(EXPORTED.toString());
        assertThat(service.create(42L, 90L, "reviewer")).isEqualTo(context);
        assertThat(service.create(42L, 90L, "another-user")).isNotEqualTo(context);
        var later = new VisioExportContextService(new DecisionReportBuildMetadataService("1.5.3-test", "commit"),
                "https://taxonomy.example", Clock.offset(CLOCK, java.time.Duration.ofSeconds(1)))
                .create(42L, 90L, "reviewer");
        assertThat(later.exportedAt()).isEqualTo(EXPORTED.plusSeconds(1));
    }

    @Test void neverInventsALinkForMissingConfigurationOrUnsavedViews() {
        var missing = service("").create(42L, 90L, "reviewer");
        assertThat(missing.requirementUrl()).isNull();
        assertThat(missing.requirementUrlStatus()).contains("not configured");
        assertThat(missing.documentProperties()).doesNotContainKey("taxonomy.requirementUrl");
        var working = service("https://taxonomy.example").create("reviewer");
        assertThat(working.requirementUrl()).isNull();
        assertThat(working.requirementUrlStatus()).contains("no saved requirement");
    }

    @Test void rejectsUnsafeOrAmbiguousOperatorUrlsWithoutEchoingTheirValues() {
        for (String unsafe : new String[] {"javascript:alert(1)", "//taxonomy.example", "https://user:secret@taxonomy.example",
                "https://taxonomy.example?token=secret", "https://taxonomy.example#secret", "https://taxonomy.example/a/../b",
                "https://taxonomy.example//b", "https://taxonomy.example/a%2Fb", "https://taxonomy.example/a\\b",
                "https://taxonomy.example/\r\n", "https://taxonomy.example:99999", "https://taxonomy.example/./a"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> service(unsafe))
                    .withMessage("taxonomy.export.public-base-url must be an absolute HTTP(S) URL with an unambiguous deployment path and no credentials, query or fragment");
        }
    }

    @Test void rejectsMissingOrInvalidSavedCoordinatesAndNeverFabricatesAnActor() {
        var service = service("https://taxonomy.example");
        assertThatIllegalArgumentException().isThrownBy(() -> service.create(null, 90L, "reviewer"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.create(42L, -1L, "reviewer"));
        assertThat(service.create((String) null).documentProperties()).doesNotContainKey("taxonomy.exportedBy");
    }

    private static VisioExportContextService service(String base) {
        return new VisioExportContextService(new DecisionReportBuildMetadataService("1.5.3-test", "commit"), base, CLOCK);
    }
}

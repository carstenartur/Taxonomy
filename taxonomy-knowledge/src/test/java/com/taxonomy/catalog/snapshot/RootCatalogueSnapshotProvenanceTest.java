package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.provenance.CatalogueSourceBytes;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal.InputReference;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal.Use;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RootCatalogueSnapshotProvenanceTest {
    private static final String HASH = "a".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("invalidReferences")
    void rejectsMalformedDurableInputEvidence(String component, InputReference reference) {
        ObjectNode json = (ObjectNode) mapper.valueToTree(snapshot());
        ((ObjectNode) json.get("catalogueProvenance")).set(component, mapper.valueToTree(reference));
        if (component.equals("overlay") && reference != null && reference.use() == Use.APPLIED) {
            ObjectNode overlay = (ObjectNode) json.get("overlayMetadata");
            overlay.put("enabled", true);
            if (reference.sha256() == null) overlay.putNull("sha256");
            else overlay.put("sha256", reference.sha256());
        }
        assertThatThrownBy(() -> mapper.treeToValue(json, RootCatalogueSnapshot.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> invalidReferences() {
        return Stream.of("workbook", "overlay", "relations").flatMap(component -> Stream.of(
                new InputReference(null, null, 0),
                new InputReference(Use.APPLIED, null, 0),
                new InputReference(Use.APPLIED, "short", 1),
                new InputReference(Use.APPLIED, "A".repeat(64), 1),
                new InputReference(Use.APPLIED, HASH, -1),
                new InputReference(Use.APPLIED, HASH, (long) CatalogueSourceBytes.MAX_BYTES + 1),
                new InputReference(Use.PARSE_FAILED, null, 0),
                new InputReference(Use.PARSE_FAILED, "short", 1),
                new InputReference(Use.PARSE_FAILED, HASH, -1),
                new InputReference(Use.PARSE_FAILED, HASH, (long) CatalogueSourceBytes.MAX_BYTES + 1),
                new InputReference(Use.NOT_USED, HASH, 0),
                new InputReference(Use.NOT_USED, null, 1),
                new InputReference(Use.NOT_USED, null, -1),
                new InputReference(Use.NOT_RETAINED, HASH, 0),
                new InputReference(Use.NOT_RETAINED, null, 1),
                new InputReference(Use.NOT_RETAINED, null, -1))
                .map(reference -> Arguments.of(component, reference)));
    }

    @ParameterizedTest
    @MethodSource("validReferences")
    void preservesEveryValidUseAndRetainedLengthBoundary(InputReference reference) {
        ObjectNode json = (ObjectNode) mapper.valueToTree(snapshot());
        ObjectNode provenance = (ObjectNode) json.get("catalogueProvenance");
        provenance.set("workbook", mapper.valueToTree(reference));
        provenance.set("relations", mapper.valueToTree(reference));
        RootCatalogueSnapshot decoded = mapper.treeToValue(json, RootCatalogueSnapshot.class);
        assertThat(decoded.catalogueProvenance().workbook()).isEqualTo(reference);
        assertThat(decoded.catalogueProvenance().relations()).isEqualTo(reference);
    }

    private static Stream<InputReference> validReferences() {
        return Stream.of(new InputReference(Use.APPLIED, HASH, 0),
                new InputReference(Use.APPLIED, HASH, CatalogueSourceBytes.MAX_BYTES),
                new InputReference(Use.PARSE_FAILED, HASH, 0),
                new InputReference(Use.PARSE_FAILED, HASH, CatalogueSourceBytes.MAX_BYTES),
                new InputReference(Use.NOT_USED, null, 0),
                new InputReference(Use.NOT_RETAINED, null, 0));
    }

    private static RootCatalogueSnapshot snapshot() {
        var node = CatalogueSnapshotServiceTest.node("CP", "CP", null, "Capabilities");
        var input = new InputReference(Use.NOT_USED, null, 0);
        return new RootCatalogueSnapshot(2, new CatalogueSourceIdentity("repository", "workspace", "branch", "commit"),
                "CP", List.of(RootCatalogueSnapshot.Node.capture(node,
                new CatalogueOverlayService.NodeMetadata("CATEGORY", List.of(), 1, false, null), false)),
                new CatalogueOverlayService.OverlayMetadata(false, "frozen", "fixture", "v1", null, 0),
                new CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-000000000001", Instant.EPOCH,
                        input, input, input));
    }
}

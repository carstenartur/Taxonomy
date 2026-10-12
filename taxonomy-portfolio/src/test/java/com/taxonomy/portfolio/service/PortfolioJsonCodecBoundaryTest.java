package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.TaxonomyDataFingerprint;
import com.taxonomy.dto.TaxonomyNodeDto;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortfolioJsonCodecBoundaryTest {
    final PortfolioJsonCodec codec=new PortfolioJsonCodec(new ObjectMapper());
    @Test void optionalPayloadsRemainAbsentAndCollectionsRemainImmutable() {
        assertThat(codec.write(null)).isNull();
        for(String empty:new String[]{null,""," "}) {
            assertThat(codec.read(empty,Map.class)).isNull();
            assertThat(codec.readLongList(empty)).isEmpty();assertThat(codec.readStringMap(empty)).isEmpty();
        }
        assertThat(codec.read(codec.write(Map.of("key","value")),Map.class)).containsEntry("key","value");
        var ids=codec.readLongList("[1,2,2]");assertThat(ids).containsExactly(1L,2L,2L);
        assertThatThrownBy(()->ids.add(3L)).isInstanceOf(UnsupportedOperationException.class);
        var attributes=codec.readStringMap("{\"key\":\"value\"}");assertThat(attributes).containsEntry("key","value");
        assertThatThrownBy(()->attributes.put("other","v")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void invalidPayloadsBecomeTypedAnalysisFailures() {
        assertThatThrownBy(()->codec.read("{",Map.class)).isInstanceOf(PortfolioException.class).hasMessageContaining("deserialize portfolio payload");
        assertThatThrownBy(()->codec.readLongList("{}" )).isInstanceOf(PortfolioException.class).hasMessageContaining("source fragment IDs");
        assertThatThrownBy(()->codec.readStringMap("[]")).isInstanceOf(PortfolioException.class).hasMessageContaining("extension attributes");
        // The codec constructs a strict evidence reader from rebuild(); keep a
        // real mapper there while faulting only the intended serialization call.
        var mapper=spy(new ObjectMapper());var failure=new IllegalArgumentException("serialization failure");
        doThrow(failure).when(mapper).writeValueAsString("payload");
        assertThatThrownBy(()->new PortfolioJsonCodec(mapper).write("payload"))
                .isInstanceOf(PortfolioException.class).hasCause(failure);
    }

    @Test
    void computedContainerMetadataPreservesHistoricalSnapshotEvidenceAcrossRoundTrips() {
        // Historical snapshots have hierarchy metadata but no taxonomyContainer property.
        AnalysisResult result = codec.read("""
                {"scores":{"BP":64,"BP-1000":57},"tree":[%s]}
                """.formatted(historicalTreeRoot()), AnalysisResult.class);
        String fingerprint = TaxonomyDataFingerprint.sha256(result.getTree());
        String legacyFingerprint = TaxonomyDataFingerprint.legacySha256(result.getTree());
        String scoreFingerprint = result.getScoreSemanticsFingerprintSha256();
        var rawScores = Map.copyOf(result.getRawScores());
        var effectiveScores = Map.copyOf(result.getScores());
        var scoreDetails = Map.copyOf(result.getScoreDetails());
        assertThat(rawScores).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 64, "BP-1000", 57));

        for (int round = 0; round < 3; round++) {
            String payload = codec.write(result);
            var serializedRoot = new ObjectMapper().readTree(payload).get("tree").get(0);
            assertThat(serializedRoot.get("taxonomyContainer").booleanValue()).isTrue();
            assertThat(serializedRoot.get("children").get(0).get("taxonomyContainer").booleanValue()).isFalse();
            result = codec.read(payload, AnalysisResult.class);

            var root = result.getTree().get(0);
            var original = root.getChildren().get(0);
            assertThat(root.isTaxonomyContainer()).isTrue();
            assertThat(root.getCode()).isEqualTo("BP");
            assertThat(root.getUuid()).isEqualTo("fixture-container-uuid");
            assertThat(root.getAnalysisRole()).isEqualTo("CATEGORY");
            assertThat(root.getMatchPercentage()).isEqualTo(64);
            assertThat(original.isTaxonomyContainer()).isFalse();
            assertThat(original.getCode()).isEqualTo("BP-1000");
            assertThat(original.getParentCode()).isEqualTo("BP");
            assertThat(original.getUuid()).isEqualTo("fixture-original-uuid");
            assertThat(original.getMatchPercentage()).isEqualTo(57);
            assertThat(TaxonomyDataFingerprint.sha256(result.getTree())).isEqualTo(fingerprint);
            assertThat(TaxonomyDataFingerprint.legacySha256(result.getTree())).isEqualTo(legacyFingerprint);
            assertThat(result.getRawScores()).isEqualTo(rawScores);
            assertThat(result.getScores()).isEqualTo(effectiveScores);
            assertThat(result.getScoreDetails()).isEqualTo(scoreDetails);
            assertThat(result.getScoreSemanticsFingerprintSha256()).isEqualTo(scoreFingerprint);
        }
    }

    @Test
    void incomingContainerMetadataCannotOverrideTheRecordedHierarchy() {
        var historical = codec.read(historicalTreeRoot(), TaxonomyNodeDto.class);
        String fingerprint = TaxonomyDataFingerprint.sha256(List.of(historical));
        for (boolean supplied : List.of(false, true)) {
            String rootPayload = "{\"taxonomyContainer\":" + supplied + "," + historicalTreeRoot().strip().substring(1);
            var root = codec.read(rootPayload, TaxonomyNodeDto.class);
            assertThat(root.isTaxonomyContainer()).isTrue();
            assertThat(TaxonomyDataFingerprint.sha256(List.of(root))).isEqualTo(fingerprint);

            String originalPayload = "{\"taxonomyContainer\":" + supplied + """
                    ,"code":"BP-1000","taxonomyRoot":"BP","level":1,"parentCode":"BP"}
                    """;
            assertThat(codec.read(originalPayload, TaxonomyNodeDto.class).isTaxonomyContainer()).isFalse();
        }
    }

    private static String historicalTreeRoot() {
        return """
                {"code":"BP","taxonomyRoot":"BP","level":0,"parentCode":null,
                 "uuid":"fixture-container-uuid","nameEn":"Shared display name",
                 "analysisRole":"CATEGORY","matchPercentage":64,
                 "children":[{"code":"BP-1000","taxonomyRoot":"BP","level":1,"parentCode":"BP",
                  "uuid":"fixture-original-uuid","nameEn":"Shared display name",
                  "analysisRole":"CATEGORY","matchPercentage":57}]}
                """;
    }
}

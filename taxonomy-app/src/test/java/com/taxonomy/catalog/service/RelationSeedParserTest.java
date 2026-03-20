package com.taxonomy.catalog.service;

import com.taxonomy.dto.RelationSeedRow;
import com.taxonomy.model.RelationType;
import com.taxonomy.model.SeedType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RelationSeedParser}.
 */
class RelationSeedParserTest {

    @Test
    void parseExtendedFormat() throws IOException {
        String csv = "SourceCode,TargetCode,RelationType,Description,SourceStandard,SourceReference,Confidence,SeedType,ReviewRequired,Status\n"
                + "CP,CR,REALIZES,Capabilities realized by Core Services,NAF,NCV-2,1.0,TYPE_DEFAULT,false,accepted\n";

        List<RelationSeedRow> rows = RelationSeedParser.parse(toStream(csv));
        assertThat(rows).hasSize(1);

        RelationSeedRow row = rows.get(0);
        assertThat(row.sourceCode()).isEqualTo("CP");
        assertThat(row.targetCode()).isEqualTo("CR");
        assertThat(row.relationType()).isEqualTo(RelationType.REALIZES);
        assertThat(row.description()).isEqualTo("Capabilities realized by Core Services");
        assertThat(row.sourceStandard()).isEqualTo("NAF");
        assertThat(row.sourceReference()).isEqualTo("NCV-2");
        assertThat(row.confidence()).isEqualTo(1.0);
        assertThat(row.seedType()).isEqualTo(SeedType.TYPE_DEFAULT);
        assertThat(row.reviewRequired()).isFalse();
        assertThat(row.status()).isEqualTo("accepted");
    }

    @Test
    void parseLegacyFourColumnFormat() throws IOException {
        String csv = "SourceCode,TargetCode,RelationType,Description\n"
                + "CP,CR,REALIZES,Capabilities realized by Core Services\n";

        List<RelationSeedRow> rows = RelationSeedParser.parse(toStream(csv));
        assertThat(rows).hasSize(1);

        RelationSeedRow row = rows.get(0);
        assertThat(row.sourceCode()).isEqualTo("CP");
        assertThat(row.targetCode()).isEqualTo("CR");
        assertThat(row.relationType()).isEqualTo(RelationType.REALIZES);
        assertThat(row.description()).isEqualTo("Capabilities realized by Core Services");
        assertThat(row.sourceStandard()).isNull();
        assertThat(row.sourceReference()).isNull();
        assertThat(row.confidence()).isEqualTo(1.0);
        assertThat(row.seedType()).isEqualTo(SeedType.TYPE_DEFAULT);
        assertThat(row.reviewRequired()).isFalse();
        assertThat(row.status()).isEqualTo("accepted");
    }

    @Test
    void parseLegacyThreeColumnFormat() throws IOException {
        String csv = "SourceCode,TargetCode,RelationType\n"
                + "CP,CR,REALIZES\n";

        List<RelationSeedRow> rows = RelationSeedParser.parse(toStream(csv));
        assertThat(rows).hasSize(1);

        RelationSeedRow row = rows.get(0);
        assertThat(row.sourceCode()).isEqualTo("CP");
        assertThat(row.targetCode()).isEqualTo("CR");
        assertThat(row.relationType()).isEqualTo(RelationType.REALIZES);
        assertThat(row.description()).isNull();
    }

    @Test
    void skipsMalformedRows() throws IOException {
        String csv = "SourceCode,TargetCode,RelationType,Description\n"
                + "CP\n"                              // too few columns
                + ",CR,REALIZES,desc\n"               // empty source
                + "CP,,REALIZES,desc\n"               // empty target
                + "CP,CR,,desc\n"                     // empty type
                + "CP,CR,INVALID_TYPE,desc\n"         // unknown type
                + "CP,CR,REALIZES,valid row\n";       // this one is valid

        List<RelationSeedRow> rows = RelationSeedParser.parse(toStream(csv));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).description()).isEqualTo("valid row");
    }

    @Test
    void skipsBlankLines() throws IOException {
        String csv = "SourceCode,TargetCode,RelationType,Description\n"
                + "\n"
                + "   \n"
                + "CP,CR,REALIZES,valid\n";

        List<RelationSeedRow> rows = RelationSeedParser.parse(toStream(csv));
        assertThat(rows).hasSize(1);
    }

    @Test
    void emptyFileReturnsEmptyList() throws IOException {
        String csv = "";
        List<RelationSeedRow> rows = RelationSeedParser.parse(toStream(csv));
        assertThat(rows).isEmpty();
    }

    @Test
    void headerOnlyReturnsEmptyList() throws IOException {
        String csv = "SourceCode,TargetCode,RelationType,Description\n";
        List<RelationSeedRow> rows = RelationSeedParser.parse(toStream(csv));
        assertThat(rows).isEmpty();
    }

    @Test
    void confidenceClampedToValidRange() throws IOException {
        String csv = "SourceCode,TargetCode,RelationType,Description,SourceStandard,SourceReference,Confidence\n"
                + "CP,CR,REALIZES,over-confidence,,,1.5\n"
                + "CI,CR,REALIZES,negative-confidence,,,-0.5\n";

        List<RelationSeedRow> rows = RelationSeedParser.parse(toStream(csv));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).confidence()).isEqualTo(1.0);
        assertThat(rows.get(1).confidence()).isEqualTo(0.0);
    }

    @Test
    void invalidConfidenceDefaultsToOne() throws IOException {
        String csv = "SourceCode,TargetCode,RelationType,Description,SourceStandard,SourceReference,Confidence\n"
                + "CP,CR,REALIZES,bad-conf,,,notanumber\n";

        List<RelationSeedRow> rows = RelationSeedParser.parse(toStream(csv));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).confidence()).isEqualTo(1.0);
    }

    @Test
    void invalidSeedTypeDefaultsToTypeDefault() throws IOException {
        String csv = "SourceCode,TargetCode,RelationType,Description,SourceStandard,SourceReference,Confidence,SeedType\n"
                + "CP,CR,REALIZES,bad-seed,,,1.0,UNKNOWN_SEED\n";

        List<RelationSeedRow> rows = RelationSeedParser.parse(toStream(csv));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).seedType()).isEqualTo(SeedType.TYPE_DEFAULT);
    }

    @Test
    void frameworkSeedTypeIsParsed() throws IOException {
        String csv = "SourceCode,TargetCode,RelationType,Description,SourceStandard,SourceReference,Confidence,SeedType,ReviewRequired,Status\n"
                + "CR,IP,PRODUCES,service output,TOGAF,Data Architecture,0.9,FRAMEWORK_SEED,true,accepted\n";

        List<RelationSeedRow> rows = RelationSeedParser.parse(toStream(csv));
        assertThat(rows).hasSize(1);

        RelationSeedRow row = rows.get(0);
        assertThat(row.seedType()).isEqualTo(SeedType.FRAMEWORK_SEED);
        assertThat(row.reviewRequired()).isTrue();
        assertThat(row.confidence()).isEqualTo(0.9);
    }

    @Test
    void provenanceEncodesSeedType() {
        RelationSeedRow defaultRow = new RelationSeedRow(
                "CP", "CR", RelationType.REALIZES, "desc", "NAF", "NCV-2",
                1.0, SeedType.TYPE_DEFAULT, false, "accepted");
        assertThat(defaultRow.toProvenance()).isEqualTo("csv-default:NAF");

        RelationSeedRow frameworkRow = new RelationSeedRow(
                "CR", "IP", RelationType.PRODUCES, "desc", "TOGAF", "Data Architecture",
                0.9, SeedType.FRAMEWORK_SEED, false, "accepted");
        assertThat(frameworkRow.toProvenance()).isEqualTo("csv-framework:TOGAF");

        RelationSeedRow sourceDerivedRow = new RelationSeedRow(
                "CP", "CR", RelationType.REALIZES, "desc", null, null,
                0.7, SeedType.SOURCE_DERIVED, true, "proposed");
        assertThat(sourceDerivedRow.toProvenance()).isEqualTo("csv-source-derived");
    }

    @Test
    void parseMultipleRows() throws IOException {
        String csv = "SourceCode,TargetCode,RelationType,Description,SourceStandard,SourceReference,Confidence,SeedType,ReviewRequired,Status\n"
                + "CP,CR,REALIZES,row1,NAF,NCV-2,1.0,TYPE_DEFAULT,false,accepted\n"
                + "CR,BP,SUPPORTS,row2,TOGAF,Business Architecture,0.9,FRAMEWORK_SEED,true,accepted\n"
                + "UA,IP,CONSUMES,row3,LOCAL,,0.8,SOURCE_DERIVED,false,proposed\n";

        List<RelationSeedRow> rows = RelationSeedParser.parse(toStream(csv));
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).sourceCode()).isEqualTo("CP");
        assertThat(rows.get(1).sourceCode()).isEqualTo("CR");
        assertThat(rows.get(2).sourceCode()).isEqualTo("UA");
    }

    @Test
    void requiresRelationType() throws IOException {
        String csv = "SourceCode,TargetCode,RelationType,Description\n"
                + "CP,IP,REQUIRES,Capability requires info product\n";

        List<RelationSeedRow> rows = RelationSeedParser.parse(toStream(csv));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).relationType()).isEqualTo(RelationType.REQUIRES);
    }

    @Test
    void resultListIsUnmodifiable() throws IOException {
        String csv = "SourceCode,TargetCode,RelationType\nCP,CR,REALIZES\n";
        List<RelationSeedRow> rows = RelationSeedParser.parse(toStream(csv));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> rows.add(null));
    }

    private static ByteArrayInputStream toStream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}

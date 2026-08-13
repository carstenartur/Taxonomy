package com.taxonomy.dsl.command;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.AmbiguousRelationException;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.InvalidRelationBlockException;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.dsl.parser.TaxDslParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureRelationDslTransformerTest {

    private final ArchitectureRelationDslTransformer transformer =
            new ArchitectureRelationDslTransformer();
    private final TaxDslParser parser = new TaxDslParser();

    @Test
    void addsRelationAndPreservesUnrelatedAndUnknownBlocks() {
        String input = """
                meta {
                  language: "taxdsl";
                  version: "2.0";
                  namespace: "test";
                }

                element APP-1 type Application {
                  title: "Payments";
                }

                futureBlock FUTURE-1 {
                  x-owner: "architecture";
                }
                """;
        RelationIdentity identity = new RelationIdentity(
                "APP-1", "uses", "SVC-1");
        RelationDefinition relation = new RelationDefinition(
                identity,
                "accepted",
                0.8,
                "manual",
                Map.of("x-decision-id", "DEC-17"));

        var result = transformer.upsert(input, relation);

        assertThat(result.kind()).isEqualTo(ChangeKind.ADDED);
        assertThat(result.changed()).isTrue();
        DocumentAst document = parser.parse(result.dsl());
        assertThat(document.getMeta().namespace()).isEqualTo("test");
        assertThat(document.blocksOfKind("element")).hasSize(1);
        assertThat(document.blocksOfKind("futureBlock")).hasSize(1);
        BlockAst added = document.blocksOfKind("relation").getFirst();
        assertThat(added.getHeaderTokens())
                .containsExactly("APP-1", "USES", "SVC-1");
        assertThat(added.property("status")).isEqualTo("accepted");
        assertThat(added.property("confidence")).isEqualTo("0.8");
        assertThat(added.property("provenance")).isEqualTo("manual");
        assertThat(added.property("x-decision-id")).isEqualTo("DEC-17");
    }

    @Test
    void equivalentUpsertIsANoOpAndReturnsTheOriginalBytes() {
        String input = """
                relation APP-1 USES SVC-1 {
                    provenance: manual;
                    confidence: 0.8;
                    status: accepted;
                }
                """;
        RelationDefinition relation = new RelationDefinition(
                new RelationIdentity("APP-1", "USES", "SVC-1"),
                "accepted",
                0.8,
                "manual");

        var result = transformer.upsert(input, relation);

        assertThat(result.kind()).isEqualTo(ChangeKind.UNCHANGED);
        assertThat(result.changed()).isFalse();
        assertThat(result.dsl()).isSameAs(input);
    }

    @Test
    void updatesSuppliedReviewFieldsAndPreservesOtherProperties() {
        String input = """
                relation APP-1 USES SVC-1 {
                  status: proposed;
                  confidence: 0.55;
                  provenance: imported;
                  review-note: "retain me";
                  x-source-id: "proposal-9";
                }
                """;
        RelationDefinition relation = new RelationDefinition(
                new RelationIdentity("APP-1", "USES", "SVC-1"),
                "accepted",
                0.9,
                "manual",
                Map.of("x-decision-id", "decision-2"));

        var result = transformer.upsert(input, relation);

        assertThat(result.kind()).isEqualTo(ChangeKind.UPDATED);
        BlockAst updated = parser.parse(result.dsl())
                .blocksOfKind("relation").getFirst();
        assertThat(updated.propertyValues("status"))
                .containsExactly("accepted");
        assertThat(updated.propertyValues("confidence"))
                .containsExactly("0.9");
        assertThat(updated.property("provenance")).isEqualTo("manual");
        assertThat(updated.property("review-note")).isEqualTo("retain me");
        assertThat(updated.property("x-source-id")).isEqualTo("proposal-9");
        assertThat(updated.property("x-decision-id")).isEqualTo("decision-2");
    }

    @Test
    void removesOnlyTheExactRelation() {
        String input = """
                relation APP-1 USES SVC-1 {
                  status: accepted;
                }

                relation APP-1 USES SVC-2 {
                  status: accepted;
                }
                """;

        var result = transformer.remove(
                input, new RelationIdentity("APP-1", "USES", "SVC-1"));

        assertThat(result.kind()).isEqualTo(ChangeKind.REMOVED);
        assertThat(parser.parse(result.dsl()).blocksOfKind("relation"))
                .singleElement()
                .extracting(BlockAst::getHeaderTokens)
                .isEqualTo(java.util.List.of("APP-1", "USES", "SVC-2"));
    }

    @Test
    void absentRemovalIsANoOp() {
        String input = "element APP-1 type Application {\n}\n";

        var result = transformer.remove(
                input, new RelationIdentity("APP-1", "USES", "SVC-1"));

        assertThat(result.kind()).isEqualTo(ChangeKind.UNCHANGED);
        assertThat(result.dsl()).isSameAs(input);
    }

    @Test
    void rejectsAmbiguousDuplicateRelationsForUpsertAndRemoval() {
        String input = """
                relation APP-1 USES SVC-1 {
                  status: proposed;
                }

                relation APP-1 USES SVC-1 {
                  status: accepted;
                }
                """;
        RelationIdentity identity = new RelationIdentity(
                "APP-1", "USES", "SVC-1");

        assertThatThrownBy(() -> transformer.upsert(
                input,
                new RelationDefinition(identity, "accepted", null, null)))
                .isInstanceOf(AmbiguousRelationException.class)
                .hasMessageContaining("occurs 2 times");
        assertThatThrownBy(() -> transformer.remove(input, identity))
                .isInstanceOf(AmbiguousRelationException.class)
                .hasMessageContaining("occurs 2 times");
    }

    @Test
    void rejectsMalformedMatchingRelationHeader() {
        String input = """
                relation APP-1 USES SVC-1 unexpected-token {
                  status: proposed;
                }
                """;

        assertThatThrownBy(() -> transformer.upsert(
                input,
                new RelationDefinition(
                        new RelationIdentity("APP-1", "USES", "SVC-1"),
                        "accepted",
                        null,
                        null)))
                .isInstanceOf(InvalidRelationBlockException.class)
                .hasMessageContaining("malformed header");
    }

    @Test
    void validatesCommandTokensConfidenceAndExtensionKeys() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RelationIdentity(
                        "APP 1", "USES", "SVC-1"))
                .withMessageContaining("one DSL token");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RelationDefinition(
                        new RelationIdentity("APP-1", "USES", "SVC-1"),
                        "accepted", 1.1, "manual"))
                .withMessageContaining("between 0.0 and 1.0");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RelationDefinition(
                        new RelationIdentity("APP-1", "USES", "SVC-1"),
                        "accepted", 0.8, "manual",
                        Map.of("decision-id", "17")))
                .withMessageContaining("must start with x-");
    }
}

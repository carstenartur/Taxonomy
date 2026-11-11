package com.taxonomy.dsl.merge;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.parser.TaxDslParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Authored DSL fixtures: these identifiers do not claim catalogue provenance. */
class StableSemanticMergeTest {
    private final TaxDslSemanticMerger merger = new TaxDslSemanticMerger();
    private final TaxDslParser parser = new TaxDslParser();

    @Test
    void mergesTypeChangeWithIndependentPropertyChangeInEitherDirection() {
        String base = block("element arch-a type System", "title: Original;\nx-vendor: retained;");
        String typeEdit = base.replace("type System", "type Component");
        String propertyEdit = base.replace("Original", "Reviewed");
        for (TaxDslMergeResult result : new TaxDslMergeResult[]{
                merger.merge(base, typeEdit, propertyEdit), merger.merge(base, propertyEdit, typeEdit)}) {
            assertThat(result.conflicts()).isEmpty();
            assertThat(parser.parse(result.mergedText()).getBlocks()).singleElement().satisfies(merged -> {
                assertThat(merged.getHeaderTokens()).containsExactly("arch-a", "type", "Component");
                assertThat(merged.property("title")).isEqualTo("Reviewed");
                assertThat(merged.property("x-vendor")).isEqualTo("retained");
            });
        }
        assertThat(ArchitectureSemanticPatch.between(base, typeEdit)).singleElement()
                .satisfies(change -> assertThat(change.id()).isEqualTo("element:arch-a"));
    }

    @Test
    void mergerAndPatchShareKindAwareIdentityForNamedCompositeAndUnknownBlocks() {
        for (String header : new String[]{"element arch-a type System", "view arch-a",
                "relation arch-a CONTAINS arch-b", "mapping req-a to arch-a",
                "projectRequirement project-a req-a", "requirementVersion req-a v2",
                "vendorExtension opaque qualifier"}) {
            BlockAst ast = parser.parse(block(header, "x-value: retained;")).getBlocks().getFirst();
            assertThat(merger.identity(ast)).isEqualTo(ArchitectureSemanticPatch.key(ast));
        }
    }

    @Test
    void conflictingTypesRemainOneExplicitConflict() {
        String base = block("element arch-a type System", "title: Original;");
        TaxDslMergeResult result = merger.merge(base, base.replace("System", "Component"),
                base.replace("System", "Capability"));
        assertThat(result.mergedText()).isNull();
        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.blockIdentity()).isEqualTo("element:arch-a");
            assertThat(conflict.propertyKey()).isEqualTo("header");
        });
    }

    @Test
    void deletionConflictsWithTypeChange() {
        String base = block("element arch-a type System", "title: Original;");
        TaxDslMergeResult result = merger.merge(base, "", base.replace("System", "Component"));
        assertThat(result.mergedText()).isNull();
        assertThat(result.conflicts()).singleElement()
                .satisfies(conflict -> assertThat(conflict.reason()).contains("deleted in ours"));
    }

    @Test
    void duplicateElementIdentityCannotHideBehindDifferentTypes() {
        String first = block("element arch-a type System", "title: Original;");
        TaxDslMergeResult result = merger.merge("", first + first.replace("System", "Component"), "");
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.conflicts()).anySatisfy(conflict -> assertThat(conflict.reason()).contains("duplicate"));
    }

    @Test
    void independentMembershipAdditionsMergeFromEmptySet() {
        TaxDslMergeResult result = merger.merge(view(""), view("include: arch-a;"), view("include: arch-b;"));
        assertThat(result.conflicts()).isEmpty();
        assertThat(merged(result).propertyValues("include")).containsExactly("arch-a", "arch-b");
    }

    @Test
    void independentMembershipRemovalAndAdditionMerge() {
        String base = view("include: arch-a;\ninclude: arch-b;");
        String ours = view("include: arch-b;\ninclude: arch-c;");
        String theirs = view("include: arch-a;\ninclude: arch-d;");
        TaxDslMergeResult result = merger.merge(base, ours, theirs);
        assertThat(result.conflicts()).isEmpty();
        assertThat(merged(result).propertyValues("include")).containsExactly("arch-c", "arch-d");
    }

    @Test
    void membershipReorderingDoesNotResurrectRemovedMember() {
        TaxDslMergeResult result = merger.merge(view("include: arch-a;\ninclude: arch-b;"),
                view("include: arch-b;\ninclude: arch-a;"), view("include: arch-b;"));
        assertThat(result.conflicts()).isEmpty();
        assertThat(merged(result).propertyValues("include")).containsExactly("arch-b");
    }

    @Test
    void preservesUnknownRepeatedValuesWhileMergingIndependentKnownEdits() {
        String base = view("title: Original;\nx-step: first;\nx-step: second;\ninclude: arch-a;");
        TaxDslMergeResult result = merger.merge(base, base.replace("Original", "Reviewed"),
                base.replace("include: arch-a;", "include: arch-a;\ninclude: arch-b;"));
        assertThat(result.conflicts()).isEmpty();
        assertThat(merged(result).propertyValues("x-step")).containsExactly("first", "second");
        assertThat(merged(result).property("title")).isEqualTo("Reviewed");
        assertThat(merged(result).propertyValues("include")).containsExactly("arch-a", "arch-b");
    }

    @Test
    void concurrentOrderedReorderingAndValueEditConflictRatherThanDroppingReorder() {
        String base = view("x-step: first;\nx-step: second;");
        TaxDslMergeResult result = merger.merge(base, view("x-step: second;\nx-step: first;"),
                view("x-step: first;\nx-step: changed;"));
        assertThat(result.mergedText()).isNull();
        assertThat(result.conflicts()).singleElement()
                .satisfies(conflict -> assertThat(conflict.propertyKey()).isEqualTo("x-step"));
    }

    @Test
    void unknownIncludePropertyDoesNotAcquireViewSetSemantics() {
        String base = block("vendorExtension opaque", "include: first;\ninclude: second;");
        TaxDslMergeResult result = merger.merge(base, base.replace("first", "ours"), base.replace("second", "theirs"));
        assertThat(result.isSuccessful()).isFalse();
    }

    @Test
    void unknownBlockHeaderAndExtensionContentSurviveIndependentChanges() {
        String base = block("vendorExtension opaque qualifier", "title: Original;\nx-payload: untouched;");
        TaxDslMergeResult result = merger.merge(base, base.replace("qualifier", "changed"),
                base.replace("Original", "Reviewed"));
        assertThat(result.conflicts()).isEmpty();
        assertThat(merged(result).getHeaderTokens()).containsExactly("opaque", "changed");
        assertThat(merged(result).property("x-payload")).isEqualTo("untouched");
        assertThat(merged(result).property("title")).isEqualTo("Reviewed");
    }

    @Test
    void finalValidationRejectsMembershipAddedForAnIndependentlyDeletedElement() {
        ArchitectureDslCommands commands = new ArchitectureDslCommands();
        String element = block("element arch-a type System", "title: Fixture;");
        String base = element + view("");
        String ours = view("");
        String theirs = element + view("include: arch-a;");
        assertThatCode(() -> commands.validateReconciledSource(ours)).doesNotThrowAnyException();
        assertThatCode(() -> commands.validateReconciledSource(theirs)).doesNotThrowAnyException();
        TaxDslMergeResult result = merger.merge(base, ours, theirs);
        assertThat(result.conflicts()).isEmpty();
        assertThatThrownBy(() -> commands.validateReconciledSource(result.mergedText()))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        problem -> assertThat(problem.code()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void finalValidationRejectsIncompatibleTypesCombinedFromIndividuallyValidChanges() {
        ArchitectureDslCommands commands = new ArchitectureDslCommands();
        String base = block("element arch-parent type System", "title: Parent;")
                + block("element arch-child type Component", "title: Child;")
                + block("relation arch-parent CONTAINS arch-child", "status: accepted;");
        String ours = base.replace("arch-parent type System", "arch-parent type Component");
        String theirs = base.replace("arch-child type Component", "arch-child type UserApplication");
        assertThatCode(() -> commands.validateReconciledSource(ours)).doesNotThrowAnyException();
        assertThatCode(() -> commands.validateReconciledSource(theirs)).doesNotThrowAnyException();
        TaxDslMergeResult result = merger.merge(base, ours, theirs);
        assertThat(result.conflicts()).isEmpty();
        assertThatThrownBy(() -> commands.validateReconciledSource(result.mergedText()))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        problem -> assertThat(problem.code()).isEqualTo("INCOMPATIBLE_RELATION"));
    }

    @Test
    void portfolioAssociationAndEvidenceIdentitiesRetainEveryKeyComponent() {
        for (String[] headers : new String[][]{
                {"solutionTaxonomyCoverage solution-a node-a", "solutionTaxonomyCoverage solution-a node-b"},
                {"productTaxonomyCoverage product-a node-a", "productTaxonomyCoverage product-a node-b"},
                {"projectSolutionDecision project-a solution-a", "projectSolutionDecision project-a solution-b"},
                {"requirementSolutionDecision project-a req-a solution-a", "requirementSolutionDecision project-a req-a solution-b"},
                {"solutionProductDecision project-a solution-a product-a", "solutionProductDecision project-a solution-a product-b"},
                {"reformulationEvidence project-a req-a 2 hash-a", "reformulationEvidence project-a req-a 2 hash-b"}}) {
            String first = block(headers[0], "x-value: first;");
            String second = block(headers[1], "x-value: second;");
            TaxDslMergeResult result = merger.merge("", first, second);
            assertThat(result.conflicts()).as(headers[0]).isEmpty();
            assertThat(parser.parse(result.mergedText()).getBlocks()).hasSize(2);
            assertThat(ArchitectureSemanticPatch.index(first + second)).hasSize(2);
            assertThat(ArchitectureSemanticPatch.between(first, first + second)).singleElement()
                    .satisfies(change -> {
                        assertThat(change.before()).isNull();
                        assertThat(change.after()).contains(headers[1]);
                    });
        }
    }

    private BlockAst merged(TaxDslMergeResult result) {
        return parser.parse(result.mergedText()).getBlocks().getFirst();
    }

    private static String view(String properties) { return block("view fixture-view", properties); }
    private static String block(String header, String properties) { return header + " {\n" + properties + "\n}\n"; }
}

package com.taxonomy.dsl.command;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.planning.PlanningEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class ArchitectureDslCommandBatchTest {
    private final ArchitectureDslCommands transformer = new ArchitectureDslCommands();
    private static final String MODEL = """
            # Retain the document header.
            source original {
              title: "Untouched source";
              x-future-property: "Änderung 😀 # { }";
            }
            element system type System {
              # Retain the object rationale.
              title: "Before";
              x-owner: "Team";
            }
            element component type Component {
              title: "Component";
            }
            """;
    private static final RelationKey CONTAINS = new RelationKey("system", "CONTAINS", "component");

    @Test
    void dependentCommandsRetainTheExactSequentialDocumentAndCompleteAggregatePatch() {
        List<ArchitectureCommand> commands = List.of(
                new CreateArchitectureElement("child", "Component", Map.of("title", "New child")),
                new UpdateArchitectureElement("system", null, Map.of("title", "Reviewed", "description", "Complete description")),
                new CreateArchitectureRelation(CONTAINS, "proposed"),
                new UpdateArchitectureRelation(CONTAINS, "accepted"),
                new MoveOrGroupElement("child", "component"),
                new MoveOrGroupElement("child", "system"),
                new SetExchangeProperties("element", "system", Map.of("x-exchange-id", "external-system")),
                new ClearArchitectureElementProperties("system", Set.of("description")),
                new UpsertArchitectureView("reviewed", "Reviewed view", "Complete members", List.of("system", "child"), Map.of()),
                new DeleteArchitectureView("reviewed"),
                new MoveOrGroupElement("child", null),
                new DeleteArchitectureElement("child"),
                new DeleteArchitectureRelation(CONTAINS),
                new StoreExchangeEvidence("complete", "archimate", "1", "a".repeat(64), "{\"complete\":\"Änderung 😀\"}"));

        var actual = assertEquivalent(MODEL, commands);
        assertThat(actual.changedIds()).containsExactly("element:system", "exchange:complete");
        assertThat(actual.dsl()).contains("# Retain the object rationale.", "x-owner: \"Team\";", "external-system", "Änderung 😀")
                .doesNotContain("Complete description", "element child", "view reviewed", "relation system CONTAINS component");
    }

    @Test
    void packageMappingAndPlanningCommandsKeepTheirOrderedDependencies() {
        String source = MODEL + "requirement req {\n  title: \"Requirement\";\n  text: \"Full requirement\";\n}\n";
        var year = new PlanningEntry("launch", "go-live", "1", "MANUAL", Map.of("precision", "YEAR", "value", "2030"));
        var opaque = new PlanningEntry("future", "go-live", "99", "external", Map.of("unknown", "retain"));
        List<ArchitectureCommand> commands = List.of(
                new CreateArchitecturePackage("root", Map.of("title", "Package")),
                new UpdateArchitecturePackage("root", Map.of("title", "Reviewed package")),
                new SetArchitecturePackagePlacements(List.of(
                        new PackagePlacement(PackageMemberKind.PACKAGE, "root", "", 0),
                        new PackagePlacement(PackageMemberKind.ELEMENT, "component", "root", 0)), Set.of("", "root")),
                new UpsertRequirementMapping("req", "component", "Reviewed exchange mapping", Map.of("x-exchange-id", "mapping")),
                new SetRequirementPlanning("req", year),
                new ImportRequirementPlanning("req", opaque),
                new DeleteRequirementPlanning("req", "launch"),
                new DeleteRequirementMapping("req", "component"),
                new SetArchitecturePackagePlacements(List.of(
                        new PackagePlacement(PackageMemberKind.PACKAGE, "root", "", 0),
                        new PackagePlacement(PackageMemberKind.ELEMENT, "component", null, -1)), Set.of("", "root")),
                new DeleteArchitecturePackage("root"));

        var actual = assertEquivalent(source, commands);
        assertThat(actual.changedIds()).containsExactly("requirement:req");
        assertThat(actual.dsl()).contains("Full requirement", "future", "retain").doesNotContain("launch", "mapping req", "package root");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void unicodeQuotedBracesCommentsUnknownBlocksAndLineEndingsMatchSequentialEdits(String newline) {
        String source = MODEL.replace("title: \"Before\";", "title: \"Before { # quoted }\"; # trailing rationale {")
                .replace("\n", newline);
        var actual = assertEquivalent(source, List.of(
                new UpdateArchitectureElement("system", null, Map.of("title", "Nachher 😀", "description", "Line one\nQuoted \"text\" { # }")),
                new SetExchangeProperties("element", "system", Map.of("x-exchange-opaque", "Änderung\r\n完全")),
                new UpdateArchitectureElement("component", null, Map.of("title", "Baustein"))));
        assertThat(actual.dsl()).startsWith(source.substring(0, source.indexOf("element system")))
                .contains("# trailing rationale {", "# Retain the object rationale.", "Nachher 😀", "完全");
    }

    @Test
    void emptyBatchKeepsTheOriginalZeroCommandPathEvenForUntouchedAmbiguousInput() {
        String ambiguous = MODEL + MODEL;
        var actual = transformer.applyAll(ambiguous, List.of());
        assertThat(actual.dsl()).isSameAs(ambiguous);
        assertThat(actual.changes()).isEmpty();
    }

    @Test
    void noOpAndNetZeroSequencesHaveNoAggregateChanges() {
        var noOp = assertEquivalent(MODEL, List.of(new UpdateArchitectureElement("system", null, Map.of("title", "Before"))));
        assertThat(noOp.dsl()).isSameAs(MODEL);
        assertThat(noOp.changes()).isEmpty();
        var netZero = assertEquivalent(MODEL, List.of(new CreateArchitectureElement("temporary", "System", Map.of("title", "Temporary")),
                new DeleteArchitectureElement("temporary")));
        assertThat(netZero.dsl()).isEqualTo(MODEL);
        assertThat(netZero.changes()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidSequences")
    void theSameFirstInvalidIntermediateCommandFailsBeforeLaterCommandsCanRepairIt(InvalidSequence sequence) {
        var expected = catchThrowableOfType(() -> sequential(sequence.source(), sequence.commands()), CommandProblem.class);
        assertThat(expected).isNotNull();
        assertThatThrownBy(() -> transformer.applyAll(sequence.source(), sequence.commands()))
                .isInstanceOfSatisfying(CommandProblem.class, actual -> {
                    assertThat(actual.code()).isEqualTo(expected.code());
                    assertThat(actual.field()).isEqualTo(expected.field());
                    assertThat(actual.dependencies()).isEqualTo(expected.dependencies());
                    assertThat(actual.getMessage()).isEqualTo(expected.getMessage());
                });
    }

    private static Stream<InvalidSequence> invalidSequences() {
        String related = MODEL + "relation system CONTAINS component {\n  status: \"accepted\";\n}\n";
        return Stream.of(
                new InvalidSequence(related, List.of(new DeleteArchitectureElement("component"), new DeleteArchitectureRelation(CONTAINS))),
                new InvalidSequence(MODEL, List.of(new CreateArchitectureRelation(new RelationKey("system", "CONTAINS", "later"), null),
                        new CreateArchitectureElement("later", "Component", Map.of("title", "Too late")))),
                new InvalidSequence(MODEL, List.of(new CreateArchitectureElement("temporary", "System", Map.of("title", "First")),
                        new CreateArchitectureElement("temporary", "System", Map.of("title", "Duplicate")), new DeleteArchitectureElement("temporary"))),
                new InvalidSequence(MODEL + MODEL, List.of(new UpdateArchitectureElement("system", null, Map.of("title", "Ambiguous")))),
                new InvalidSequence("element system type System {\n  title: \"Unclosed\";\n",
                        List.of(new UpdateArchitectureElement("system", null, Map.of("title", "Changed")))),
                new InvalidSequence(MODEL.replace("title: \"Before\";", "title: \"Before\";\n  title: \"Duplicate\";"),
                        List.of(new UpdateArchitectureElement("system", null, Map.of("title", "Changed")))),
                new InvalidSequence(MODEL, List.of(new CreateArchitectureRelation(CONTAINS, "invalid"), new CreateArchitectureRelation(CONTAINS, "accepted"))),
                new InvalidSequence(MODEL + "requirement req {\n title: \"Requirement\";\n}\n"
                        + "mapping req -> component {\n source: \"analysis\";\n x-project-key: \"project\";\n}\n",
                        List.of(new UpsertRequirementMapping("req", "component", "Cannot replace analysis", Map.of()),
                                new DeleteRequirementMapping("req", "component"))));
    }

    private ArchitectureDslCommands.Change assertEquivalent(String source, List<ArchitectureCommand> commands) {
        String expected = sequential(source, commands);
        var actual = transformer.applyAll(source, commands);
        assertThat(actual.dsl()).isEqualTo(expected);
        assertThat(actual.changes()).isEqualTo(ArchitectureSemanticPatch.between(source, expected));
        return actual;
    }

    private String sequential(String source, List<ArchitectureCommand> commands) {
        String next = source;
        for (var command : commands) next = transformer.apply(next, command).dsl();
        return next;
    }

    private record InvalidSequence(String source, List<ArchitectureCommand> commands) {}
}

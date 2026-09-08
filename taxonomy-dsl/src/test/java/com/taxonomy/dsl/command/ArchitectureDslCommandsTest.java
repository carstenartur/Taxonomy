package com.taxonomy.dsl.command;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ArchitectureDslCommandsTest {
    private final ArchitectureDslCommands commands = new ArchitectureDslCommands();
    // Architecture object identities, deliberately independent of catalog taxonomy codes.
    private static final String MODEL = """
            # Keep document comment and unusual block order.
            source origin {
              title: "Original source";
              x-provider: "untouched";
            }

            element arch-system type System {
              # Keep object rationale.
              title: "Payments";
              x-owner: "Alice";
              x-future-property: "retain";
            }

            element arch-component type Component {
              title: "Ledger";
            }
            """;
    private static final RelationKey CONTAINS = new RelationKey("arch-system", "CONTAINS", "arch-component");

    @Test
    void editPreservesUnrelatedBytesCommentsIdentityAndUnknownProperties() {
        var update = new UpdateArchitectureElement("arch-system", null, Map.of("title", "New name", "description", "Quoted \"text\"\nNext line"));
        var result = commands.apply(MODEL, update);
        assertThat(result.changedIds()).containsExactly("element:arch-system");
        assertThat(result.dsl()).startsWith(MODEL.substring(0, MODEL.indexOf("element arch-system")))
                .contains("# Keep object rationale.", "x-future-property: \"retain\";", "element arch-system type System")
                .endsWith(MODEL.substring(MODEL.indexOf("element arch-component")));
        assertThat(commands.model(result.dsl()).findElement("arch-system").orElseThrow().getDescription()).isEqualTo("Quoted \"text\"\nNext line");
        assertThat(commands.apply(result.dsl(), update).changes()).isEmpty();
        assertThat(commands.apply(result.dsl(), update).dsl()).isEqualTo(result.dsl());
    }

    @Test
    void duplicateIdentityAndUnapprovedPropertiesAreRejected() {
        assertCode(() -> commands.apply(MODEL, new CreateArchitectureElement("arch-system", "System", Map.of("title", "Duplicate"))), "DUPLICATE_ID");
        assertCode(() -> commands.apply(MODEL, new CreateArchitectureElement("arch-new", "CanvasShape", Map.of("title", "Bad type"))), "UNKNOWN_ELEMENT_TYPE");
        assertCode(() -> commands.apply(MODEL, new UpdateArchitectureElement("arch-system", null, Map.of("taxonomy", "invented"))), "READ_ONLY_PROPERTY");
        assertCode(() -> commands.apply(MODEL, new UpdateArchitectureElement("arch-system", null, Map.of("title", " "))), "REQUIRED_PROPERTY");
        assertCode(() -> commands.apply(MODEL + MODEL, new DeleteArchitectureElement("arch-system")), "DUPLICATE_ID");
        assertCode(() -> commands.apply("", new CreateArchitectureElement("arch-new\n}", "System", Map.of("title", "Injected"))), "INVALID_ID");
    }

    @Test
    void relationValidationAndDeletionImpactUseTheCanonicalMetamodel() {
        String connected = commands.apply(MODEL, new CreateArchitectureRelation(CONTAINS, "accepted")).dsl();
        assertCode(() -> commands.apply(connected, new CreateArchitectureRelation(CONTAINS, "proposed")), "DUPLICATE_RELATION");
        assertCode(() -> commands.apply(MODEL, new CreateArchitectureRelation(new RelationKey("arch-component", "CONTAINS", "arch-system"), null)), "INCOMPATIBLE_RELATION");
        assertCode(() -> commands.apply(MODEL, new CreateArchitectureRelation(new RelationKey("arch-system", "RELATED_TO", "arch-system"), null)), "SELF_RELATION");
        assertCode(() -> commands.apply(MODEL, new CreateArchitectureRelation(new RelationKey("arch-system", "RELATED_TO", "arch-missing"), null)), "NOT_FOUND");
        assertThatThrownBy(() -> commands.apply(connected, new DeleteArchitectureElement("arch-component")))
                .isInstanceOfSatisfying(CommandProblem.class, p -> assertThat(p.dependencies()).containsExactly("relation:" + CONTAINS.id()));
        assertCode(() -> commands.apply(connected, new UpdateArchitectureElement("arch-component", "InformationProduct", Map.of())), "INCOMPATIBLE_RELATION");
        String reviewed = connected + "evidence decision {\n  for-relation: \"" + CONTAINS.id() + "\";\n  summary: \"Reviewed\";\n}\n";
        assertCode(() -> commands.apply(reviewed, new DeleteArchitectureRelation(CONTAINS)), "DEPENDENCIES_EXIST");
        String disconnected = commands.apply(connected, new DeleteArchitectureRelation(CONTAINS)).dsl();
        assertThat(commands.apply(disconnected, new DeleteArchitectureElement("arch-component")).dsl()).doesNotContain("element arch-component");
    }

    @Test
    void containmentRejectsCyclesAndMultipleParentsAndMovesAtomically() {
        String model = commands.apply(MODEL, new CreateArchitectureElement("arch-child", "Component", Map.of("title", "Child"))).dsl();
        model = commands.apply(model, new CreateArchitectureRelation(CONTAINS, null)).dsl();
        model = commands.apply(model, new MoveOrGroupElement("arch-child", "arch-component")).dsl();
        String connected = model;
        assertCode(() -> commands.apply(connected, new CreateArchitectureRelation(new RelationKey("arch-child", "CONTAINS", "arch-component"), null)), "MULTIPLE_PARENTS");
        assertCode(() -> commands.apply(connected, new MoveOrGroupElement("arch-component", "arch-child")), "CONTAINMENT_CYCLE");
        String moved = commands.apply(connected, new MoveOrGroupElement("arch-child", "arch-system")).dsl();
        assertThat(moved).contains("relation arch-system CONTAINS arch-child").doesNotContain("relation arch-component CONTAINS arch-child");
        assertThat(commands.apply(moved, new MoveOrGroupElement("arch-child", null)).dsl()).doesNotContain("CONTAINS arch-child");
    }

    @Test
    void inverseKeepsLaterUnrelatedChangesAndRejectsNewDependencies() {
        String edited = commands.apply(MODEL, new UpdateArchitectureElement("arch-system", null, Map.of("title", "New title"))).dsl();
        String later = commands.apply(edited, new UpdateArchitectureElement("arch-component", null, Map.of("title", "Later edit"))).dsl();
        String undone = commands.inverse(later, MODEL, edited).dsl();
        assertThat(commands.model(undone).findElement("arch-system").orElseThrow().getTitle()).isEqualTo("Payments");
        assertThat(commands.model(undone).findElement("arch-component").orElseThrow().getTitle()).isEqualTo("Later edit");
        assertThat(commands.inverse(undone, later, undone).dsl()).isEqualTo(later);
        assertCode(() -> commands.inverse(edited.replace("New title", "Conflicting edit"), MODEL, edited), "UNDO_CONFLICT");
        assertCode(() -> commands.inverse(edited.replace("# Keep object rationale.", "# Later annotation"), MODEL, edited), "UNDO_CONFLICT");
        String created = commands.apply(MODEL, new CreateArchitectureElement("arch-created", "Component", Map.of("title", "New"))).dsl();
        String dependency = created + "view added {\n  include: \"arch-created\";\n}\n";
        assertCode(() -> commands.inverse(dependency, MODEL, created), "DEPENDENCIES_EXIST");
    }

    @Test
    void importedMultipleParentsCannotMakeMoveAFalseNoOp() {
        String invalid = MODEL + "element arch-other type System {\n  title: \"Other\";\n}\n"
                + "relation arch-system CONTAINS arch-component {\n}\n"
                + "relation arch-other CONTAINS arch-component {\n}\n";
        assertCode(() -> commands.apply(invalid, new MoveOrGroupElement("arch-component", "arch-system")), "MULTIPLE_PARENTS");
        assertCode(() -> commands.apply(invalid, new MoveOrGroupElement("arch-component", "arch-other")), "MULTIPLE_PARENTS");
        assertCode(() -> commands.apply(invalid, new MoveOrGroupElement("arch-component", null)), "MULTIPLE_PARENTS");
    }

    @Test
    void malformedEditedBlockAndDuplicatePropertiesFailClosed() {
        assertCode(() -> commands.apply("element arch-system type System {\n title: \"Incomplete\";\n",
                new UpdateArchitectureElement("arch-system", null, Map.of("title", "Changed"))), "INVALID_DOCUMENT");
        assertCode(() -> commands.apply(MODEL.replace("title: \"Payments\";", "title: \"Payments\";\n title: \"Duplicate\";"),
                new UpdateArchitectureElement("arch-system", null, Map.of("title", "Changed"))), "DUPLICATE_PROPERTY");
    }

    @Test
    void sourceRangesSupportCrLfAndCrWithoutTouchingOtherBlocksOrLosingTrailingComments() {
        for (String newline : java.util.List.of("\r\n", "\r")) {
            String original = MODEL.replace("title: \"Payments\";", "# comment ending in {\n  title: \"Payments #1\"; # inline rationale")
                    .replace("\n", newline);
            var changed = commands.apply(original, new UpdateArchitectureElement("arch-system", null, Map.of("title", "Updated")));
            assertThat(changed.dsl()).contains("# comment ending in {", "# inline rationale")
                    .doesNotContain("#1\"");
            assertThat(changed.dsl()).startsWith(original.substring(0, original.indexOf("element arch-system")))
                    .endsWith(original.substring(original.indexOf("element arch-component")));
            assertThat(commands.inverse(changed.dsl(), original, changed.dsl()).dsl()).isEqualTo(original);
        }
    }

    @Test
    void quotedAndCommentBracesCannotChangeBlockBoundariesOrInverseTargets() {
        String prefix = "# Outside the edited block }\n";
        String target = """
                element arch-system type System { # opening comment {
                  title: "Before { # literal }"; # trailing comment {
                  x-future-property: "Escaped \\" { # literal"; # retained comment }
                  x-count: 7; # comment with "quoted text" {
                } # closing comment {
                """;
        String suffix = "element arch-other type Component {\n  title: \"Other }\";\n}\n";
        for (String newline : java.util.List.of("\n", "\r\n", "\r")) {
            String original = (prefix + target + suffix).replace("\n", newline);
            var update = new UpdateArchitectureElement("arch-system", null, Map.of("title", "After {"));
            var changed = commands.apply(original, update);
            assertThat(changed.changedIds()).containsExactly("element:arch-system");
            assertThat(changed.dsl()).startsWith(prefix.replace("\n", newline)).endsWith(suffix.replace("\n", newline))
                    .contains("# opening comment {", "# trailing comment {", "# retained comment }", "# closing comment {");
            var element = commands.model(changed.dsl()).findElement("arch-system").orElseThrow();
            assertThat(element.getExtensions()).containsEntry("x-count", "7")
                    .containsEntry("x-future-property", "Escaped \" { # literal");
            assertThat(commands.apply(changed.dsl(), update).changes()).isEmpty();
            assertThat(commands.inverse(changed.dsl(), original, changed.dsl()).dsl()).isEqualTo(original);
            assertThat(commands.apply(original, new DeleteArchitectureElement("arch-system")).dsl())
                    .isEqualTo((prefix + suffix).replace("\n", newline));
        }
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CommandProblem.class, problem -> assertThat(problem.code()).isEqualTo(code));
    }
}

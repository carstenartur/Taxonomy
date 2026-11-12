package com.taxonomy.dsl.command;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.mapper.ModelToAstMapper;
import com.taxonomy.dsl.model.ArchitectureOrganization;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.dsl.validation.DslValidator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ArchitectureOrganizationTest {
    private final ArchitectureDslCommands commands = new ArchitectureDslCommands();
    private static final String LEGACY = """
            element system type System {
              title: "Application";
              x-responsible-organization: "Legacy office";
            }
            """;
    private static final String REF = "x-responsible-organization-id";

    @Test
    void directoryAndAssignmentRoundTripWithoutChangingLegacyText() {
        String directory = directory();
        var assignment = commands.apply(directory, new UpdateArchitectureElement("system", null, Map.of(REF, "ops")));
        String renamed = commands.apply(assignment.dsl(),
                new UpsertOrganization("ops", Map.of("title", "Operations North"))).dsl();
        var model = commands.model(renamed);
        assertThat(model.getOrganizations()).containsExactly(
                new ArchitectureOrganization("agency", "Agency", null),
                new ArchitectureOrganization("ops", "Operations North", "agency"));
        assertThat(model.getElements().getFirst().getExtensions()).containsEntry(REF, "ops")
                .containsEntry("x-responsible-organization", "Legacy office");
        var text = new TaxDslSerializer().serialize(new ModelToAstMapper().toDocument(model, "test"));
        var reparsed = commands.model(text);
        assertThat(reparsed.getOrganizations()).containsExactlyElementsOf(model.getOrganizations());
        assertThat(new DslValidator().validate(reparsed).isValid()).isTrue();
        var undo = commands.inverse(renamed, directory, assignment.dsl());
        assertThat(commands.model(undo.dsl()).getElements().getFirst().getExtensions()).doesNotContainKey(REF);
        assertThat(commands.model(undo.dsl()).getOrganizations()).isEqualTo(model.getOrganizations());
    }

    @Test
    void rejectsUnknownReferencesCyclesAndDeletionWithDependants() {
        String directory = directory();
        assertThatThrownBy(() -> commands.apply(directory, new UpsertOrganization("agency", Map.of("parentId", "ops"))))
                .isInstanceOf(ArchitectureDslCommands.CommandProblem.class);
        assertThatThrownBy(() -> commands.apply(directory, new UpsertOrganization("agency", Map.of("parentId", "missing"))))
                .isInstanceOf(ArchitectureDslCommands.CommandProblem.class);
        assertThatThrownBy(() -> commands.apply(directory, new UpdateArchitectureElement("system", null, Map.of(REF, "other"))))
                .isInstanceOf(ArchitectureDslCommands.CommandProblem.class);
        assertThatThrownBy(() -> commands.apply(directory, new DeleteOrganization("agency")))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        e -> assertThat(e.dependencies()).contains("organization:ops"));
        String assigned = commands.apply(directory, new UpdateArchitectureElement("system", null, Map.of(REF, "ops"))).dsl();
        assertThatThrownBy(() -> commands.apply(assigned, new DeleteOrganization("ops")))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        e -> assertThat(e.dependencies()).contains("element:system"));
        String cleared = commands.apply(assigned, new UpdateArchitectureElement("system", null, Map.of(REF, ""))).dsl();
        var deleted = commands.apply(cleared, new DeleteOrganization("ops"));
        assertThat(commands.model(deleted.dsl()).getOrganizations()).extracting(ArchitectureOrganization::id).containsExactly("agency");
        assertThat(commands.model(commands.inverse(deleted.dsl(), cleared, deleted.dsl()).dsl()).getOrganizations())
                .containsExactlyElementsOf(commands.model(cleared).getOrganizations());
    }

    @Test
    void importedSourceAndUndoCannotIntroduceDanglingAssignments() {
        String invalid = LEGACY.replace("  title:", "  " + REF + ": \"missing\";\n  title:");
        assertThat(new DslValidator().validate(commands.model(invalid)).isValid()).isFalse();
        assertThatThrownBy(() -> commands.validateReconciledSource(invalid)).isInstanceOf(ArchitectureDslCommands.CommandProblem.class);
        var created = commands.apply(LEGACY, new UpsertOrganization("ops", Map.of("title", "Operations")));
        var assigned = commands.apply(created.dsl(), new UpdateArchitectureElement("system", null, Map.of(REF, "ops")));
        assertThatThrownBy(() -> commands.inverse(assigned.dsl(), LEGACY, created.dsl()))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        e -> assertThat(e.dependencies()).contains("element:system"));
    }

    private String directory() {
        var root = commands.apply(LEGACY, new UpsertOrganization("agency", Map.of("title", "Agency")));
        return commands.apply(root.dsl(), new UpsertOrganization("ops", Map.of("title", "Operations", "parentId", "agency"))).dsl();
    }
}

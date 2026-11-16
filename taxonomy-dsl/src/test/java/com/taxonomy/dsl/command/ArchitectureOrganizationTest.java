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
    @Test void theFirstAssignmentInAnUnpinnedProjectRetainsItsAppliedCatalogue() {
        var commands = new ArchitectureDslCommands(com.taxonomy.dsl.model.CatalogueDefinition.standard(),
                code -> new com.taxonomy.dsl.model.CatalogueDefinitionResolver.AssignmentTarget(
                        "BP-1000".equals(code), "applied-catalogue"));
        String source = "organization ops {\n  title: \"Operations\";\n}\n";
        String edited = commands.apply(source, new SetCatalogueResponsibility("BP-1000", "ops")).dsl();
        assertThat(commands.model(edited).responsibilityCatalogueRevision()).isEqualTo("applied-catalogue");
        assertThat(commands.inverse(edited, source, edited).dsl()).isEqualTo(source);
    }
    private final ArchitectureDslCommands commands = new ArchitectureDslCommands();
    private static final String LEGACY = """
            element system type System {
              title: "Application";
              x-responsible-organization: "Legacy office";
            }
            """;
    private static final String REF = "x-responsible-organization-id";
    private static final String CATALOGUE = """
            organization ops {
              title: "Operations";
            }

            catalogueResponsibility BP-1 {
              organization: ops;
            }
            """;

    @Test
    void catalogueAssignmentSurvivesCanonicalRoundTripWithoutArchitectureElements() {
        var model = commands.model(CATALOGUE);
        assertThat(model.getElements()).isEmpty();
        var source = new TaxDslSerializer().serialize(new ModelToAstMapper().toDocument(model, "test"));
        assertThat(source).contains("catalogueResponsibility BP-1").contains("organization: \"ops\";");
        commands.validateReconciledSource(source);
    }

    @Test
    void catalogueAssignmentPreventsDeletingTheOrganizationItReferences() {
        assertThatThrownBy(() -> commands.apply(CATALOGUE, new DeleteOrganization("ops")))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        error -> assertThat(error.dependencies()).contains("catalogueResponsibility:BP-1"));
    }

    @Test
    void changingAndClearingCatalogueAssignmentUsesTheNormalUndoPatch() {
        String original = commands.apply(CATALOGUE, new UpsertOrganization("delivery", Map.of("title", "Delivery"))).dsl();
        var update = commands.apply(original, new SetCatalogueResponsibility("BP-1", "delivery"));
        assertThat(commands.model(update.dsl()).getCatalogueResponsibilities())
                .containsExactly(new com.taxonomy.dsl.model.CatalogueResponsibility("BP-1", "delivery"));
        var cleared = commands.apply(update.dsl(), new SetCatalogueResponsibility("BP-1", ""));
        assertThat(commands.model(cleared.dsl()).getCatalogueResponsibilities()).isEmpty();
        assertThat(commands.inverse(cleared.dsl(), update.dsl(), cleared.dsl()).dsl()).isEqualTo(update.dsl());
    }

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
        assertThat(commands.model(cleared).getElements().getFirst().getExtensions()).doesNotContainKey(REF)
                .containsEntry("x-responsible-organization", "Legacy office");
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

    @Test
    void malformedDuplicateSourceReferencesCannotStrandAssignmentsOnDelete() {
        String duplicate = directory().replace("  x-responsible-organization:", "  " + REF + ": \"agency\";\n  "
                + REF + ": \"ops\";\n  x-responsible-organization:");
        assertThatThrownBy(() -> commands.apply(duplicate, new DeleteOrganization("ops")))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        e -> assertThat(e.dependencies()).contains("element:system"));
        assertThatThrownBy(() -> commands.validateReconciledSource(duplicate))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        e -> assertThat(e.code()).isEqualTo("DUPLICATE_PROPERTY"));
    }

    private String directory() {
        var root = commands.apply(LEGACY, new UpsertOrganization("agency", Map.of("title", "Agency")));
        return commands.apply(root.dsl(), new UpsertOrganization("ops", Map.of("title", "Operations", "parentId", "agency"))).dsl();
    }
}

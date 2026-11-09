package com.taxonomy.dsl.admbw;

import com.taxonomy.dsl.command.ArchitectureCommand.ClearArchitectureElementProperties;
import com.taxonomy.dsl.command.ArchitectureCommand.ConfigureAdmbwDelivery;
import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureRelation;
import com.taxonomy.dsl.command.ArchitectureCommand.DeleteArchitectureView;
import com.taxonomy.dsl.command.ArchitectureCommand.RelationKey;
import com.taxonomy.dsl.command.ArchitectureCommand.UpdateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureCommand.UpsertArchitectureView;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.mapper.ModelToAstMapper;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmbwDeliveryAuthoringTest {
    private static final String DSL = """
            element CP-1022 type Capability {
              title: "Warnen";
              description: "Warnmeldungen bereitstellen";
              taxonomy: "CP-1022";
              x-admbw-catalogue-guid: "{67AEAD63-403A-48FD-A022-174DC3037CE2}";
              x-admbw-catalogue-reference: "Catalogue edition 1";
            }
            element CR-1047 type CoreService {
              title: "Warnmeldungsdienst";
              description: "Gibt Warnmeldungen aus";
              taxonomy: "CR-1047";
              x-admbw-type: "ServiceSpecification";
            }
            element other type BusinessRole {
              title: "Warnstelle";
              description: "Organisatorische Zuständigkeit";
            }
            view warning {
              title: "Warnung";
              include: CP-1022;
              include: CR-1047;
              x-analysis-relation-members: "";
              x-analysis-catalogue-fingerprint: "%s";
            }
            delivery admbw {
              profile: "admbw-2026.05-fffmlv-2.0-c1-s1-r7";
              project-code: "WARN";
              source-view: "warning";
              catalogue-fingerprint: "%s";
            }
            """.formatted("ab".repeat(32), "ab".repeat(32));

    private final ArchitectureDslCommands commands = new ArchitectureDslCommands();

    @Test
    void rejectsAnInScopeGenericEdgeBeforeWritingAndAllowsAnOutOfScopeEdge() {
        assertThatThrownBy(() -> commands.apply(DSL, relation("CR-1047", "RELATED_TO", "CP-1022")))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        error -> assertThat(error.code()).isEqualTo("ADMBW_RELATION_NOT_ALLOWED"));
        assertThat(commands.apply(DSL, relation("CR-1047", "RELATED_TO", "other")).dsl())
                .contains("relation CR-1047 RELATED_TO other");
    }

    @Test
    void rejectsAnUnboundCapabilityAsAConnectionEndpointBeforeWriting() {
        String unbound = DSL.replace("x-admbw-catalogue-guid: \"{67AEAD63-403A-48FD-A022-174DC3037CE2}\";", "");
        assertThatThrownBy(() -> commands.apply(unbound, relation("CR-1047", "FULFILLS", "CP-1022")))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        error -> assertThat(error.code()).isEqualTo("ADMBW_RELATION_NOT_ALLOWED"));
    }

    @Test
    void boundDeliveryProjectsNewExhibitsUsingTheSameEndpointRuleAndFrozenScope() {
        String edited = commands.apply(DSL, relation("CR-1047", "FULFILLS", "CP-1022")).dsl();
        var report = new AdmbwFffmlvProfile().assess(edited, "WARN", "warning");
        assertThat(report.capabilityToService().issues()).isEmpty();
        assertThat(report.capabilityToService().connectors()).containsExactly(
                new AdmbwFffmlvProfile.Connector("Exhibits", "element:CR-1047", "element:CP-1022"));
        assertThatThrownBy(() -> new AdmbwFffmlvProfile().assess(edited, "OTHER", "warning"))
                .hasMessageContaining("saved ADMBw delivery");
    }

    @Test
    void preservesTheEndpointsOfAnExistingExhibitsConnectionDuringElementEdits() {
        String linked = commands.apply(DSL, relation("CR-1047", "FULFILLS", "CP-1022")).dsl();
        assertThatThrownBy(() -> commands.apply(linked, new ClearArchitectureElementProperties(
                "CP-1022", Set.of(AdmbwFffmlvProfile.CATALOGUE_GUID))))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        error -> assertThat(error.code()).isEqualTo("ADMBW_C1_S1_ENDPOINT_IN_USE"));
        assertThatThrownBy(() -> commands.apply(linked, new UpdateArchitectureElement(
                "CR-1047", null, Map.of(AdmbwFffmlvProfile.TYPE, "ResourceRole"))))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        error -> assertThat(error.code()).isEqualTo("ADMBW_C1_S1_ENDPOINT_IN_USE"));
        assertThat(commands.apply(linked, new UpdateArchitectureElement(
                "CR-1047", null, Map.of("title", "Meldedienst"))).dsl()).contains("Meldedienst");
    }

    @Test
    void changingTheDeliveryScopeClearsThePreviousCatalogueBindingAndCanBeUndone() {
        String updated = commands.apply(DSL, new ConfigureAdmbwDelivery("ALERT", null)).dsl();
        var profile = commands.model(updated).getAdmbwDelivery();
        assertThat(profile.projectCode()).isEqualTo("ALERT");
        assertThat(profile.sourceViewId()).isNull();
        assertThat(profile.catalogueFingerprint()).isNull();
        assertThat(commands.model(commands.inverse(updated, DSL, updated).dsl()).getAdmbwDelivery())
                .isEqualTo(commands.model(DSL).getAdmbwDelivery());
    }

    @Test
    void deliveryPreventsDeletionOfItsBoundSourceView() {
        assertThatThrownBy(() -> commands.apply(DSL, new DeleteArchitectureView("warning")))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        error -> assertThat(error.dependencies()).containsExactly("delivery:admbw"));
    }

    @Test
    void canonicalDslRoundTripRetainsTheDeliveryAndItsSourceMembers() {
        var model = commands.model(DSL);
        String serialized = new TaxDslSerializer().serialize(new ModelToAstMapper().toDocument(model, "warnings"));
        var reopened = commands.model(serialized);
        assertThat(reopened.getAdmbwDelivery()).isEqualTo(model.getAdmbwDelivery());
        assertThat(reopened.getAdmbwDelivery().members(reopened)).containsExactlyInAnyOrder("CP-1022", "CR-1047");
    }

    @Test
    void reconciliationRetainsLegacyEdgesButRejectsNewForbiddenConnections() {
        String legacy = DSL + """
                relation CR-1047 RELATED_TO CP-1022 {
                  status: accepted;
                }
                """;
        commands.validateReconciledSource(legacy, legacy);
        assertThatThrownBy(() -> commands.validateReconciledSource(DSL, legacy))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        error -> assertThat(error.code()).isEqualTo("ADMBW_RELATION_NOT_ALLOWED"));
        String valid = commands.apply(legacy, relation("CR-1047", "FULFILLS", "CP-1022")).dsl();
        commands.validateReconciledSource(legacy, valid);
        String broken = valid.replace("x-admbw-type: \"ServiceSpecification\";", "");
        assertThatThrownBy(() -> commands.validateReconciledSource(valid, broken))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        error -> assertThat(error.code()).isEqualTo("ADMBW_C1_S1_ENDPOINT_IN_USE"));
    }

    @Test
    void boundScopeCannotChangeItsMembersWithoutBindingAnotherSource() {
        assertThatThrownBy(() -> commands.apply(DSL, new UpsertArchitectureView(
                "warning", "Warnung", "", List.of("CP-1022", "CR-1047", "other"), Map.of())))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        error -> assertThat(error.code()).isEqualTo("ADMBW_SOURCE_VIEW_IN_USE"));
        String amended = DSL.replace("include: CR-1047;", "include: CR-1047;\n  include: other;");
        assertThatThrownBy(() -> commands.validateReconciledSource(DSL, amended))
                .isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class,
                        error -> assertThat(error.code()).isEqualTo("ADMBW_SOURCE_VIEW_IN_USE"));
    }

    private static CreateArchitectureRelation relation(String source, String type, String target) {
        return new CreateArchitectureRelation(new RelationKey(source, type, target), "accepted");
    }
}

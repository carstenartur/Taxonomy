package com.taxonomy.dsl.command;

import com.taxonomy.dsl.command.ArchitectureCommand.SetExchangeProperties;
import com.taxonomy.dsl.command.ArchitectureCommand.UpsertArchitectureView;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureExchangePropertiesTest {
    private final ArchitectureDslCommands commands = new ArchitectureDslCommands();

    private static final String VIEW = """
            element arch-system type System {
              title: "Payments";
            }
            element arch-component type Component {
              title: "Ledger";
            }
            view shared {
              title: "Shared";
              description: "Members";
              include: "arch-system";
              include: "arch-component";
              x-exchange-tool-id: "old";
            }
            """;

    @Test
    void exchangePropertiesPreserveRepeatedViewMembers() {
        var changed = commands.apply(VIEW, new SetExchangeProperties("view", "shared",
                Map.of("x-exchange-tool-id", "remote-view", "x-exchange-profile", "archimate")));

        var block = ArchitectureSemanticPatch.index(changed.dsl()).get("view:shared");
        assertThat(block.propertyValues("include")).containsExactly("arch-system", "arch-component");
        assertThat(block.property("x-exchange-tool-id")).isEqualTo("remote-view");
        assertThat(block.property("x-exchange-profile")).isEqualTo("archimate");
        assertThat(changed.changedIds()).containsExactly("view:shared");
        assertThat(commands.apply(changed.dsl(), new SetExchangeProperties("view", "shared",
                Map.of("x-exchange-tool-id", "remote-view", "x-exchange-profile", "archimate"))).changes()).isEmpty();
    }

    @Test
    void exchangePropertiesStillRejectAmbiguousNonMemberViewProperties() {
        String ambiguous = VIEW.replace("title: \"Shared\";", "title: \"Shared\";\n  title: \"Duplicate\";");
        assertThatThrownBy(() -> commands.apply(ambiguous,
                new SetExchangeProperties("view", "shared", Map.of("x-exchange-tool-id", "remote-view"))))
                .isInstanceOfSatisfying(CommandProblem.class,
                        problem -> assertThat(problem.code()).isEqualTo("DUPLICATE_PROPERTY"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "description", "x-owner", "x-exchange-tool-id"})
    void upsertRejectsAmbiguousPropertiesBeforePreservingOrReplacingThem(String key) {
        String ambiguous = VIEW.replace("view shared {", "view shared {\n  " + key + ": \"Duplicate\";");
        if ("x-owner".equals(key)) {
            ambiguous = ambiguous.replace("view shared {", "view shared {\n  x-owner: \"Existing\";");
        }
        String source = ambiguous;

        assertThatThrownBy(() -> commands.apply(source, new UpsertArchitectureView("shared",
                "Updated", "Updated members", List.of("arch-system", "arch-component"),
                Map.of("x-exchange-tool-id", "remote-view"))))
                .isInstanceOfSatisfying(CommandProblem.class, problem -> {
                    assertThat(problem.code()).isEqualTo("DUPLICATE_PROPERTY");
                    assertThat(problem.field()).isEqualTo(key);
                });
    }

    @Test
    void upsertReplacesRepeatedMembersAndRetainsUnambiguousProperties() {
        String source = VIEW.replace("view shared {", "view shared {\n  x-owner: \"Architecture team\";");
        var command = new UpsertArchitectureView("shared", "Updated", "Updated members",
                List.of("arch-component", "arch-system"), Map.of("x-exchange-tool-id", "remote-view"));

        var changed = commands.apply(source, command);
        var block = ArchitectureSemanticPatch.index(changed.dsl()).get("view:shared");
        assertThat(block.propertyValues("include")).containsExactly("arch-component", "arch-system");
        assertThat(block.propertyValues("title")).containsExactly("Updated");
        assertThat(block.propertyValues("description")).containsExactly("Updated members");
        assertThat(block.propertyValues("x-exchange-tool-id")).containsExactly("remote-view");
        assertThat(block.property("x-owner")).isEqualTo("Architecture team");
        assertThat(changed.changedIds()).containsExactly("view:shared");
        assertThat(commands.apply(changed.dsl(), command).changes()).isEmpty();
    }
}

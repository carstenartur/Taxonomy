package com.taxonomy.dsl.command;

import com.taxonomy.dsl.command.ArchitectureCommand.SetExchangeProperties;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import org.junit.jupiter.api.Test;

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
}

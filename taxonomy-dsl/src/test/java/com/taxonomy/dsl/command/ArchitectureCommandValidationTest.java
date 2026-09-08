package com.taxonomy.dsl.command;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static org.assertj.core.api.Assertions.*;

class ArchitectureCommandValidationTest {
    @Test void nullPropertyValuesProduceStructuredDomainErrorsForBothTypedElementCommands() {
        var properties = new HashMap<String, String>();
        properties.put("title", null);
        assertThatThrownBy(() -> new CreateArchitectureElement("arch-null", "System", properties))
                .isInstanceOfSatisfying(CommandProblem.class, error -> {
                    assertThat(error.code()).isEqualTo("INVALID_VALUE");
                    assertThat(error.field()).isEqualTo("title");
                });
        assertThatThrownBy(() -> new UpdateArchitectureElement("arch-null", null, properties))
                .isInstanceOfSatisfying(CommandProblem.class, error -> assertThat(error.code()).isEqualTo("INVALID_VALUE"));
        assertThatThrownBy(() -> new UpdateArchitectureElement("arch-null", null, null))
                .isInstanceOfSatisfying(CommandProblem.class, error -> assertThat(error.field()).isEqualTo("properties"));
    }
}

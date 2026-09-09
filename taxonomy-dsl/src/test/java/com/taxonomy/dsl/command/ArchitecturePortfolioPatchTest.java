package com.taxonomy.dsl.command;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchitecturePortfolioPatchTest {
    @Test void compositePortfolioIdentitiesPermitIndependentRequirementsAndImmutableVersions() {
        String architecture = "# Original source comment\r\nelement arch-kept type System {\r\n    title: \"Unchanged\";\r\n}\r\n";
        String first = "projectRequirement P REQ-1 {\n title: \"First\";\n}\n"
                + "projectRequirement P REQ-2 {\n title: \"Second\";\n}\n"
                + "requirementVersion P REQ-1 1 {\n text: \"Initial text\";\n}\n";
        String next = "element arch-kept type System {\n title: \"Unchanged\";\n}\n" + first.replace("First", "Updated first")
                + "requirementVersion P REQ-1 2 {\n text: \"Updated text\";\n}\n";
        String result = ArchitectureSemanticPatch.applyProjection(architecture + first, next);
        assertTrue(result.startsWith(architecture), "Unrelated canonical architecture source stays byte-for-byte intact");
        var blocks = ArchitectureSemanticPatch.index(result);
        assertEquals(5, blocks.size());
        assertTrue(blocks.containsKey("projectRequirement:P REQ-1")); assertTrue(blocks.containsKey("projectRequirement:P REQ-2"));
        assertTrue(blocks.containsKey("requirementVersion:P REQ-1 1")); assertTrue(blocks.containsKey("requirementVersion:P REQ-1 2"));
        assertEquals(2, ArchitectureSemanticPatch.between(architecture + first, result).size());
        assertEquals(result, ArchitectureSemanticPatch.applyProjection(result, next));
        assertThrows(ArchitectureDslCommands.CommandProblem.class, () -> ArchitectureSemanticPatch.index(result + first));
    }
}

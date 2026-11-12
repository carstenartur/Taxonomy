package com.taxonomy.dsl.planning;

import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureRelation;
import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitecturePackage;
import com.taxonomy.dsl.command.ArchitectureCommand.RelationKey;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ArchitectureBriefTest {
    private final ArchitectureDslCommands commands = new ArchitectureDslCommands();
    private static final String MODEL = """
            element a type CoreService {
              title: "Register";
            }
            element b type CoreService {
              title: "Registry";
            }
            """;

    @Test void architectureScopeAndSourceEditionSurviveIndependentRevisions() {
        assertFalse(ArchitectureBrief.read(commands.model(MODEL)).present());
        var brief = ArchitectureBrief.store(MODEL, Map.of("objective", "Provide registry services", "stage", "target"));
        var binding = Map.of("identifier", "Commissioning authority", "edition", "2026-01", "section", "4.2",
                "role", "binding", "scope", "architecture", "rule", "exclude-relation-v1", "relation", "DEPENDS_ON");
        var first = ArchitectureBrief.putReference(brief, "decision", binding);
        var second = ArchitectureBrief.putReference(first, "decision", Map.of("identifier", "Commissioning authority",
                "edition", "2026-02", "section", "5", "role", "guidance", "scope", "architecture"));
        var historical = ArchitectureBrief.read(commands.model(first));
        var current = ArchitectureBrief.read(commands.model(second));
        assertEquals(Set.of("DEPENDS_ON"), historical.excludedRelationTypes());
        var source = historical.references().getFirst();
        assertEquals(commands.model(first).getSources().stream().filter(item -> "Commissioning authority".equals(item.getCanonicalIdentifier()))
                .findFirst().orElseThrow().getId(), source.sourceId());
        assertEquals(commands.model(first).getSourceVersions().getFirst().getId(), source.sourceVersionId());
        assertEquals(commands.model(first).getSourceFragments().getFirst().getId(), source.sourceFragmentId());
        assertEquals("2026-01", source.values().get("edition"));
        assertEquals("4.2", historical.references().getFirst().values().get("section"));
        assertEquals(Set.of(), current.excludedRelationTypes());
        assertEquals("2026-02", current.references().getFirst().values().get("edition"));
        assertTrue(commands.model(second).getSourceVersions().stream().anyMatch(v -> "2026-01".equals(v.getVersionLabel())));
        assertEquals(1, ArchitectureBrief.read(commands.model(ArchitectureBrief.store(second,
                Map.of("objective", "Carry forward", "stage", "transition")))).references().size());
        var relation = new CreateArchitectureRelation(new RelationKey("a", "DEPENDS_ON", "b"), "proposed");
        assertDoesNotThrow(() -> commands.apply(second, relation));
        var refused = assertThrows(ArchitectureDslCommands.CommandProblem.class, () -> commands.apply(first, relation));
        assertEquals("ARCHITECTURE_BRIEF_RULE", refused.code());
        assertFalse(ArchitectureBrief.removeReference(first, "decision").contains("x-brief.ref.decision"));
    }

    @Test void unversionedCitationAndUnknownSchemaRemainInspectable() {
        var created = ArchitectureBrief.store(MODEL, Map.of("objective", "Document interfaces", "context", "european"));
        var cited = ArchitectureBrief.putReference(created, "design", Map.of(
                "identifier", "Interoperability guide", "section", "Services", "role", "open",
                "scope", "deliverable", "appliesTo", "Exchange view", "derivation", "Edition to be confirmed"));
        var reference = ArchitectureBrief.read(commands.model(cited)).references().getFirst();
        assertEquals("Services", reference.values().get("section"));
        assertFalse(reference.values().containsKey("edition"));
        assertTrue(commands.model(cited).getSourceVersions().isEmpty());
        var future = cited.replaceFirst("(?m)^(\\s*x-brief\\.version\\s*:\\s*)[^;]+;", "$1 2;");
        assertNotEquals(cited, future);
        var snapshot = ArchitectureBrief.read(commands.model(future));
        assertEquals("UNSUPPORTED_ARCHITECTURE_BRIEF", snapshot.problem());
        assertThrows(IllegalArgumentException.class, snapshot::excludedRelationTypes);
        assertThrows(IllegalArgumentException.class, () -> ArchitectureBrief.store(future, Map.of("objective", "New")));
        var dangling = cited.replaceFirst("source plan-source-[^ ]+", "source different-source");
        assertEquals("INVALID_ARCHITECTURE_BRIEF", ArchitectureBrief.read(commands.model(dangling)).problem());
        var packages = commands.apply(dangling, new CreateArchitecturePackage(
                "registry-documents", Map.of("title", "Registry documents")));
        assertEquals("INVALID_ARCHITECTURE_BRIEF", ArchitectureBrief.read(commands.model(packages.dsl())).problem());
        assertEquals(java.util.List.of("package:registry-documents"), packages.changedIds());
        assertEquals(dangling, commands.inverse(packages.dsl(), dangling, packages.dsl()).dsl());
    }

    @Test void undoRetainsSourcesThatHaveSinceAcquiredOtherBindings() {
        var created = ArchitectureBrief.store(MODEL, Map.of("objective", "Document data flows"));
        var values = Map.of("identifier", "Directive", "edition", "A", "section", "2", "role", "guidance", "scope", "architecture");
        var first = ArchitectureBrief.putReference(created, "a", values);
        assertEquals(created, commands.inverse(first, created, first).dsl());
        var second = ArchitectureBrief.putReference(first, "b", values);
        assertThrows(ArchitectureDslCommands.CommandProblem.class, () -> commands.inverse(second, created, first));
    }

    @Test void preventsAuthorityEscalationAndPreservesForeignSource() {
        var brief = ArchitectureBrief.store(MODEL, Map.of("objective", "Plan services"));
        assertThrows(IllegalArgumentException.class, () -> ArchitectureBrief.putReference(brief, "open",
                Map.of("identifier", "Unresolved directive", "role", "open", "scope", "architecture",
                        "rule", "exclude-relation-v1", "relation", "USES")));
        String historicalSource = "source architecture-brief {\n  type: \"reference\";\n  title: \"Public consultation\";\n}\n";
        var coexist = ArchitectureBrief.store(historicalSource, Map.of("objective", "Plan consultation services"));
        assertTrue(coexist.startsWith(historicalSource));
        assertEquals(2, commands.model(coexist).getSources().size());
        assertEquals("Plan consultation services", ArchitectureBrief.read(commands.model(coexist)).fields().get("objective"));
        assertEquals(coexist, ArchitectureBrief.store(coexist, Map.of("objective", "Plan consultation services")));
        assertThrows(IllegalArgumentException.class, () -> ArchitectureBrief.putReference(brief, "missing",
                Map.of("identifier", "No edition", "role", "binding", "scope", "architecture",
                        "rule", "exclude-relation-v1", "relation", "USES")));
    }
}

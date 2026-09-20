package com.taxonomy.dsl.command;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.mapper.*;
import com.taxonomy.dsl.model.ArchitecturePackage;
import com.taxonomy.dsl.validation.DslValidator;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ArchitecturePackagesTest {
    private final ArchitectureDslCommands commands = new ArchitectureDslCommands();
    private String packages() {
        String dsl = commands.apply("", new CreateArchitecturePackage("a", Map.of("title", "A"))).dsl();
        dsl = commands.apply(dsl, new CreateArchitecturePackage("b", Map.of("title", "B"))).dsl();
        return commands.apply(dsl, new CreateArchitectureElement("e", "System", Map.of("title", "E"))).dsl();
    }
    private PackagePlacement pkg(String id, String parent, int position) { return new PackagePlacement(PackageMemberKind.PACKAGE, id, parent, position); }
    private PackagePlacement el(String id, String parent, int position) { return new PackagePlacement(PackageMemberKind.ELEMENT, id, parent, position); }
    @Test void packagesRoundTripWithoutBecomingElementsAndUndoRename() {
        String dsl = packages();
        var model = commands.model(dsl);
        assertThat(model.getPackages()).extracting(ArchitecturePackage::id).containsExactly("a", "b");
        assertThat(model.allElementIds()).containsExactly("e");
        assertThat(model.getRelations()).isEmpty();
        assertThat(new AstToModelMapper().map(new ModelToAstMapper().toDocument(model, "test")).getPackages()).isEqualTo(model.getPackages());
        String renamed = commands.apply(dsl, new UpdateArchitecturePackage("a", Map.of("title", "Renamed"))).dsl();
        assertThat(commands.inverse(renamed, dsl, renamed).dsl()).isEqualTo(dsl);
    }
    @Test void finalSiblingStatePermitsSwapsAndCrossParentMovesAndExplicitDetach() {
        String original = packages();
        String swapped = commands.apply(original, new SetArchitecturePackagePlacements(List.of(pkg("b", "", 0), pkg("a", "", 1)), Set.of(""))).dsl();
        assertThat(commands.model(swapped).getPackages()).filteredOn(p -> p.id().equals("a")).extracting(ArchitecturePackage::position).containsExactly(1);
        String moved = commands.apply(swapped, new SetArchitecturePackagePlacements(List.of(pkg("a", "", 0), pkg("b", "a", 0), el("e", "a", 1)), Set.of("", "a"))).dsl();
        assertThat(commands.inverse(moved, swapped, moved).dsl()).isEqualTo(swapped);
        assertCode(() -> commands.apply(moved, new DeleteArchitecturePackage("a")), "DEPENDENCIES_EXIST");
        assertCode(() -> commands.apply(moved, new SetArchitecturePackagePlacements(List.of(pkg("b", "a", 0)), Set.of("a"))), "INCOMPLETE_PACKAGE_SCOPE");
        String detached = commands.apply(moved, new SetArchitecturePackagePlacements(List.of(pkg("b", "a", 0), el("e", null, -1)), Set.of("a"))).dsl();
        assertThat(commands.model(detached).findElement("e").orElseThrow().getPackageId()).isNull();
        assertThat(new AstToModelMapper().map(new ModelToAstMapper().toDocument(commands.model(moved), "test")).findElement("e").orElseThrow().getPackageId()).isEqualTo("a");
    }
    @Test void cyclesDuplicatePositionsAndMissingScopesFailAtomically() {
        String dsl = packages();
        assertCode(() -> commands.apply(dsl, new SetArchitecturePackagePlacements(List.of(pkg("a", "b", 0), pkg("b", "a", 0)), Set.of("", "a", "b"))), "PACKAGE_CYCLE");
        assertCode(() -> commands.apply(dsl, new SetArchitecturePackagePlacements(List.of(pkg("a", "", 0), pkg("b", "", 0)), Set.of(""))), "PACKAGE_POSITION");
        assertCode(() -> commands.apply(dsl, new SetArchitecturePackagePlacements(List.of(pkg("a", "b", 0)), Set.of("b"))), "INCOMPLETE_PACKAGE_SCOPE");
        assertThat(commands.model(dsl).getPackages()).allMatch(p -> p.parentId().isEmpty());
    }
    @Test void mappingRequiresRealRequirementAndCannotOverwritePortfolioAnalysis() {
        String dsl = packages();
        assertCode(() -> commands.apply(dsl, new UpsertRequirementMapping("req", "e", "reviewed", Map.of())), "NOT_FOUND");
        String requirement = dsl + "requirement req {\n title: \"R\";\n text: \"Need\";\n}\n";
        String mapped = commands.apply(requirement, new UpsertRequirementMapping("req", "e", "reviewed", Map.of("x-exchange-id", "connector"))).dsl();
        assertThat(commands.model(mapped).getMappings()).hasSize(1);
        assertThat(commands.inverse(mapped, requirement, mapped).dsl()).isEqualTo(requirement);
        assertCode(() -> commands.apply(requirement + "mapping req -> e {\n x-project-key: \"P\";\n source: \"analysis\";\n}\n", new UpsertRequirementMapping("req", "e", "replace", Map.of())), "REQUIREMENT_MAPPING_OWNERSHIP_CONFLICT");
    }
    @Test void malformedPlacementAndDepthAreRejectedAndUnrelatedElementsNeedNoRootEntry() {
        String dsl = packages();
        assertCode(() -> commands.apply(dsl, new UpdateArchitecturePackage("a", Map.of("parent", "b"))), "READ_ONLY_PROPERTY");
        assertCode(() -> commands.apply(dsl, new SetArchitecturePackagePlacements(List.of(pkg("a", null, -1)), Set.of(""))), "PACKAGE_POSITION");
        assertCode(() -> commands.apply(dsl, new SetArchitecturePackagePlacements(List.of(new PackagePlacement(null, "a", "", 0)), Set.of(""))), "INVALID_VALUE");
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 81; i++) deep.append("package p").append(i).append(" {\n title: \"P\";\n parent: \"")
                .append(i == 0 ? "" : "p" + (i - 1)).append("\";\n position: \"0\";\n}\n");
        assertThat(new DslValidator().validate(commands.model(deep.toString())).getErrors()).anyMatch(e -> e.contains("PACKAGE_DEPTH"));
        assertCode(() -> commands.apply(deep.toString(), new SetArchitecturePackagePlacements(List.of(), Set.of())), "PACKAGE_DEPTH");
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ArchitectureDslCommands.CommandProblem.class, p -> assertThat(p.code()).isEqualTo(code));
    }
}

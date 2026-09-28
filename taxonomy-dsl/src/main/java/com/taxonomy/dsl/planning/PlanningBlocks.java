package com.taxonomy.dsl.planning;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Source-preserving canonical block operations shared by the small planning profiles. */
final class PlanningBlocks {
    private PlanningBlocks() {}
    static String identity(String prefix, String value) {
        return prefix + UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
    static BlockAst block(String kind, String id, Map<String, String> values) {
        return new BlockAst(kind, List.of(id), values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new PropertyAst(e.getKey(), e.getValue(), null)).toList(), List.of(), Map.of(), null);
    }
    static String add(String dsl, BlockAst block) {
        BlockAst existing = ArchitectureSemanticPatch.index(dsl).get(ArchitectureSemanticPatch.key(block));
        if (existing != null) {
            for (PropertyAst property : block.getProperties()) if (!property.value().equals(existing.property(property.key())))
                throw new IllegalArgumentException("Conflicting canonical planning source identity: " + ArchitectureSemanticPatch.key(block));
            return dsl;
        }
        return ArchitectureSemanticPatch.replace(dsl, null, block);
    }
}

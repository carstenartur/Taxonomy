package com.taxonomy.composition.backup;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import java.io.IOException;
import java.util.*;

/** Resolves inline provenance against this exact document, never against a newer live record. */
final class PortableDocumentSourceReferences {
    private PortableDocumentSourceReferences() { }

    static void requireClosed(DocumentAst document) throws IOException {
        Map<String, BlockAst> sources = index(document, "source");
        Map<String, BlockAst> versions = index(document, "sourceVersion");
        Map<String, BlockAst> fragments = index(document, "sourceFragment");
        Set<String> requirements = new HashSet<>();
        for (var block : document.getBlocks()) {
            if (block.getKind().equals("requirement") && block.getHeaderTokens().size() == 1)
                requirements.add(block.getHeaderTokens().getFirst());
        }
        for (var version : versions.values()) require(sources, version.property("source"));
        for (var fragment : fragments.values()) {
            require(versions, fragment.property("sourceVersion"));
            String parent = fragment.property("parentFragment");
            if (parent != null && !Objects.equals(require(fragments, parent).property("sourceVersion"), fragment.property("sourceVersion")))
                throw new IOException("Inconsistent document source fragment ancestry");
        }
        // Iterative walk bounds stack usage and verifies cycles once for the entire document.
        Set<String> resolved = new HashSet<>();
        for (String start : fragments.keySet()) {
            Set<String> path = new HashSet<>();
            String id = start;
            while (id != null && !resolved.contains(id)) {
                if (!path.add(id)) throw new IOException("Cyclic document source fragment ancestry");
                id = require(fragments, id).property("parentFragment");
            }
            resolved.addAll(path);
        }
        for (var block : document.getBlocks()) if (block.getKind().equals("requirementSourceLink")) {
            if (!requirements.contains(block.property("requirement"))) throw new IOException("Missing document requirement reference");
            String source = block.property("source"), version = block.property("sourceVersion");
            require(sources, source);
            String fragment = block.property("sourceFragment");
            if (fragment != null) {
                String fragmentVersion = require(fragments, fragment).property("sourceVersion");
                if (version != null && !version.equals(fragmentVersion)) throw new IOException("Inconsistent document fragment version reference");
                version = fragmentVersion;
            }
            if (version != null && !source.equals(require(versions, version).property("source")))
                throw new IOException("Inconsistent document source version reference");
        }
    }

    private static Map<String, BlockAst> index(DocumentAst document, String kind) throws IOException {
        Map<String, BlockAst> blocks = new HashMap<>();
        for (var block : document.getBlocks()) if (block.getKind().equals(kind)) {
            if (block.getHeaderTokens().size() != 1 || blocks.putIfAbsent(block.getHeaderTokens().getFirst(), block) != null)
                throw new IOException("Invalid or duplicate document source identity");
        }
        return blocks;
    }

    private static BlockAst require(Map<String, BlockAst> blocks, String id) throws IOException {
        BlockAst block = id == null ? null : blocks.get(id);
        if (block == null) throw new IOException("Missing document source reference");
        return block;
    }
}

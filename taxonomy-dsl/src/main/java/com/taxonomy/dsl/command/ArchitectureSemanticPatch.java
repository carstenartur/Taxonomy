package com.taxonomy.dsl.command;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A reconstructible semantic patch between two immutable Git documents.
 * Only changed blocks are replaced. Unrelated text, ordering and comments remain byte-for-byte intact.
 * Applying an inverse requires the affected blocks to still match the accepted after-state.
 */
public final class ArchitectureSemanticPatch {
    private static final TaxDslParser PARSER = new TaxDslParser();
    private static final TaxDslSerializer SERIALIZER = new TaxDslSerializer();

    private ArchitectureSemanticPatch() {}

    public record BlockChange(String id, String before, String after) {}

    public static List<BlockChange> between(String before, String after) {
        Map<String, BlockAst> oldBlocks = index(before);
        Map<String, BlockAst> newBlocks = index(after);
        var ids = new LinkedHashSet<>(oldBlocks.keySet());
        ids.addAll(newBlocks.keySet());
        List<BlockChange> changes = new ArrayList<>();
        for (String id : ids) {
            BlockAst oldBlock = oldBlocks.get(id);
            BlockAst newBlock = newBlocks.get(id);
            if (!Objects.equals(canonical(oldBlock), canonical(newBlock))) {
                changes.add(new BlockChange(id, raw(before, oldBlock), raw(after, newBlock)));
            }
        }
        return List.copyOf(changes);
    }

    public static String inverse(String current, List<BlockChange> changes) {
        Map<String, BlockAst> currentBlocks = index(current);
        for (BlockChange change : changes) {
            String actual = raw(current, currentBlocks.get(change.id()));
            String expected = change.after();
            if (!Objects.equals(actual, expected)) {
                throw new ArchitectureDslCommands.CommandProblem("UNDO_CONFLICT", change.id(),
                        "The affected object changed after the target command", List.of(change.id()));
            }
        }
        String result = current;
        for (BlockChange change : changes) {
            result = replaceRaw(result, index(result).get(change.id()), change.before());
        }
        return result;
    }

    public static Map<String, BlockAst> index(String source) {
        Map<String, BlockAst> result = new LinkedHashMap<>();
        for (BlockAst block : PARSER.parse(source, "architecture.taxdsl").getBlocks()) {
            String id = key(block);
            if (result.putIfAbsent(id, block) != null) {
                throw new ArchitectureDslCommands.CommandProblem("DUPLICATE_ID", id,
                        "Ambiguous duplicate block identity", List.of(id));
            }
        }
        return result;
    }

    public static String key(BlockAst block) {
        List<String> tokens = block.getHeaderTokens();
        if (tokens.isEmpty()) {
            throw new ArchitectureDslCommands.CommandProblem("INVALID_HEADER", block.getKind(),
                    "Block identity is missing", List.of());
        }
        return block.getKind() + ":" + ("relation".equals(block.getKind()) || "mapping".equals(block.getKind())
                ? String.join(" ", tokens) : tokens.getFirst());
    }

    public static String replace(String source, BlockAst existing, BlockAst replacement) {
        String next = canonical(replacement);
        if (Objects.equals(canonical(existing), next)) return source;
        if (existing != null && replacement != null) {
            // The formatter does not model comments. Retain them explicitly inside the edited block.
            String comments = raw(source, existing).lines().filter(line -> line.strip().startsWith("#"))
                    .reduce("", (a, b) -> a + b + "\n");
            int opening = next.indexOf('\n') + 1;
            next = next.substring(0, opening) + comments + next.substring(opening);
        }
        return replaceRaw(source, existing, next);
    }

    private static String replaceRaw(String source, BlockAst existing, String replacement) {
        if (existing == null) {
            if (replacement == null) return source;
            return source + (source.isEmpty() || source.endsWith("\n") ? "" : "\n") + replacement;
        }
        int[] range = range(source, existing);
        return source.substring(0, range[0]) + (replacement == null ? "" : replacement)
                + source.substring(range[1]);
    }

    private static String raw(String source, BlockAst block) {
        if (block == null) return null;
        int[] range = range(source, block);
        return source.substring(range[0], range[1]);
    }

    private static int[] range(String source, BlockAst block) {
        int start = 0;
        for (int line = 1; line < block.getSourceLocation().line(); line++) {
            start = source.indexOf('\n', start) + 1;
        }
        int depth = 0;
        for (int cursor = start; cursor < source.length();) {
            int newline = source.indexOf('\n', cursor);
            int end = newline < 0 ? source.length() : newline + 1;
            String line = source.substring(cursor, end).strip();
            if (!line.startsWith("#")) {
                if (line.endsWith("{")) depth++;
                if (line.startsWith("}")) depth--;
                if (depth == 0) return new int[]{start, end};
            }
            cursor = end;
        }
        throw new ArchitectureDslCommands.CommandProblem("INVALID_DOCUMENT", key(block),
                "Block has no closing delimiter", List.of(key(block)));
    }

    private static String canonical(BlockAst block) {
        return block == null ? null : SERIALIZER.serialize(new DocumentAst(null, List.of(block)));
    }
}

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
            String comments = raw(source, existing).lines().map(ArchitectureSemanticPatch::comment).filter(Objects::nonNull)
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
            start = lineEnd(source, start);
        }
        int depth = 0;
        for (int cursor = start; cursor < source.length();) {
            int end = lineEnd(source, cursor);
            String line = source.substring(cursor, end).strip();
            if (!line.startsWith("#")) {
                if (line.endsWith("{")) depth++;
                if (depth > 1) throw new ArchitectureDslCommands.CommandProblem("INVALID_DOCUMENT", key(block),
                        "Nested blocks are not supported on an edited object", List.of(key(block)));
                if (line.startsWith("}")) depth--;
                if (depth == 0) return new int[]{start, end};
            }
            cursor = end;
        }
        throw new ArchitectureDslCommands.CommandProblem("INVALID_DOCUMENT", key(block),
                "Block has no closing delimiter", List.of(key(block)));
    }

    private static int lineEnd(String source, int start) {
        for (int cursor = start; cursor < source.length(); cursor++) {
            char value = source.charAt(cursor);
            if (value == '\n') return cursor + 1;
            if (value == '\r') return cursor + 1 < source.length() && source.charAt(cursor + 1) == '\n' ? cursor + 2 : cursor + 1;
        }
        return source.length();
    }

    /** Retain both full-line and trailing comments without treating a quoted hash as a comment. */
    private static String comment(String line) {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (escaped) { escaped = false; continue; }
            if (quoted && value == '\\') { escaped = true; continue; }
            if (value == '"') quoted = !quoted;
            if (!quoted && value == '#') return "  " + line.substring(index);
        }
        return null;
    }

    private static String canonical(BlockAst block) {
        return block == null ? null : SERIALIZER.serialize(new DocumentAst(null, List.of(block)));
    }
}

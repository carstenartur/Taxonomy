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
 * Only changed blocks are replaced; unrelated source remains byte-for-byte intact.
 * Comments in edited blocks are retained, but their placement, indentation and line endings may change.
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

    public static String inverse(String current, String original, List<BlockChange> changes) {
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
            BlockAst existing = index(result).get(change.id());
            if (existing == null && change.before() != null) {
                int insertion = restorationOffset(result, original, change.id());
                result = result.substring(0, insertion) + change.before() + result.substring(insertion);
            } else {
                result = replaceRaw(result, existing, change.before());
            }
        }
        return result;
    }

    /** Restore beside an original neighbor, preserving intervening source and later unrelated block edits. */
    private static int restorationOffset(String current, String original, String id) {
        Map<String, BlockAst> oldBlocks = index(original);
        List<String> order = new ArrayList<>(oldBlocks.keySet());
        int position = order.indexOf(id);
        int[] target = range(original, oldBlocks.get(id));
        Map<String, BlockAst> live = index(current);
        String previous = position == 0 ? null : order.get(position - 1);
        String next = position + 1 < order.size() ? order.get(position + 1) : null;
        int oldLeft = previous == null ? 0 : range(original, oldBlocks.get(previous))[1];
        String leftGap = original.substring(oldLeft, target[0]);
        int liveLeft = previous == null ? 0 : live.containsKey(previous) ? range(current, live.get(previous))[1] : -1;
        int liveRight = next != null && live.containsKey(next) ? range(current, live.get(next))[0] : current.length();
        if (liveLeft >= 0 && liveLeft + leftGap.length() <= liveRight && current.startsWith(leftGap, liveLeft)) {
            return liveLeft + leftGap.length();
        }

        if (next != null && live.containsKey(next)) {
            String rightGap = original.substring(target[1], range(original, oldBlocks.get(next))[0]);
            int insertion = liveRight - rightGap.length();
            if (insertion >= Math.max(0, liveLeft) && current.startsWith(rightGap, insertion)) return insertion;
        }
        throw new ArchitectureDslCommands.CommandProblem("UNDO_CONFLICT", id,
                "The original insertion boundary changed; choose a new explicit placement", List.of(id));
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
            StringBuilder comments = new StringBuilder();
            raw(source, existing).lines().map(ArchitectureSemanticPatch::comment).filter(Objects::nonNull)
                    .forEach(comment -> comments.append(comment).append('\n'));
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
            int delimiter = TaxDslParser.blockDelimiter(source.substring(cursor, end));
            depth += delimiter;
            if (depth > 1) throw new ArchitectureDslCommands.CommandProblem("INVALID_DOCUMENT", key(block),
                    "Nested blocks are not supported on an edited object", List.of(key(block)));
            if (depth == 0 && delimiter < 0) return new int[]{start, end};
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

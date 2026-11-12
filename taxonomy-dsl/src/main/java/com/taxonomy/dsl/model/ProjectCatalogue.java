package com.taxonomy.dsl.model;

import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.ast.MetaAst;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Catalogue evidence belongs to the project source and therefore travels with its Git history. */
public final class ProjectCatalogue {
    private ProjectCatalogue() {}

    public static String initial(String namespace, UUID generation) {
        var meta = new MetaAst(MetaAst.LANGUAGE_ID, MetaAst.CURRENT_VERSION, namespace, generation.toString(), null, null);
        return new TaxDslSerializer().serialize(new DocumentAst(meta, List.of()));
    }

    /** Recovery verifies the original seed, independently of a subsequent catalogue import. */
    public static boolean isInitial(String source, String namespace) {
        if (source == null) return false;
        var meta = new TaxDslParser().parse(source).getMeta();
        if (meta == null || meta.catalogueRevision() == null) return false;
        try {
            return initial(namespace, UUID.fromString(meta.catalogueRevision())).equals(source);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Explicit draft operation. Keep all user blocks, comments and line endings outside the edited property. */
    public static String compareWith(String source, UUID generation) {
        var meta = new TaxDslParser().parse(source).getMeta();
        String lineEnding = source.contains("\r\n") ? "\r\n" : source.contains("\r") ? "\r" : "\n";
        String value = "  comparisonCatalogueRevision: \"" + generation + "\";";
        if (meta == null) return "meta {" + lineEnding + value + lineEnding + "}" + lineEnding + source;
        var lines = Pattern.compile("([^\r\n]*)(\r\n|\n|\r|$)").matcher(source);
        int number = 0, insertAt = -1;
        while (lines.find()) {
            number++;
            if (number <= meta.sourceLocation().line()) continue;
            String line = TaxDslParser.withoutComment(lines.group(1)).strip();
            if (TaxDslParser.blockDelimiter(line) < 0) {
                insertAt = lines.start();
                break;
            }
            if (line.matches("comparisonCatalogueRevision\\s*:.*")) {
                int comment = TaxDslParser.withoutComment(lines.group(1)).length();
                return source.substring(0, lines.start()) + value + lines.group(1).substring(comment) + source.substring(lines.end(1));
            }
        }
        if (insertAt < 0) throw new IllegalArgumentException("The project meta block is incomplete");
        return source.substring(0, insertAt) + value + lineEnding + source.substring(insertAt);
    }
}

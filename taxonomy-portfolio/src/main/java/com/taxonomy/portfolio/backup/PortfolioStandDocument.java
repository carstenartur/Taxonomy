package com.taxonomy.portfolio.backup;

import com.taxonomy.dsl.ast.*;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import java.util.*;

/** A Git tree is not necessarily history-free: the canonical portfolio embeds earlier versions and adoption ancestry. */
public final class PortfolioStandDocument {
    private PortfolioStandDocument() { }
    public static String project(String dsl) {
        requireSupportedSyntax(dsl);
        var document=new TaxDslParser().parse(dsl,"portable-stand.taxdsl");
        var active=new HashMap<RequirementKey,String>();
        var versions=new HashMap<RequirementKey,Set<String>>();
        for (var block:document.getBlocks()) {
            if (portfolioRequirement(block)) {
                var key=key(block,2);
                if (active.containsKey(key)) throw new IllegalArgumentException("Duplicate requirement in stand document");
                active.put(key,block.property("currentVersionNumber"));
            } else if (block.getKind().equals("requirementVersion")) {
                var key=key(block,3);
                if (!versions.computeIfAbsent(key,k->new HashSet<>()).add(block.getHeaderTokens().get(2)))
                    throw new IllegalArgumentException("Duplicate requirement version in stand document");
            }
        }
        for (var item:versions.entrySet()) {
            if (!active.containsKey(item.getKey())) throw new IllegalArgumentException("Requirement version has no parent");
            String selected=active.get(item.getKey());
            if (selected==null && item.getValue().size()==1) { selected=item.getValue().iterator().next(); active.put(item.getKey(),selected); }
            if (selected==null || !item.getValue().contains(selected)) throw new IllegalArgumentException("Stand document requires an unambiguous current requirement version");
        }
        var blocks=new ArrayList<BlockAst>();
        for (var block:document.getBlocks()) {
            if (block.getKind().equals("reformulationEvidence")) continue;
            if (block.getKind().equals("requirementVersion") && !block.getHeaderTokens().get(2).equals(active.get(key(block,3)))) continue;
            var properties=new ArrayList<PropertyAst>();
            for (var property:block.getProperties()) {
                if (block.getKind().equals("requirementVersion") && Set.of("originalText","changeReason").contains(property.key())) continue;
                if (portfolioRequirement(block) && Set.of("currentVersionId","currentVersionNumber").contains(property.key())) continue;
                properties.add(property);
            }
            if (portfolioRequirement(block) && active.get(key(block,2))!=null)
                properties.add(new PropertyAst("currentVersionNumber",active.get(key(block,2)),block.getSourceLocation()));
            rejectNestedHistory(block.getChildren());
            blocks.add(new BlockAst(block.getKind(),block.getHeaderTokens(),properties,block.getChildren(),block.getExtensions(),block.getSourceLocation()));
        }
        return new TaxDslSerializer().serialize(new DocumentAst(document.getMeta(),blocks));
    }
    private static void rejectNestedHistory(List<BlockAst> blocks) {
        for (var child:blocks) {
            if (Set.of("requirementVersion","reformulationEvidence").contains(child.getKind()))
                throw new IllegalArgumentException("Nested portfolio history is not a supported stand document");
            rejectNestedHistory(child.getChildren());
        }
    }
    private static void requireSupportedSyntax(String source) {
        if (source == null) throw new IllegalArgumentException("Missing stand document");
        var property = java.util.regex.Pattern.compile("[A-Za-z_][\\w.-]*\\s*:\\s*(?:\"(?:[^\"\\\\]|\\\\.)*+\"|[^\";]*)\\s*;");
        boolean inside = false;
        var keys = new HashSet<String>();
        for (String original : source.lines().toList()) {
            String line = TaxDslParser.withoutComment(original).strip();
            if (line.isBlank() || line.startsWith("//")) continue;
            if (!inside && TaxDslParser.blockDelimiter(line) == 1) { inside = true; keys.clear(); continue; }
            if (inside && line.equals("}")) { inside = false; continue; }
            if (!inside || !property.matcher(line).matches())
                throw new IllegalArgumentException("Stand projection requires complete line-oriented TaxDSL syntax");
            if (!keys.add(line.substring(0,line.indexOf(':')).strip()))
                throw new IllegalArgumentException("Duplicate property in stand document");
        }
        if (inside) throw new IllegalArgumentException("Unclosed block in stand document");
    }
    private static RequirementKey key(BlockAst block,int size) {
        if (block.getHeaderTokens().size()!=size) throw new IllegalArgumentException("Invalid portfolio identity in stand document");
        return new RequirementKey(block.getHeaderTokens().get(0),block.getHeaderTokens().get(1));
    }
    private static boolean portfolioRequirement(BlockAst block) {
        // A one-key architecture requirement is a different DSL type from project + requirement.
        return block.getKind().equals("projectRequirement")
                || (block.getKind().equals("requirement") && block.getHeaderTokens().size()!=1);
    }
    private record RequirementKey(String project,String requirement) { }
}

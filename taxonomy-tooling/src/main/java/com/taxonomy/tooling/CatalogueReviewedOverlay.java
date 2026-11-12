package com.taxonomy.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Preflight an administrative review against the checkout receiving the eventual Git change. */
final class CatalogueReviewedOverlay {
    private CatalogueReviewedOverlay() { }

    private static boolean equivalent(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b)
            return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0;
        if (left instanceof Map<?,?> a && right instanceof Map<?,?> b)
            return a.keySet().equals(b.keySet()) && a.keySet().stream().allMatch(key -> equivalent(a.get(key), b.get(key)));
        if (left instanceof List<?> a && right instanceof List<?> b)
            return a.size() == b.size() && java.util.stream.IntStream.range(0, a.size()).allMatch(index -> equivalent(a.get(index), b.get(index)));
        return Objects.equals(left, right);
    }

    static String verify(Path workbook, Path current, Path review, Path candidate) throws IOException {
        var entry = FlatJson.parseObject(Files.readString(review));
        if (!(entry.get("commit") instanceof String commit) || !commit.matches("[0-9a-f]{40}")
                || !(entry.get("decision") instanceof Map<?,?> decision)
                || !("ACCEPT".equals(decision.get("action")) || "CHANGE".equals(decision.get("action"))))
            throw new IllegalArgumentException("A recorded acceptance or change is required");
        if (!CatalogueOverlayProposalGenerator.sha256(workbook).equals(decision.get("workbookSha256"))
                || !CatalogueOverlayProposalGenerator.sha256(current).equals(decision.get("overlaySha256"))
                || !CatalogueOverlayProposalGenerator.sha256(candidate).equals(decision.get("proposedSha256")))
            throw new IllegalArgumentException("The workbook, checked-in overlay or candidate differs from the review");
        var base = FlatJson.parseObject(Files.readString(current));
        var updated = FlatJson.parseObject(Files.readString(candidate));
        String code = decision.get("code") instanceof String value ? value : "";
        if (!Objects.equals(base.get("mappingVersion"), decision.get("mappingVersion"))
                || !Objects.equals(updated.get("mappingVersion"), decision.get("proposedVersion"))
                || Objects.equals(base.get("mappingVersion"), updated.get("mappingVersion"))
                || !(base.get("nodePatches") instanceof List<?> before)
                || !(updated.get("nodePatches") instanceof List<?> after) || before.size() != after.size())
            throw new IllegalArgumentException("The candidate does not preserve its reviewed mapping baseline");
        var expected = new java.util.LinkedHashMap<>(base);
        expected.put("mappingVersion", updated.get("mappingVersion"));
        var patches = new java.util.ArrayList<Object>(before);
        int changed = 0;
        for (int i = 0; i < before.size(); i++) {
            if (!(before.get(i) instanceof Map<?,?> original) || !code.equals(original.get("code"))) continue;
            if (!Boolean.TRUE.equals(original.get("reviewRequired")) || !(after.get(i) instanceof Map<?,?> replacement))
                throw new IllegalArgumentException("The recorded product was not provisional");
            if ("ACCEPT".equals(decision.get("action")) && (!Objects.equals(original.get("parentCode"), decision.get("proposedParent"))
                    || !Objects.equals(original.getOrDefault("secondaryClassificationCodes", null), decision.get("secondary"))))
                throw new IllegalArgumentException("Acceptance cannot replace a product's classifications");
            var edit = new java.util.LinkedHashMap<Object,Object>(original);
            edit.put("parentCode", decision.get("proposedParent"));
            edit.put("secondaryClassificationCodes", decision.get("secondary"));
            edit.put("justification", decision.get("rationale"));
            edit.put("reviewRequired", false);
            if (!equivalent(edit, replacement)) throw new IllegalArgumentException("Candidate differs from the reviewed decision");
            patches.set(i, edit); changed++;
        }
        expected.put("nodePatches", patches);
        if (changed != 1 || !equivalent(expected, updated)) throw new IllegalArgumentException("Unreviewed catalogue changes in candidate");
        var overlay = CatalogueOverlayProposalInputs.readOverlay(candidate, workbook.getFileName().toString());
        var source = CatalogueOverlayProposalInputs.readSourceCatalogue(workbook, overlay.strictRoot());
        CatalogueOverlayProposalInputs.validateOverlayAgainstSource(overlay, source);
        var parents = CatalogueOverlayProposalInputs.effectiveParents(source, overlay);
        CatalogueOverlayProposalValidator.validateHierarchy(parents, source.nodes(), overlay.strictRoot(), "review candidate");
        CatalogueOverlayProposalValidator.validateOverlayProductLeaves(overlay.patches(), parents, "review candidate");
        return commit;
    }
}

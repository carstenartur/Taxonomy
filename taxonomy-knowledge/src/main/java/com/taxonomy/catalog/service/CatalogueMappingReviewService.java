package com.taxonomy.catalog.service;

import com.taxonomy.catalog.provenance.CatalogueSourceBytes;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.CatalogueMappingReviewHistory.Action;
import com.taxonomy.catalog.service.CatalogueMappingReviewHistory.Decision;
import com.taxonomy.identity.StableIdentityHash;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Service
public class CatalogueMappingReviewService {
    public record Command(String catalogueRevision, String mappingVersion, String overlaySha256, String expectedHead,
                          Action action, String proposedParent, List<String> secondary, String rationale) { }
    private record Proposal(String version, String sha256, byte[] bytes) { }
    private final CatalogueSourceJournal sources;
    private final TaxonomyService taxonomy;
    private final CatalogueOverlayService overlays;
    private final CatalogueMappingReviewHistory journal;
    private final ObjectMapper mapper;

    public CatalogueMappingReviewService(CatalogueSourceJournal sources, TaxonomyService taxonomy,
            CatalogueOverlayService overlays, CatalogueMappingReviewHistory journal, ObjectMapper mapper) {
        this.sources = sources; this.taxonomy = taxonomy; this.overlays = overlays; this.journal = journal; this.mapper = mapper;
    }

    @Transactional
    public CatalogueMappingReviewHistory.Entry review(String code, Command command, String reviewer) throws IOException {
        if (command == null || command.action() == null || command.rationale() == null || command.rationale().isBlank()
                || command.rationale().length() > 2000 || code == null || code.isBlank() || code.length() > 160)
            throw invalid("A valid product, review decision and rationale are required");
        var generation = sources.lockForCapture();
        var queue = taxonomy.provisionalInformationProducts(code);
        if (!generation.id().equals(command.catalogueRevision()) || !generation.id().equals(queue.catalogueRevision())
                || queue.mappingVersion() == null || !queue.mappingVersion().equals(command.mappingVersion())
                || queue.overlaySha256() == null || !queue.overlaySha256().equals(command.overlaySha256()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The applied mapping version changed");
        var original = queue.mappings().stream().filter(mapping -> mapping.productCode().equals(code)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provisional product mapping not found"));
        boolean change = command.action() == Action.CHANGE;
        if (!change && (command.proposedParent() != null || command.secondary() != null))
            throw invalid("A replacement parent or secondary classification belongs to a change decision");
        String parent = change ? command.proposedParent() : original.proposedParentCode();
        List<String> secondary = change ? command.secondary() : original.secondaryClassificationCodes();
        if (parent == null || parent.isBlank() || parent.length() > 160 || secondary == null || secondary.size() > 256
                || secondary.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 160)
                || secondary.stream().distinct().count() != secondary.size()) throw invalid("Invalid replacement mapping");
        Proposal proposal = command.action() == Action.REJECT ? null
                : prepare(generation, original, parent, secondary, command.rationale().strip(), command.expectedHead());
        var record = new Decision(code, generation.id(), queue.mappingVersion(), queue.workbookSha256(), queue.overlaySha256(),
                original, command.action(), parent, List.copyOf(secondary), command.rationale().strip(), reviewer, Instant.now(),
                proposal == null ? null : proposal.version(), proposal == null ? null : proposal.sha256());
        return journal.append(command.expectedHead(), record, proposal == null ? null : proposal.bytes());
    }

    private Proposal prepare(CatalogueSourceJournal.Snapshot generation, TaxonomyService.ProvisionalProductMapping mapping,
                             String parent, List<String> secondary, String rationale, String expectedHead) throws IOException {
        if (generation.workbook().use() != CatalogueSourceJournal.Use.APPLIED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The original workbook is unavailable for proposal validation");
        var original = sources.retainedInput(generation.overlay());
        try (var input = original.openStream()) {
            if (!(mapper.readTree(input) instanceof ObjectNode candidate)) throw new IOException("Retained overlay is invalid");
            ObjectNode patch = null;
            for (var item : candidate.path("nodePatches")) {
                if (mapping.productCode().equals(item.path("code").asString("")) && item instanceof ObjectNode object) {
                    if (patch != null) throw new IOException("Ambiguous retained product mapping");
                    patch = object;
                }
            }
            if (patch == null) throw new IOException("Provisional mapping is absent from its overlay");
            patch.put("parentCode", parent);
            patch.set("secondaryClassificationCodes", mapper.valueToTree(secondary));
            patch.put("justification", rationale);
            patch.put("reviewRequired", false);
            String version = "review-" + StableIdentityHash.sha256(mapper.writeValueAsString(List.of(generation.id(),
                    expectedHead == null ? "" : expectedHead, mapping.productCode(), parent, secondary, rationale))).substring(0, 24);
            candidate.put("mappingVersion", version);
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(candidate);
            var captured = CatalogueSourceBytes.read(new ByteArrayInputStream(bytes));
            try (var workbook = sources.retainedWorkbook(generation.workbook()).openStream(); var excel = new XSSFWorkbook(workbook)) {
                var hierarchy = TaxonomyService.readWorkbookNodes(excel);
                try {
                    overlays.applyRetainedHierarchy(hierarchy.nodes(), hierarchy.uuidToCode(), captured);
                } catch (IllegalStateException | IllegalArgumentException invalid) {
                    throw invalid("The proposal violates the catalogue hierarchy or its source constraints");
                }
            }
            return new Proposal(version, captured.sha256(), bytes);
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

package com.taxonomy.portfolio.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.shared.service.PromptTemplateService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;

/** Creates stable fingerprints needed to interpret and reproduce analysis snapshots. */
@Service
public class PortfolioFingerprintService {

    private final TaxonomyNodeRepository nodeRepository;
    private final PromptTemplateService promptTemplateService;

    public PortfolioFingerprintService(TaxonomyNodeRepository nodeRepository,
                                       PromptTemplateService promptTemplateService) {
        this.nodeRepository = nodeRepository;
        this.promptTemplateService = promptTemplateService;
    }

    public String taxonomyFingerprint() {
        StringBuilder canonical = new StringBuilder();
        nodeRepository.findAll().stream()
                .sorted(Comparator.comparing(TaxonomyNode::getCode))
                .forEach(node -> canonical
                        .append(safe(node.getCode())).append('\u001f')
                        .append(safe(node.getNameEn())).append('\u001f')
                        .append(safe(node.getDescriptionEn())).append('\u001f')
                        .append(safe(node.getTaxonomyRoot())).append('\u001f')
                        .append(node.getLevel()).append('\n'));
        return sha256(canonical.toString());
    }

    public String promptFingerprint() {
        StringBuilder canonical = new StringBuilder();
        promptTemplateService.getAllTemplateCodes().stream()
                .sorted()
                .forEach(code -> canonical
                        .append(code).append('\u001f')
                        .append(promptTemplateService.getTemplate(code))
                        .append('\n'));
        return sha256(canonical.toString());
    }

    public String contentFingerprint(String content) {
        return sha256(content != null ? content : "");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}

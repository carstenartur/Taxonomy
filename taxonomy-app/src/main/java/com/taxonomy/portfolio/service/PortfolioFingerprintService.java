package com.taxonomy.portfolio.service;

import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.TaxonomyDataFingerprint;
import com.taxonomy.shared.service.PromptTemplateService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Creates stable fingerprints needed to interpret and reproduce analysis snapshots. */
@Service
public class PortfolioFingerprintService {

    private final TaxonomyService taxonomyService;
    private final PromptTemplateService promptTemplateService;

    public PortfolioFingerprintService(TaxonomyService taxonomyService,
                                       PromptTemplateService promptTemplateService) {
        this.taxonomyService = taxonomyService;
        this.promptTemplateService = promptTemplateService;
    }

    /**
     * Fingerprints the same frozen DTO representation that is persisted with an analysis.
     * Parent identity and analysis role are therefore part of the reproducibility contract.
     */
    public String taxonomyFingerprint() {
        return TaxonomyDataFingerprint.sha256(taxonomyService.getFullTree());
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
}

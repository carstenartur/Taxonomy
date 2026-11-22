package com.taxonomy.architecture.decision;

import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.TaxonomyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supplies stable catalogue identity for report provenance.
 *
 * <p>The human-readable version is derived from the bundled catalogue filename.
 * The effective SHA-256 digest combines the exact Excel bytes, the versioned JSON
 * overlay bytes and its mapping version, so provenance changes whenever either
 * catalogue layer changes.</p>
 */
@Service
public class TaxonomyCatalogueMetadataService {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyCatalogueMetadataService.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("([0-9]{1,2}[A-Z]{3}[0-9]{4})");

    private final ResourceLoader resourceLoader;
    private final CatalogueOverlayService overlayService;
    private final String catalogueResource;
    private final TaxonomyService taxonomy;

    public TaxonomyCatalogueMetadataService(
            ResourceLoader resourceLoader,
            TaxonomyService taxonomy,
            CatalogueOverlayService overlayService,
            @Value("${taxonomy.catalogue.resource:classpath:data/bilingual/C3_Taxonomy_Catalogue_25AUG2025.xlsx}")
            String catalogueResource) {
        this.resourceLoader = resourceLoader;
        this.taxonomy = taxonomy;
        this.overlayService = overlayService;
        this.catalogueResource = catalogueResource;
    }

    public CatalogueMetadata getMetadata() {
        Resource resource = resourceLoader.getResource(catalogueResource);
        String filename = resource.getFilename() != null
                ? resource.getFilename() : catalogueResource;
        String version = extractVersion(filename);
        String baseSha256 = taxonomy.appliedWorkbookSha256().orElse(null);

        CatalogueOverlayService.OverlayMetadata overlay = overlayService.getOverlayMetadata();
        String overlaySha256 = overlay.sha256() != null ? overlay.sha256() : "disabled";
        String effectiveSha256 = baseSha256 == null ? "unavailable" : compositeDigest(
                baseSha256, overlaySha256, overlay.mappingVersion());
        return new CatalogueMetadata(
                filename,
                version,
                effectiveSha256,
                overlay.enabled()
                        ? "C3 Taxonomy Catalogue + versioned JSON overlay"
                        : "C3 Taxonomy Catalogue",
                baseSha256,
                overlay.resource(),
                overlay.mappingVersion(),
                overlay.sha256());
    }

    private String compositeDigest(String baseSha256, String overlaySha256, String mappingVersion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = baseSha256 + ":" + overlaySha256 + ":"
                    + (mappingVersion != null ? mappingVersion : "unspecified");
            return HexFormat.of().formatHex(
                    digest.digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            log.warn("Could not compute effective catalogue digest: {}", exception.getMessage());
            return baseSha256;
        }
    }

    private String extractVersion(String filename) {
        Matcher matcher = VERSION_PATTERN.matcher(filename.toUpperCase(Locale.ROOT));
        return matcher.find() ? matcher.group(1) : "unspecified";
    }

    public record CatalogueMetadata(
            String filename,
            String version,
            String sha256,
            String source,
            String baseSha256,
            String overlayResource,
            String overlayMappingVersion,
            String overlaySha256) {
    }
}

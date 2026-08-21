package com.taxonomy.architecture.decision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supplies stable catalogue identity for report provenance.
 *
 * <p>The human-readable version is derived from the bundled catalogue filename,
 * while the SHA-256 digest identifies the exact bytes used by this application
 * build. The digest therefore remains useful even when two files carry the same
 * nominal catalogue version.</p>
 */
@Service
public class TaxonomyCatalogueMetadataService {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyCatalogueMetadataService.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("([0-9]{1,2}[A-Z]{3}[0-9]{4})");

    private final ResourceLoader resourceLoader;
    private final String catalogueResource;
    private volatile CatalogueMetadata cached;

    public TaxonomyCatalogueMetadataService(
            ResourceLoader resourceLoader,
            @Value("${taxonomy.catalogue.resource:classpath:data/C3_Taxonomy_Catalogue_25AUG2025.xlsx}")
            String catalogueResource) {
        this.resourceLoader = resourceLoader;
        this.catalogueResource = catalogueResource;
    }

    public CatalogueMetadata getMetadata() {
        CatalogueMetadata current = cached;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cached == null) {
                cached = loadMetadata();
            }
            return cached;
        }
    }

    private CatalogueMetadata loadMetadata() {
        Resource resource = resourceLoader.getResource(catalogueResource);
        String filename = resource.getFilename() != null
                ? resource.getFilename() : catalogueResource;
        String version = extractVersion(filename);
        String sha256 = "unavailable";

        try (InputStream input = resource.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            sha256 = HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            log.warn("Could not compute taxonomy catalogue digest for '{}': {}",
                    catalogueResource, exception.getMessage());
        }

        return new CatalogueMetadata(
                filename,
                version,
                sha256,
                "C3 Taxonomy Catalogue");
    }

    private String extractVersion(String filename) {
        Matcher matcher = VERSION_PATTERN.matcher(filename.toUpperCase(Locale.ROOT));
        return matcher.find() ? matcher.group(1) : "unspecified";
    }

    public record CatalogueMetadata(
            String filename,
            String version,
            String sha256,
            String source) {
    }
}

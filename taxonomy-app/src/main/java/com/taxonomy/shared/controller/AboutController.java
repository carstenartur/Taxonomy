package com.taxonomy.shared.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for application version, build info, licenses, notices and SBOMs.
 */
@RestController
@RequestMapping("/api/about")
@Tag(name = "About")
public class AboutController {

    private static final Logger log = LoggerFactory.getLogger(AboutController.class);
    private static final MediaType MARKDOWN = MediaType.parseMediaType("text/markdown;charset=UTF-8");
    private static final MediaType UTF8_TEXT = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final BuildProperties buildProperties;
    private final GitProperties gitProperties;

    @Autowired(required = false)
    public AboutController(BuildProperties buildProperties, GitProperties gitProperties) {
        this.buildProperties = buildProperties;
        this.gitProperties = gitProperties;
    }

    public AboutController() {
        this.buildProperties = null;
        this.gitProperties = null;
    }

    @GetMapping
    @Operation(summary = "Application info", description = "Returns version, build, source and packaged legal-resource information.")
    public ResponseEntity<Map<String, Object>> about() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("product", "Taxonomy Architecture Analyzer");
        info.put("version", buildProperties != null ? buildProperties.getVersion() : "unknown");
        info.put("buildTime", buildProperties != null ? buildProperties.getTime() : null);
        info.put("commit", gitProperties != null ? gitProperties.getShortCommitId() : "unknown");
        info.put("commitTime", gitProperties != null ? gitProperties.getCommitTime() : null);
        info.put("branch", gitProperties != null ? gitProperties.getBranch() : "unknown");
        info.put("license", "MIT");
        info.put("copyright", "Copyright 2026 Carsten Hammer");
        info.put("sourceUrl", "https://github.com/carstenartur/Taxonomy");
        info.put("apiDocsUrl", "/swagger-ui.html");
        info.put("projectLicenseUrl", "/api/about/license");
        info.put("noticeUrl", "/api/about/notice");
        info.put("thirdPartyNoticesUrl", "/api/about/third-party");
        info.put("runtimeThirdPartyLicensesUrl", "/api/about/runtime-licenses");
        info.put("sbomJsonUrl", "/api/about/sbom.json");
        info.put("sbomXmlUrl", "/api/about/sbom.xml");
        return ResponseEntity.ok(info);
    }

    @GetMapping("/license")
    @Operation(summary = "Project license", description = "Returns the packaged Taxonomy MIT license.")
    public ResponseEntity<String> license() {
        return classpathText("static/LICENSE", UTF8_TEXT);
    }

    @GetMapping("/notice")
    @Operation(summary = "Product notice", description = "Returns the packaged product NOTICE file.")
    public ResponseEntity<String> notice() {
        return classpathText("static/NOTICE", UTF8_TEXT);
    }

    @GetMapping("/third-party")
    @Operation(summary = "Curated third-party notices", description = "Returns curated notices for browser assets, models, containers and special distribution terms.")
    public ResponseEntity<String> thirdParty() {
        return classpathText("static/THIRD-PARTY-NOTICES.md", MARKDOWN);
    }

    @GetMapping("/runtime-licenses")
    @Operation(summary = "Generated runtime dependency licenses", description = "Returns the build-generated report for packaged Maven runtime dependencies.")
    public ResponseEntity<String> runtimeLicenses() {
        return classpathText("THIRD-PARTY-RUNTIME.txt", UTF8_TEXT);
    }

    @GetMapping("/sbom.json")
    @Operation(summary = "CycloneDX SBOM as JSON", description = "Returns the build-specific packaged CycloneDX software bill of materials.")
    public ResponseEntity<String> sbomJson() {
        return classpathText("static/taxonomy-sbom.json", MediaType.APPLICATION_JSON);
    }

    @GetMapping("/sbom.xml")
    @Operation(summary = "CycloneDX SBOM as XML", description = "Returns the build-specific packaged CycloneDX software bill of materials.")
    public ResponseEntity<String> sbomXml() {
        return classpathText("static/taxonomy-sbom.xml", MediaType.APPLICATION_XML);
    }

    private ResponseEntity<String> classpathText(String path, MediaType contentType) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            log.warn("About resource not found on classpath: {}", path);
            return ResponseEntity.notFound().build();
        }
        try (InputStream input = resource.getInputStream()) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(contentType).body(content);
        } catch (IOException e) {
            log.warn("Could not read about resource {}: {}", path, e.getMessage());
            return ResponseEntity.internalServerError().contentType(UTF8_TEXT)
                    .body("Packaged legal resource could not be read.");
        }
    }
}

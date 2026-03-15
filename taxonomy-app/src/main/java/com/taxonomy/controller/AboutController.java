package com.taxonomy.controller;

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
 * REST API for application version, build info, license, and third-party notices.
 */
@RestController
@RequestMapping("/api/about")
@Tag(name = "About")
public class AboutController {

    private static final Logger log = LoggerFactory.getLogger(AboutController.class);

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
    @Operation(summary = "Application info", description = "Returns version, build time, git commit, license, and copyright information.")
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
        info.put("thirdPartyNoticesUrl", "/THIRD-PARTY-NOTICES.md");
        return ResponseEntity.ok(info);
    }

    @GetMapping("/third-party")
    @Operation(summary = "Third-party notices", description = "Returns the content of THIRD-PARTY-NOTICES.md.")
    public ResponseEntity<String> thirdParty() {
        try {
            ClassPathResource resource = new ClassPathResource("static/THIRD-PARTY-NOTICES.md");
            try (InputStream is = resource.getInputStream()) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                        .body(content);
            }
        } catch (IOException e) {
            log.warn("THIRD-PARTY-NOTICES.md not found on classpath: {}", e.getMessage());
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Third-party notices file not available.");
        }
    }
}

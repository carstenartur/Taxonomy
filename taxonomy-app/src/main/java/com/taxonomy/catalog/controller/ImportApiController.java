package com.taxonomy.catalog.controller;

import com.taxonomy.catalog.service.importer.FrameworkImportService;
import com.taxonomy.dto.FrameworkImportResult;
import com.taxonomy.dto.ProfileInfo;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/import")
@Tag(name = "Framework Import")
public class ImportApiController {

    private static final Logger log = LoggerFactory.getLogger(ImportApiController.class);

    private final FrameworkImportService importService;
    private final RepositoryStateService repositoryStateService;
    private final WorkspaceResolver workspaceResolver;

    public ImportApiController(FrameworkImportService importService,
                               RepositoryStateService repositoryStateService,
                               WorkspaceResolver workspaceResolver) {
        this.importService = importService;
        this.repositoryStateService = repositoryStateService;
        this.workspaceResolver = workspaceResolver;
    }

    @Operation(summary = "List import profiles",
            description = "Returns all registered import profiles (UAF, APQC, C4, etc.)")
    @GetMapping("/profiles")
    public ResponseEntity<List<ProfileInfo>> listProfiles() {
        return ResponseEntity.ok(importService.getAvailableProfiles());
    }

    @Operation(summary = "Preview import",
            description = "Parse and map the file without writing to the database. Returns mapping statistics.")
    @PostMapping(value = "/preview/{profileId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FrameworkImportResult> preview(
            @PathVariable String profileId,
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(importService.preview(profileId, file.getInputStream()));
        } catch (Exception e) {
            log.error("Preview failed for profile {} with file {}",
                    profileId, file.getOriginalFilename(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Import framework model",
            description = "Parse, map, and materialize the file into the active workspace branch unless an explicit branch is supplied")
    @PostMapping(value = "/{profileId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> importFile(
            @PathVariable String profileId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "branch", required = false) String requestedBranch) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            WorkspaceContext context = workspaceResolver.resolveCurrentContext();
            String username = context.username();
            String activeBranch = repositoryStateService.resolveWorkspaceBranch(username);
            String branch = requestedBranch == null || requestedBranch.isBlank()
                    ? activeBranch : requestedBranch.trim();

            FrameworkImportResult result =
                    importService.importFile(profileId, file.getInputStream(), branch);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("result", result);
            response.put("branch", branch);
            response.put("viewContext",
                    repositoryStateService.getViewContext(username, branch, context));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Import failed for profile {} with file {}",
                    profileId, file.getOriginalFilename(), e);
            return ResponseEntity.badRequest().build();
        }
    }
}

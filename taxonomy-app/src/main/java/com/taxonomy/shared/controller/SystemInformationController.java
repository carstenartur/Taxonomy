package com.taxonomy.shared.controller;

import com.taxonomy.shared.service.SystemInformationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Uses the existing admin authorization boundary; never exposes a public Actuator endpoint. */
@RestController
public class SystemInformationController {
    private final SystemInformationService information;

    public SystemInformationController(SystemInformationService information) {
        this.information = information;
    }

    @Operation(summary = "Read system and database persistence information", tags = {"Administration"},
            description = "Returns the application's bounded system-information view for diagnostics, using the information service's redacted representation. This inspection does not trigger analysis or a health repair.")
    @ApiResponse(responseCode = "200", description = "Observed runtime and database information; optional diagnostics may be unavailable",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SystemInformationService.Snapshot.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "403", description = "Administrator role required", content = @Content)
    @GetMapping("/api/admin/system-information")
    public ResponseEntity<SystemInformationService.Snapshot> information(HttpServletRequest request) {
        if (!request.isUserInRole("ADMIN") && !request.isUserInRole("ROLE_ADMIN")) {
            return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(information.snapshot());
    }
}

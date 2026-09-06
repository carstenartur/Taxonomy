package com.taxonomy.shared.controller;

import com.taxonomy.shared.service.SystemInformationService;
import io.swagger.v3.oas.annotations.Operation;
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

    @Operation(summary = "Read system and database persistence information", tags = {"Administration"})
    @GetMapping("/api/admin/system-information")
    public ResponseEntity<SystemInformationService.Snapshot> information(HttpServletRequest request) {
        if (!request.isUserInRole("ADMIN") && !request.isUserInRole("ROLE_ADMIN")) {
            return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(information.snapshot());
    }
}

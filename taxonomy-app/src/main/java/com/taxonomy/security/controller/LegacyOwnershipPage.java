package com.taxonomy.security.controller;

import com.taxonomy.security.service.VerifiedLegacyOwnerMigration;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Read-only administrator report, including deployments with external authentication. */
@Controller
public class LegacyOwnershipPage {
    private final VerifiedLegacyOwnerMigration migration;

    public LegacyOwnershipPage(VerifiedLegacyOwnerMigration migration) { this.migration = migration; }

    @GetMapping("/admin/identity/ownership")
    @PreAuthorize("hasRole('ADMIN')")
    public String view(Authentication administrator, Model model, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        model.addAttribute("status", migration.reservationStatus(administrator, 100));
        return "legacy-ownership";
    }
}

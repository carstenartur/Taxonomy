package com.taxonomy.security.controller;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.taxonomy.security.service.BrowserSessionInventory;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** Administrator-only inventory. No session IDs, credentials, roles or client addresses. */
@Controller
@Tag(name = "Browser sessions")
@PreAuthorize("hasRole('ADMIN')")
public class BrowserSessionController {
    private final BrowserSessionInventory inventory;

    public BrowserSessionController(BrowserSessionInventory inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/admin/sessions")
    public String page(Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("snapshot", inventory.snapshot());
        return "browser-sessions";
    }

    @GetMapping("/api/admin/sessions")
    @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Read the administrative browser-session inventory",
            description = "ADMIN only. Returns the bounded session inventory without session identifiers, credentials, roles or client addresses. The response must not be cached and does not revoke sessions.")
    @ApiResponse(responseCode = "200", description = "Read the administrative browser-session inventory response")
    public ResponseEntity<BrowserSessionInventory.Snapshot> sessions() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(inventory.snapshot());
    }
}

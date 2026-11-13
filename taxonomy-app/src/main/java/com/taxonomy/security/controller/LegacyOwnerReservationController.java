package com.taxonomy.security.controller;

import com.taxonomy.security.service.VerifiedLegacyOwnerMigration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Inspect and reserve existing ownership for offline identity verification. */
@RestController
@RequestMapping("/api/admin/users/ownership-reservations")
@Tag(name = "Legacy identity migration")
@PreAuthorize("hasRole('ADMIN')")
public class LegacyOwnerReservationController {
    private final VerifiedLegacyOwnerMigration migration;

    public LegacyOwnerReservationController(VerifiedLegacyOwnerMigration migration) {
        this.migration = migration;
    }

    @GetMapping
    @Operation(summary = "Inspect historical identity reservations",
            description = "Returns current ownership readiness counts and a bounded list of disabled unbound principals.")
    public ResponseEntity<VerifiedLegacyOwnerMigration.ReservationStatus> preview(
            Authentication administrator, @RequestParam(defaultValue = "100") int limit) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(migration.reservationStatus(administrator, limit));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reservation preview", invalid);
        }
    }

    public record ReservationResult(int created) { }

    @PostMapping
    @Operation(summary = "Reserve newly observed historical ownership",
            description = "Creates disabled, unbound reservations and audit rows for observed historical owners. A repeat returns zero when the inventory has not changed.")
    public ResponseEntity<ReservationResult> reserve(Authentication administrator) {
        int created = migration.reserveMissing(administrator);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ReservationResult(created));
    }
}

package com.taxonomy.security.controller;

import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.service.RepositoryOwnershipTransfer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/ownership")
@Tag(name = "Architecture Repositories")
@ConditionalOnProperty(prefix = "taxonomy.features.multi-repository-api", name = "enabled", havingValue = "true")
public class RepositoryOwnershipTransferController {
    private final RepositoryOwnershipTransfer transfers;

    public RepositoryOwnershipTransferController(RepositoryOwnershipTransfer transfers) { this.transfers = transfers; }

    public record TransferRequest(UUID targetPrincipalId, Long expectedVersion, String rationale) { }

    @PutMapping
    @Operation(summary = "Transfer a personal repository to an active principal",
            description = "The catalogue owner supplies the successor's stable principal ID, the version from GET /api/repositories/{repositoryId}, and a rationale. The successor receives OWNER, the former owner relinquishes direct ownership, and the receipt identifies the committed audit event.")
    public ResponseEntity<RepositoryOwnershipTransfer.Receipt> transfer(@PathVariable String repositoryId,
            @RequestBody TransferRequest request, Authentication authentication) {
        if (request.expectedVersion() == null) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED);
        if (request.targetPrincipalId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var receipt = transfers.transfer(authentication, repositoryId, new PrincipalId(request.targetPrincipalId()),
                request.expectedVersion(), request.rationale());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(receipt);
    }
}

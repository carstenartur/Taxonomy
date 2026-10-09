package com.taxonomy.composition.analysis.artemis;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.taxonomy.analysis.dispatch.AnalysisDispatchService;
import com.taxonomy.analysis.dispatch.AnalysisDispatchStatus;
import com.taxonomy.analysis.dispatch.AnalysisDispatchStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumMap;
import java.util.Map;

/**
 * Administrative view of the durable dispatch backlog and the explicit, bounded
 * repair trigger. Exists only in Artemis transport mode; protected by the
 * {@code /api/admin/**} ADMIN rule.
 */
@RestController
@Tag(name = "Analysis dispatch", description = "ADMIN-only Artemis delivery status and bounded recovery")
@RequestMapping("/api/admin/analysis/dispatch")
@ConditionalOnProperty(name = "taxonomy.analysis.transport.mode", havingValue = ArtemisAnalysisSettings.MODE_ARTEMIS)
public class AnalysisDispatchAdminController {

    private final AnalysisDispatchStore store;
    private final AnalysisDispatchService dispatch;
    private final ArtemisAnalysisConnection connection;

    public AnalysisDispatchAdminController(AnalysisDispatchStore store, AnalysisDispatchService dispatch,
                                           ArtemisAnalysisConnection connection) {
        this.store = store;
        this.dispatch = dispatch;
        this.connection = connection;
    }

    /** Documentation model for the existing map response; the wire representation is unchanged. */
    @Schema(name = "AnalysisDispatchStatus", description = "Content-free administrative delivery status")
    public record DispatchStatusResponse(
            @Schema(description = "Artemis connection state, not analysis completion") String broker,
            @Schema(description = "Number of durable intents for each dispatch status") Map<String, Long> intents) { }

    @Operation(summary = "Read the durable analysis dispatch backlog",
            description = "ADMIN only; available only in Artemis mode. Returns the broker connection state and "
                    + "counts of dispatch intents by status. Does not expose prompts, credentials or user identities "
                    + "and does not start or repair an analysis.")
    @ApiResponse(responseCode = "200", description = "Broker state and intent counts",
            content = @Content(schema = @Schema(implementation = DispatchStatusResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "403", description = "Administrator role required", content = @Content)
    @GetMapping
    public Map<String, Object> status() {
        Map<AnalysisDispatchStatus, Long> counts = new EnumMap<>(AnalysisDispatchStatus.class);
        for (AnalysisDispatchStatus status : AnalysisDispatchStatus.values()) {
            counts.put(status, store.count(status));
        }
        return Map.of("broker", connection.state().name(), "intents", counts);
    }

    @Operation(summary = "Repair recoverable analysis dispatch intents",
            description = "ADMIN only; available only in Artemis mode. Explicitly triggers bounded, keyset-paginated "
                    + "republication of recoverable intents, not a new analysis. Concurrent triggers are coalesced. "
                    + "The report describes this recovery attempt, not completion of the underlying analyses. "
                    + "A broker outage leaves intents recoverable; no periodic database polling is enabled.")
    @ApiResponse(responseCode = "200", description = "Bounded recovery report",
            content = @Content(schema = @Schema(implementation = AnalysisDispatchService.RecoveryReport.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "403", description = "Administrator role or valid CSRF protection required", content = @Content)
    @PostMapping("/repair")
    public AnalysisDispatchService.RecoveryReport repair() {
        return dispatch.repair();
    }
}

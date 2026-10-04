package com.taxonomy.composition.analysis.artemis;

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

    @GetMapping
    public Map<String, Object> status() {
        Map<AnalysisDispatchStatus, Long> counts = new EnumMap<>(AnalysisDispatchStatus.class);
        for (AnalysisDispatchStatus status : AnalysisDispatchStatus.values()) {
            counts.put(status, store.count(status));
        }
        return Map.of("broker", connection.state().name(), "intents", counts);
    }

    @PostMapping("/repair")
    public AnalysisDispatchService.RecoveryReport repair() {
        return dispatch.repair();
    }
}

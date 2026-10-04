package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.dispatch.AnalysisDispatchStatus;
import com.taxonomy.analysis.dispatch.AnalysisDispatchStore;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.Objects;

/**
 * Reports the analysis broker connection and the number of dispatch intents that
 * are waiting for it. Never exposes the broker URL or credentials. Not part of
 * the readiness group: an unreachable broker delays analysis but must not take
 * the editor, search or catalogue out of service.
 */
public final class ArtemisAnalysisBrokerHealthIndicator implements HealthIndicator {

    private final ArtemisAnalysisConnection connection;
    private final AnalysisDispatchStore store;

    public ArtemisAnalysisBrokerHealthIndicator(ArtemisAnalysisConnection connection, AnalysisDispatchStore store) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Health health() {
        Health.Builder builder = connection.connected() ? Health.up() : Health.down();
        builder.withDetail("state", connection.state().name());
        try {
            builder.withDetail("waitingForBroker", store.count(AnalysisDispatchStatus.WAITING_FOR_BROKER))
                    .withDetail("dispatchPending", store.count(AnalysisDispatchStatus.DISPATCH_PENDING))
                    .withDetail("dispatchFailed", store.count(AnalysisDispatchStatus.DISPATCH_FAILED));
        } catch (RuntimeException unavailable) {
            builder.withDetail("dispatchBacklog", "unavailable");
        }
        return builder.build();
    }
}

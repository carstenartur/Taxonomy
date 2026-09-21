package com.taxonomy.composition.reformulation;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Application-lifetime timers only; work and retry state belong to the database. */
@Component
public class ReformulationRecoveryCoordinator {
    private final ReformulationExecutionService execution;
    private final long pollMillis;
    private final long heartbeatMillis;
    private final int maximumInFlight;
    private ScheduledExecutorService timers;
    public ReformulationRecoveryCoordinator(ReformulationExecutionService execution,
            @Value("${reformulation.recovery.poll-ms:5000}") long pollMillis,
            @Value("${reformulation.recovery.heartbeat-ms:10000}") long heartbeatMillis,
            @Value("${reformulation.recovery.lease-ms:120000}") long leaseMillis,
            @Value("${reformulation.recovery.max-in-flight:2}") int maximumInFlight) {
        if(pollMillis<100 || heartbeatMillis<100 || heartbeatMillis>leaseMillis/3 || maximumInFlight<1 || maximumInFlight>32)
            throw new IllegalArgumentException("Invalid reformulation recovery scheduling limits");
        this.execution=execution;this.pollMillis=pollMillis;this.heartbeatMillis=heartbeatMillis;this.maximumInFlight=maximumInFlight;
    }
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if(timers!=null)return;
        timers=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"reformulation-recovery");t.setDaemon(true);return t;});
        timers.scheduleWithFixedDelay(()->tick(()->execution.recoverAvailable(maximumInFlight)),pollMillis,pollMillis,TimeUnit.MILLISECONDS);
        timers.scheduleWithFixedDelay(()->tick(execution::heartbeat),heartbeatMillis,heartbeatMillis,TimeUnit.MILLISECONDS);
    }
    private void tick(Runnable work) {
        try {work.run();}
        catch(RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Reformulation recovery tick failed ({})",failure.getClass().getSimpleName());
        }
    }
    @PreDestroy
    public synchronized void stop() {
        if(timers!=null) {timers.shutdownNow();timers=null;}
    }
}

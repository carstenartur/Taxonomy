package com.taxonomy.backup.jobs;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts after the startup maintenance hold is released; repeat tasks and thread count are bounded. */
public final class BackupJobRunner implements ApplicationListener<ApplicationReadyEvent>, DisposableBean, Ordered {
    private final BackupJobWorker worker;
    private final int concurrency;
    private final AtomicBoolean warned = new AtomicBoolean();
    private ScheduledExecutorService executor;
    public BackupJobRunner(BackupJobWorker worker, BackupJobLimits limits) {
        this.worker = worker; concurrency = limits.heavyConcurrency() + limits.currentConcurrency();
    }
    @Override public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
        if (executor != null) return;
        executor = Executors.newScheduledThreadPool(concurrency, Thread.ofPlatform().daemon().name("backup-worker-", 0).factory());
        for (int i = 0; i < concurrency; i++) executor.scheduleWithFixedDelay(() -> {
            try { worker.runNext(); warned.set(false); }
            catch (RuntimeException unavailable) {
                if (warned.compareAndSet(false, true)) LoggerFactory.getLogger(BackupJobRunner.class).warn("Backup worker unavailable; retrying durable queue");
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
    }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public synchronized void destroy() {
        if (executor != null) executor.shutdownNow();
    }
}

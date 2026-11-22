package com.taxonomy.mail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.shared.features.ConditionalOnFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Durable admission receipts and delivery jobs owned by one mailbox coordinator. */
@Service
@ConditionalOnProperty(name = "taxonomy.mail.enabled", havingValue = "true")
@ConditionalOnFeature({"analysis", "architecture", "reporting"})
public final class MailAnalysisQueue implements ApplicationListener<ApplicationReadyEvent>, DisposableBean, Ordered {
    private static final Logger log = LoggerFactory.getLogger(MailAnalysisQueue.class);
    private static final Duration DELIVERY_RETRY = Duration.ofMinutes(1);
    private static final Comparator<Job> FIFO = Comparator.comparingLong(Job::sequence).thenComparing(Job::token);
    private static final Comparator<Job> DELIVERY_ORDER = Comparator
            .comparing(Job::retryAfter, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(FIFO);

    public enum State { QUEUED, RUNNING, DELIVERING, COMPLETED, PARTIAL, FAILED, REJECTED }

    private static final String CAPACITY_REJECTION = "Die Warteschlange ist voll. Bitte senden Sie den Auftrag später erneut.";

    public record Submission(String jobToken, Integer position, boolean accepted, boolean duplicate,
                             boolean receiptConfirmed, String rejectionReason) {}

    /** The bearer link deliberately has no request, address, or attachment data. */
    public record Status(State state, Integer position, int queuedJobs, int capacity,
                         int runningJobs, int deliveringJobs, boolean finished) {}

    record Outcome(String summary, List<MailAnalysisAttachment> attachments, boolean successful, boolean partial) {
        Outcome(String summary, List<MailAnalysisAttachment> attachments, boolean successful) {
            this(summary, attachments, successful, false);
        }

        State deliveredState() {
            if (!successful) return State.FAILED;
            return partial ? State.PARTIAL : State.COMPLETED;
        }

        static Outcome failed(String summary) {
            return new Outcome(summary, List.of(), false);
        }
    }

    private record Job(String token, String identity, long sequence, IncomingMail mail, State state,
                       boolean receiptConfirmed, Instant submittedAt, Instant updatedAt, Instant retryAfter,
                       Instant deliveryStartedAt, Integer admissionPosition) {
        boolean terminal() {
            return state == State.COMPLETED || state == State.PARTIAL || state == State.FAILED || state == State.REJECTED;
        }

        Instant retentionStart() {
            if (terminal() || !receiptConfirmed) return updatedAt;
            return state == State.DELIVERING ? Objects.requireNonNullElse(deliveryStartedAt, updatedAt) : null;
        }

        Job update(State next, boolean confirmed, Instant updated, Instant retry) {
            IncomingMail retained = switch (next) {
                case COMPLETED, PARTIAL, FAILED, REJECTED -> null;
                case DELIVERING -> new IncomingMail(null, mail.sender(), mail.subject(), null);
                default -> mail;
            };
            Instant firstDelivery = next == State.DELIVERING && deliveryStartedAt == null ? updated : deliveryStartedAt;
            return new Job(token, identity, sequence, retained, next, confirmed,
                    submittedAt, updated, retry, firstDelivery, admissionPosition);
        }
    }

    private final Path directory;
    private final int capacity;
    private final Duration retention;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final MailAnalysisProcessor processor;
    private final MailAnalysisDelivery delivery;
    // Rebuilt from the durable job files on startup. Only active jobs participate in scheduling.
    private final Map<String, Job> jobs = new HashMap<>();
    private final Map<String, String> tokenByIdentity = new HashMap<>();
    private final NavigableSet<Job> queued = new TreeSet<>(FIFO);
    private final NavigableSet<Job> running = new TreeSet<>(FIFO);
    private final NavigableSet<Job> deliveries = new TreeSet<>(DELIVERY_ORDER);
    private final NavigableSet<Job> expiring = new TreeSet<>(Comparator.comparing(Job::retentionStart).thenComparing(FIFO));
    private final Map<State, NavigableSet<Job>> active = Map.of(
            State.QUEUED, queued, State.RUNNING, running, State.DELIVERING, deliveries);
    private FileChannel lockChannel;
    private FileLock directoryLock;
    private ScheduledExecutorService executor;
    private long lastSequence;
    private boolean warned;
    private boolean lastClaimWasDeliveryRetry;

    @Autowired
    public MailAnalysisQueue(
            @Value("${taxonomy.mail.queue.directory:./data/mail-analysis}") Path directory,
            @Value("${taxonomy.mail.queue.capacity:16}") int capacity,
            @Value("${taxonomy.mail.queue.retention:PT48H}") Duration retention,
            ObjectMapper mapper, MailAnalysisProcessor processor, MailAnalysisDelivery delivery) {
        this(directory, capacity, retention, mapper, processor, delivery, Clock.systemUTC());
    }

    MailAnalysisQueue(Path directory, int capacity, Duration retention, ObjectMapper mapper,
            MailAnalysisProcessor processor, MailAnalysisDelivery delivery, Clock clock) {
        if (capacity < 0 || capacity > 1_000 || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("Invalid email queue capacity or retention");
        }
        this.directory = directory.toAbsolutePath().normalize();
        this.capacity = capacity;
        this.retention = retention;
        this.clock = clock;
        this.mapper = mapper;
        this.processor = processor;
        this.delivery = delivery;
        openDirectory();
    }

    public synchronized Submission submit(IncomingMail mail) {
        Objects.requireNonNull(mail);
        pruneExpired();
        String identity = StableIdentityHash.sha256(mail.sender() + "\n" + mail.messageId());
        Job existing = jobs.get(tokenByIdentity.get(identity));
        if (existing != null) return submission(existing, true);

        int waiting = queued.size();
        boolean accepted = waiting < capacity;
        Instant admitted = clock.instant();
        // Record each decision before SMTP. A retry of the same IMAP UID must never change the answer.
        Job job = new Job(UUID.randomUUID().toString(), identity, ++lastSequence, accepted ? mail : null,
                accepted ? State.QUEUED : State.REJECTED, false, admitted, admitted, null, null,
                accepted ? waiting + 1 : null);
        store(job);
        return submission(job, false);
    }

    private Submission submission(Job job, boolean duplicate) {
        boolean accepted = job.state() != State.REJECTED;
        Integer admittedAt = job.admissionPosition();
        if (admittedAt == null && job.state() == State.QUEUED) admittedAt = position(job);
        return new Submission(job.token(), admittedAt, accepted, duplicate, job.receiptConfirmed(),
                accepted ? null : CAPACITY_REJECTION);
    }

    /** The worker starts only after the mailbox adapter successfully sent the admission receipt. */
    public synchronized void confirmReceipt(String jobToken) {
        Job job = jobs.get(jobToken);
        if (job != null && !job.receiptConfirmed()) {
            store(job.update(job.state(), true, clock.instant(), null));
        }
    }

    public synchronized Optional<Status> status(String jobToken) {
        Job job = jobs.get(jobToken);
        if (job == null || job.state() == State.REJECTED || removable(job, clock.instant())) return Optional.empty();
        Integer position = job.state() == State.QUEUED ? position(job) : null;
        return Optional.of(new Status(job.state(), position, queued.size(), capacity,
                running.size(), deliveries.size(), job.terminal()));
    }

    private int position(Job job) {
        return 1 + queued.headSet(job).size();
    }

    @Override
    public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("mail-analysis").factory());
        executor.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private void tick() {
        try {
            processNext();
            warned = false;
        } catch (RuntimeException unavailable) {
            if (!warned) log.warn("Email analysis queue unavailable; durable jobs will be retried ({})",
                    unavailable.getClass().getSimpleName());
            warned = true;
        }
    }

    void processNext() {
        Job job = claimNext();
        if (job == null) return;
        Path outgoing = file(job.token(), ".outgoing");
        Outcome computed = null;
        if (job.state() == State.RUNNING) {
            // A restart between storing the file and recording DELIVERING can reuse the staged result.
            if (!Files.exists(outgoing)) {
                computed = analyze(job);
                write(outgoing, computed);
            }
            job = job.update(State.DELIVERING, true, clock.instant(), null);
            update(job);
        }

        Outcome outcome;
        try {
            outcome = computed != null ? computed : read(outgoing, Outcome.class);
            if (outcome.successful()) {
                delivery.sendResult(job.mail().sender(), job.mail().subject(), outcome.summary(), outcome.attachments());
            } else {
                delivery.sendFailure(job.mail().sender(), job.mail().subject(), outcome.summary());
            }
        } catch (RuntimeException unavailable) {
            var now = clock.instant();
            update(job.update(State.DELIVERING, true, now, now.plus(DELIVERY_RETRY)));
            log.warn("Email job delivery will be retried ({})", unavailable.getClass().getSimpleName());
            return;
        }
        update(job.update(outcome.deliveredState(), true, clock.instant(), null));
        delete(outgoing);
    }

    private Outcome analyze(Job job) {
        try {
            return processor.process(job.mail().subject(), job.mail().requirementText());
        } catch (MailAnalysisProcessor.Rejected rejection) {
            return Outcome.failed(rejection.getMessage());
        } catch (RuntimeException failure) {
            if (Thread.currentThread().isInterrupted()) throw failure;
            log.warn("Email analysis could not be completed ({})", failure.getClass().getSimpleName());
            return Outcome.failed("Die Analyse oder der Export konnte nicht fertiggestellt werden.");
        }
    }

    private synchronized Job claimNext() {
        pruneExpired();
        Instant now = clock.instant();
        if (!running.isEmpty()) return running.first();
        Job due = deliveries.isEmpty() ? null : deliveries.first();
        if (due != null && due.retryAfter() != null && due.retryAfter().isAfter(now)) due = null;
        Job waiting = queued.stream().filter(Job::receiptConfirmed).findFirst().orElse(null);
        // Long SMTP timeouts across several due retries must still leave turns for admitted analyses.
        if (due != null && (waiting == null || !lastClaimWasDeliveryRetry)) {
            lastClaimWasDeliveryRetry = true;
            return due;
        }
        if (waiting == null) return null;
        Job started = waiting.update(State.RUNNING, true, now, null);
        store(started);
        lastClaimWasDeliveryRetry = false;
        return started;
    }

    private synchronized void update(Job job) {
        store(job);
    }

    private void openDirectory() {
        try {
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createDirectories(directory, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(directory);
            }
            lockChannel = FileChannel.open(directory.resolve("coordinator.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            directoryLock = lockChannel.tryLock();
            if (directoryLock == null) throw new IllegalStateException("Another email queue coordinator owns the configured directory");
            try (var files = Files.list(directory)) {
                for (Path path : files.filter(item -> item.getFileName().toString().endsWith(".job")).toList()) {
                    Job job = read(path, Job.class);
                    if (!path.equals(file(job.token(), ".job"))) throw new IllegalStateException("Invalid email job file identity");
                    if (job.state() == State.RUNNING && job.retryAfter() != null && Files.exists(file(job.token(), ".outgoing"))) {
                        // Jobs written before delivery had its own state retain their existing backoff on upgrade.
                        job = job.update(State.DELIVERING, true, job.updatedAt(), job.retryAfter());
                        store(job);
                    } else index(job);
                    lastSequence = Math.max(lastSequence, job.sequence());
                    if (job.terminal()) delete(file(job.token(), ".outgoing"));
                }
            }
            try (var files = Files.list(directory)) {
                for (Path path : files.filter(item -> item.getFileName().toString().startsWith(".tmp-")).toList()) delete(path);
            }
            pruneExpired();
        } catch (OverlappingFileLockException locked) {
            releaseLock();
            throw new IllegalStateException("Another email queue coordinator owns the configured directory", locked);
        } catch (IOException unavailable) {
            releaseLock();
            throw new UncheckedIOException("Email queue storage is unavailable", unavailable);
        } catch (RuntimeException invalid) {
            releaseLock();
            throw invalid;
        }
    }

    private Path file(String token, String suffix) {
        if (token == null || !UUID.fromString(token).toString().equals(token)) {
            throw new IllegalArgumentException("Invalid email job identity");
        }
        return directory.resolve(token + suffix);
    }

    private <T> T read(Path path, Class<T> type) {
        try (var input = Files.newInputStream(path)) {
            return mapper.readValue(input, type);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read an email job", failure);
        }
    }

    private void write(Path path, Object data) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, ".tmp-", null);
            try (var output = Files.newOutputStream(temporary)) {
                mapper.writeValue(output, data);
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not persist an email job", failure);
        } finally {
            if (temporary != null) delete(temporary);
        }
    }

    private void store(Job job) {
        write(file(job.token(), ".job"), job);
        index(job);
    }

    private void index(Job job) {
        Job previous = jobs.put(job.token(), job);
        if (previous == null) tokenByIdentity.put(job.identity(), job.token());
        else removeFromSchedule(previous);

        var stateJobs = active.get(job.state());
        if (stateJobs != null) stateJobs.add(job);
        if (job.retentionStart() != null) expiring.add(job);
    }

    private void removeFromSchedule(Job job) {
        var stateJobs = active.get(job.state());
        if (stateJobs != null) stateJobs.remove(job);
        if (job.retentionStart() != null) expiring.remove(job);
    }

    private void pruneExpired() {
        Instant now = clock.instant();
        while (!expiring.isEmpty()) {
            Job job = expiring.first();
            if (!retainedTooLong(job.retentionStart(), now)) return;
            if (job.state() == State.DELIVERING && job.receiptConfirmed()) {
                store(job.update(State.FAILED, true, now, null));
                delete(file(job.token(), ".outgoing"));
            } else {
                delete(file(job.token(), ".outgoing"));
                delete(file(job.token(), ".job"));
                removeFromSchedule(job);
                jobs.remove(job.token());
                tokenByIdentity.remove(job.identity());
            }
        }
    }

    private boolean removable(Job job, Instant now) {
        return (job.terminal() || !job.receiptConfirmed()) && retainedTooLong(job.updatedAt(), now);
    }

    private boolean retainedTooLong(Instant since, Instant now) {
        return Duration.between(since, now).compareTo(retention) >= 0;
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not remove an email job file", failure);
        }
    }

    private void releaseLock() {
        if (lockChannel != null) {
            try {
                lockChannel.close();
            } catch (IOException failure) {
                log.warn("Could not close email queue storage ({})", failure.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void destroy() {
        final ScheduledExecutorService worker;
        synchronized (this) {
            worker = executor;
            if (worker != null) worker.shutdownNow();
        }
        try {
            if (worker != null && !worker.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Email analysis worker is still stopping; keeping the coordinator lock until process exit");
                return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        releaseLock();
    }
}

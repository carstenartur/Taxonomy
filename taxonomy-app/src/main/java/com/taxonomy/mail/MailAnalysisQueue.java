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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
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
    private static final Duration STORAGE_RETRY = Duration.ofMinutes(1);
    private static final Duration SOURCE_RETRY = Duration.ofSeconds(5);
    private static final Comparator<Job> FIFO = Comparator.comparingLong(Job::sequence).thenComparing(Job::token);
    private static final Comparator<Job> DELIVERY_ORDER = Comparator
            .comparing(Job::retryAfter, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(FIFO);

    public enum State { QUEUED, RUNNING, DELIVERING, COMPLETED, PARTIAL, FAILED, REJECTED }

    public enum WorkerState { STOPPED, IDLE, RUNNING, WAITING_FOR_SOURCE, RETRYING, DELIVERING }

    private static final int MAX_DURATION_SAMPLES = 32;
    private static final int MIN_DURATION_SAMPLES = 3;
    private static final String CAPACITY_REJECTION = "Die Warteschlange ist voll. Bitte senden Sie den Auftrag später erneut.";

    public record Submission(String jobToken, Integer position, boolean accepted, boolean duplicate,
                             boolean receiptConfirmed, String rejectionReason) {}

    /** The bearer link deliberately has no request, address, or attachment data. */
    public record Status(State state, Integer position, int queuedJobs, int capacity,
                         int runningJobs, int deliveringJobs, boolean finished) {}

    public record SettingsView(MailAnalysisCommand.Mode mode, List<String> taxonomyCodes, int taxonomyCount,
                               List<MailAnalysisCommand.Format> exports) {}

    /** A display identifier refers to a queue row. Only the receipt token authorizes access. */
    public record JobView(String displayId, boolean own, State state, Integer position,
                          Instant submittedAt, Instant startedAt, SettingsView settings,
                          Long estimatedWaitSeconds) {}

    public record Snapshot(@JsonUnwrapped Status summary, Instant observedAt, boolean workerOnline,
                           int workerCount, WorkerState workerState, int estimateSamples, Long typicalDurationSeconds,
                           JobView job, List<JobView> jobs) {}

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

    private record Prepared(String token, MailAnalysisCommand command) {}

    private record Execution(String id, MailAnalysisCommand command) {}

    private record Job(String token, String identity, long sequence, IncomingMail mail, State state,
                       boolean receiptConfirmed, Instant submittedAt, Instant updatedAt, Instant retryAfter,
                       Instant deliveryStartedAt, Integer admissionPosition, Execution execution,
                       Instant startedAt, MailAnalysisCommand classifiedSettings) {
        MailAnalysisCommand settings() {
            return execution != null && execution.command() != null ? execution.command() : classifiedSettings;
        }

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
            Execution retainedExecution = next == State.QUEUED || next == State.RUNNING ? execution : null;
            Instant start = next == State.RUNNING && state == State.QUEUED ? updated : startedAt;
            return new Job(token, identity, sequence, retained, next, confirmed,
                    submittedAt, updated, retry, firstDelivery, admissionPosition, retainedExecution,
                    start, retainedExecution == null ? settings() : null);
        }

        Job withExecution(Execution value) {
            return new Job(token, identity, sequence, mail, state, receiptConfirmed, submittedAt, updatedAt,
                    retryAfter, deliveryStartedAt, admissionPosition, value, startedAt, classifiedSettings);
        }
    }

    private final Path directory;
    private final int capacity;
    private final int deliveryCapacity;
    private final Duration retention;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final MailAnalysisProcessor processor;
    private final MailAnalysisDelivery delivery;
    private final BooleanSupplier sourceReady;
    // Rebuilt from the durable job files on startup. Only active jobs participate in scheduling.
    private final Map<String, Job> jobs = new HashMap<>();
    private final Map<String, String> tokenByIdentity = new HashMap<>();
    private final NavigableSet<Job> queued = new TreeSet<>(FIFO);
    private final NavigableSet<Job> running = new TreeSet<>(FIFO);
    private final NavigableSet<Job> deliveries = new TreeSet<>(DELIVERY_ORDER);
    private final NavigableSet<Job> expiring = new TreeSet<>(Comparator.comparing(Job::retentionStart).thenComparing(FIFO));
    private final NavigableSet<Job> measured = new TreeSet<>(Comparator.comparing(Job::deliveryStartedAt).thenComparing(FIFO));
    private final Map<State, NavigableSet<Job>> active = Map.of(
            State.QUEUED, queued, State.RUNNING, running, State.DELIVERING, deliveries);
    private FileChannel lockChannel;
    private FileLock directoryLock;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> workerTask;
    private long lastSequence;
    private volatile boolean warned;
    private volatile boolean sending;
    private boolean lastClaimWasDeliveryRetry;
    // One worker owns the running job and retains at most one computed result during a storage outage.
    private MailAnalysisCommand unpersistedCommand;
    private Outcome unspooledOutcome;
    private volatile Instant runningRetryAt;
    private volatile boolean waitingForSource;
    // One preparation lane shares the classifier with execution. A successful inference survives
    // transient spool writes in memory until the command or the RUNNING transition is durable.
    private String preparingToken;
    private Prepared prepared;
    private Instant preparationRetryAt;
    private final Set<String> failedPreparations = new HashSet<>();

    @Autowired
    public MailAnalysisQueue(
            @Value("${taxonomy.mail.queue.directory:./data/mail-analysis}") Path directory,
            @Value("${taxonomy.mail.queue.capacity:16}") int capacity,
            @Value("${taxonomy.mail.queue.retention:PT48H}") Duration retention,
            ObjectMapper mapper, MailAnalysisProcessor processor, MailAnalysisDelivery delivery) {
        this(directory, capacity, retention, mapper, processor, delivery, Clock.systemUTC(), processor::sourceReady);
    }

    MailAnalysisQueue(Path directory, int capacity, Duration retention, ObjectMapper mapper,
            MailAnalysisProcessor processor, MailAnalysisDelivery delivery, Clock clock) {
        this(directory, capacity, retention, mapper, processor, delivery, clock, () -> true);
    }

    private MailAnalysisQueue(Path directory, int capacity, Duration retention, ObjectMapper mapper,
            MailAnalysisProcessor processor, MailAnalysisDelivery delivery, Clock clock, BooleanSupplier sourceReady) {
        if (capacity < 0 || capacity > 1_000 || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("Invalid email queue capacity or retention");
        }
        this.directory = directory.toAbsolutePath().normalize();
        this.capacity = capacity;
        this.deliveryCapacity = Math.max(1, capacity);
        this.retention = retention;
        this.clock = clock;
        this.mapper = mapper;
        this.processor = processor;
        this.delivery = delivery;
        this.sourceReady = sourceReady;
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
                accepted ? waiting + 1 : null, accepted ? newExecution() : null, null, null);
        store(job);
        return submission(job, false);
    }

    private static Execution newExecution() {
        return new Execution(UUID.randomUUID().toString(), null);
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

    /**
     * One lock covers all rows and counters, so a running job cannot also appear in the waiting list.
     * Public rows contain only classified options and operational data.
     */
    public synchronized Optional<Snapshot> snapshot(String jobToken) {
        var summary = status(jobToken);
        if (summary.isEmpty()) return Optional.empty();
        Instant now = clock.instant();
        Job own = jobs.get(jobToken);
        long[] durations = measured.descendingSet().stream()
                .filter(job -> !removable(job, now))
                .limit(MAX_DURATION_SAMPLES)
                .mapToLong(job -> Math.max(1L, Duration.between(job.startedAt(), job.deliveryStartedAt()).toSeconds()))
                .sorted().toArray();
        Long typical = null;
        if (durations.length >= MIN_DURATION_SAMPLES) {
            long lower = durations[(durations.length - 1) / 2];
            typical = lower + (durations[durations.length / 2] - lower) / 2;
        }
        WorkerState worker = workerState(now);
        Long wait = worker == WorkerState.IDLE || worker == WorkerState.RUNNING ? initialWaitSeconds(typical, now) : null;
        var entries = new ArrayList<JobView>(queued.size() + running.size() + deliveries.size());
        for (Job job : running) entries.add(view(job, own, null, null));
        int position = 0;
        for (Job job : queued) {
            entries.add(view(job, own, ++position, job.receiptConfirmed() ? wait : null));
            if (job.receiptConfirmed() && wait != null) wait += typical;
        }
        for (Job job : deliveries) entries.add(view(job, own, null, null));
        JobView ownView = entries.stream().filter(JobView::own).findFirst()
                .orElseGet(() -> view(own, own, null, null));
        return Optional.of(new Snapshot(summary.get(), now, worker != WorkerState.STOPPED, 1, worker, durations.length,
                typical, ownView, List.copyOf(entries)));
    }

    private WorkerState workerState(Instant now) {
        if (workerTask == null || workerTask.isDone() || executor.isShutdown()) return WorkerState.STOPPED;
        if (sending) return WorkerState.DELIVERING;
        if (waitingForSource && (!queued.isEmpty() || !running.isEmpty())) return WorkerState.WAITING_FOR_SOURCE;
        if (warned || runningRetryAt != null && runningRetryAt.isAfter(now)) return WorkerState.RETRYING;
        if (!running.isEmpty()) return WorkerState.RUNNING;
        if (!deliveries.isEmpty()) return dueDelivery(now) == null ? WorkerState.RETRYING : WorkerState.DELIVERING;
        return preparingToken == null ? WorkerState.IDLE : WorkerState.RUNNING;
    }

    private Long initialWaitSeconds(Long typical, Instant now) {
        if (typical == null || waitingForSource || runningRetryAt != null || !deliveries.isEmpty()) return null;
        if (running.isEmpty()) return preparingToken == null ? 0L : null;
        Instant start = running.first().startedAt();
        if (start == null) return null;
        long elapsed = Math.max(0L, Duration.between(start, now).toSeconds());
        // An overrun has no defensible remaining-time estimate.
        return elapsed < typical ? typical - elapsed : null;
    }

    private static JobView view(Job job, Job own, Integer position, Long wait) {
        boolean owner = job.token().equals(own.token());
        var options = job.settings();
        var settings = options == null ? null : new SettingsView(options.mode(),
                owner ? options.taxonomyCodes() : List.of(), options.taxonomyCodes().size(), options.exports());
        return new JobView("TX-" + job.sequence(), owner, job.state(), position, job.submittedAt(),
                job.startedAt(), settings, wait);
    }

    private int position(Job job) {
        return 1 + queued.headSet(job).size();
    }

    @Override
    public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
        if (executor != null) return;
        executor = Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("mail-analysis-", 1).factory());
        workerTask = executor.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::prepareWaiting, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** Classify one confirmed waiting job while execution or result delivery is occupied. */
    private void prepareWaiting() {
        Job candidate;
        synchronized (this) {
            if (prepared != null) {
                if (!clock.instant().isBefore(preparationRetryAt)) persistPrepared();
                return;
            }
            if (waitingForSource || running.isEmpty() && deliveries.isEmpty()) return;
            candidate = queued.stream().filter(job -> job.receiptConfirmed() && job.settings() == null
                    && !failedPreparations.contains(job.token())).findFirst().orElse(null);
            if (candidate == null) return;
            preparingToken = candidate.token();
        }
        try {
            if (!sourceReady.getAsBoolean() || Thread.currentThread().isInterrupted()) return;
            var command = processor.classify(candidate.mail().subject(), candidate.mail().requirementText());
            synchronized (this) {
                prepared = new Prepared(candidate.token(), command);
                persistPrepared();
            }
        } catch (RuntimeException unavailable) {
            // The ordinary worker must still decide the outcome (including transient provider failures).
            synchronized (this) {
                failedPreparations.add(candidate.token());
            }
        } finally {
            synchronized (this) {
                preparingToken = null;
            }
        }
    }

    /** Called with the queue lock held. Keep the validated command if the spool is temporarily unavailable. */
    private void persistPrepared() {
        Job current = jobs.get(prepared.token());
        if (current != null && current.state() == State.QUEUED && current.settings() == null) {
            try {
                store(applyPrepared(current));
            } catch (RuntimeException unavailable) {
                if (preparationRetryAt == null) {
                    log.warn("Email job settings could not be persisted; the classified command will be retried ({})",
                            unavailable.getClass().getSimpleName());
                }
                preparationRetryAt = clock.instant().plus(STORAGE_RETRY);
                return;
            }
        }
        prepared = null;
        preparationRetryAt = null;
    }

    private Job applyPrepared(Job job) {
        Execution execution = job.execution() == null ? newExecution() : job.execution();
        return job.withExecution(new Execution(execution.id(), prepared.command()));
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
            job = stageForDelivery(job, outgoing);
            if (job == null) return;
            computed = unspooledOutcome;
            unspooledOutcome = null;
        }

        Outcome outcome;
        sending = true;
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
        } finally {
            sending = false;
        }
        update(job.update(outcome.deliveredState(), true, clock.instant(), null));
        delete(outgoing);
    }

    private Job stageForDelivery(Job job, Path outgoing) {
        try {
            // An existing stage also covers a restart between storing the result and recording DELIVERING.
            if (!Files.exists(outgoing)) {
                if (unspooledOutcome == null) unspooledOutcome = analyze(job);
                write(outgoing, unspooledOutcome);
            }
            synchronized (this) {
                // Classification persisted its command after this worker originally claimed the job.
                Job staged = jobs.get(job.token()).update(State.DELIVERING, true, clock.instant(), null);
                store(staged);
                waitingForSource = false;
                runningRetryAt = null;
                return staged;
            }
        } catch (MailAnalysisProcessor.SourceUnavailable unavailable) {
            waitingForSource = true;
            runningRetryAt = clock.instant().plus(SOURCE_RETRY);
        } catch (RuntimeException unavailable) {
            if (Thread.currentThread().isInterrupted()) throw unavailable;
            runningRetryAt = clock.instant().plus(STORAGE_RETRY);
            log.warn("Email analysis storage unavailable; staging will be retried ({})",
                    unavailable.getClass().getSimpleName());
        }
        return null;
    }

    private Outcome analyze(Job job) {
        Execution execution = job.execution();
        if (execution == null) {
            execution = newExecution();
            job = job.withExecution(execution);
            update(job);
        }
        if (execution.command() == null) {
            if (unpersistedCommand == null) {
                try {
                    unpersistedCommand = processor.classify(job.mail().subject(), job.mail().requirementText());
                } catch (RuntimeException failure) {
                    return analysisFailure(failure);
                }
            }
            execution = new Execution(execution.id(), unpersistedCommand);
            update(job.withExecution(execution));
            unpersistedCommand = null;
        }
        try {
            return processor.process(execution.id(), execution.command(), job.mail().requirementText(), sourceReady);
        } catch (RuntimeException failure) {
            return analysisFailure(failure);
        }
    }

    private static Outcome analysisFailure(RuntimeException failure) {
        if (failure instanceof MailAnalysisProcessor.SourceUnavailable || Thread.currentThread().isInterrupted()) throw failure;
        if (failure instanceof MailAnalysisProcessor.Rejected rejection) return Outcome.failed(rejection.getMessage());
        log.warn("Email analysis could not be completed ({})", failure.getClass().getSimpleName());
        return Outcome.failed("Die Analyse oder der Export konnte nicht fertiggestellt werden.");
    }

    private Job claimNext() {
        Job candidate = nextCandidate();
        if (candidate == null) return null;
        // Probe catalogue/Git readiness outside the queue lock so mailbox intake and status can continue.
        if (needsSource(candidate)) {
            waitingForSource = !sourceReady.getAsBoolean();
            if (waitingForSource) return claimDueDelivery();
        } else if (candidate.state() == State.RUNNING) {
            // The persisted command retries the source inside the processor itself.
            waitingForSource = false;
        }
        if (candidate.state() != State.QUEUED) return candidate;
        synchronized (this) {
            if (candidate.token().equals(preparingToken)) return null;
            // A completed preparation may have changed this row while the source probe ran.
            Job latest = jobs.get(candidate.token());
            if (latest == null || latest.state() != State.QUEUED) return null;
            boolean pending = prepared != null && prepared.token().equals(latest.token());
            Job claimed = pending ? applyPrepared(latest) : latest;
            Job started = claimed.update(State.RUNNING, true, clock.instant(), null);
            store(started);
            if (pending) {
                prepared = null;
                preparationRetryAt = null;
            }
            lastClaimWasDeliveryRetry = false;
            return started;
        }
    }

    private boolean needsSource(Job job) {
        if (job.state() == State.QUEUED) return true;
        return job.state() == State.RUNNING && (job.execution() == null || job.execution().command() == null)
                && unpersistedCommand == null && unspooledOutcome == null && !Files.exists(file(job.token(), ".outgoing"));
    }

    private synchronized Job nextCandidate() {
        pruneExpired();
        Instant now = clock.instant();
        Job due = dueDelivery(now);
        if (!running.isEmpty()) {
            return runningRetryAt == null || !runningRetryAt.isAfter(now) ? running.first() : claimDelivery(due);
        }
        Job waiting = deliveries.size() < deliveryCapacity
                ? queued.stream().filter(Job::receiptConfirmed).findFirst().orElse(null) : null;
        // Long SMTP timeouts across several due retries must still leave turns for admitted analyses.
        if (due != null && (waiting == null || !lastClaimWasDeliveryRetry)) {
            return claimDelivery(due);
        }
        return waiting;
    }

    private synchronized Job claimDueDelivery() {
        return claimDelivery(dueDelivery(clock.instant()));
    }

    private Job dueDelivery(Instant now) {
        Job due = deliveries.isEmpty() ? null : deliveries.first();
        return due != null && due.retryAfter() != null && due.retryAfter().isAfter(now) ? null : due;
    }

    private Job claimDelivery(Job due) {
        if (due != null) lastClaimWasDeliveryRetry = true;
        return due;
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
        if (job.state() != State.QUEUED) failedPreparations.remove(job.token());
        if (job.retentionStart() != null) expiring.add(job);
        if (hasMeasuredDuration(job)) measured.add(job);
    }

    private void removeFromSchedule(Job job) {
        var stateJobs = active.get(job.state());
        if (stateJobs != null) stateJobs.remove(job);
        if (job.retentionStart() != null) expiring.remove(job);
        if (hasMeasuredDuration(job)) measured.remove(job);
    }

    private static boolean hasMeasuredDuration(Job job) {
        return (job.state() == State.COMPLETED || job.state() == State.PARTIAL)
                && job.startedAt() != null && job.deliveryStartedAt() != null
                && !job.deliveryStartedAt().isBefore(job.startedAt());
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

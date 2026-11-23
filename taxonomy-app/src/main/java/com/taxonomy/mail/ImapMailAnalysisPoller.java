package com.taxonomy.mail;

import jakarta.annotation.PreDestroy;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.UIDFolder;
import jakarta.mail.event.ConnectionAdapter;
import jakarta.mail.event.ConnectionEvent;
import jakarta.mail.event.MessageCountAdapter;
import jakarta.mail.event.MessageCountEvent;
import jakarta.mail.search.FlagTerm;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.imap.IdleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Collects unread requests on IMAP IDLE notifications or a timed scan on older servers. */
final class ImapMailAnalysisPoller {

    private static final Logger log = LoggerFactory.getLogger(ImapMailAnalysisPoller.class);

    // RFC 2177 recommends renewing IDLE at least every 29 minutes.
    private static final Duration IDLE_RENEWAL = Duration.ofMinutes(25);
    private static final Duration MAX_RECONNECT_DELAY = Duration.ofSeconds(5);
    private final MailTransportProperties.Imap connection;
    private final Session session;
    private final MailRequestParser parser;
    private final MailAnalysisQueue queue;
    private final SmtpMailAnalysisDelivery delivery;
    private Thread listener;

    ImapMailAnalysisPoller(MailTransportProperties.Imap connection, MailRequestParser parser,
                           MailAnalysisQueue queue, SmtpMailAnalysisDelivery delivery) {
        this.connection = connection;
        this.parser = parser;
        this.queue = queue;
        this.delivery = delivery;
        var properties = SmtpMailAnalysisDelivery.connectionProperties("imaps");
        properties.setProperty("mail.imaps.peek", "true");
        properties.setProperty("mail.imaps.usesocketchannels", "true");
        session = Session.getInstance(properties);
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void start() {
        if (listener == null) {
            listener = Thread.ofVirtual().name("mail-analysis-imap").start(this::listen);
        }
    }

    @PreDestroy
    synchronized void stop() throws InterruptedException {
        if (listener != null) {
            listener.interrupt();
            if (!listener.join(Duration.ofSeconds(10))) {
                log.warn("Taxonomy mail listener is still closing its connection");
            }
        }
    }

    private void listen() {
        try (var notifications = Executors.newVirtualThreadPerTaskExecutor()) {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(receive(notifications));
            }
        } catch (InterruptedException shutdown) {
            Thread.currentThread().interrupt();
        }
    }

    private Duration receive(Executor notifications) throws InterruptedException {
        try (var store = (IMAPStore) session.getStore("imaps")) {
            store.connect(connection.host(), connection.port(), connection.username(), connection.password());
            IMAPFolder folder = (IMAPFolder) store.getFolder(connection.folder());
            folder.open(Folder.READ_WRITE);
            try {
                if (folder.getMode() != Folder.READ_WRITE || !folder.getPermanentFlags().contains(Flags.Flag.SEEN)) {
                    log.warn("Taxonomy mail intake requires a folder with writable seen flags");
                    return retryDelay();
                }
                long generation = folder.getUIDValidity();
                if (generation < 1) {
                    log.warn("Taxonomy mail folder has no persistent IMAP generation");
                    return retryDelay();
                }
                if (store.hasCapability("IDLE")) {
                    watch(folder, generation, notifications);
                } else {
                    collectUnread(folder, generation);
                }
            } finally {
                folder.close(false);
            }
            return connection.pollInterval();
        } catch (MessagingException | IOException | RuntimeException unavailable) {
            if (!Thread.currentThread().isInterrupted()) {
                log.warn("Taxonomy mail intake will reconnect after a connection or mailbox failure ({})",
                        unavailable.getClass().getSimpleName());
            }
            return retryDelay();
        }
    }

    private Duration retryDelay() {
        Duration interval = connection.pollInterval();
        return interval.compareTo(MAX_RECONNECT_DELAY) < 0 ? interval : MAX_RECONNECT_DELAY;
    }

    private void watch(IMAPFolder folder, long generation, Executor notifications)
            throws IOException, MessagingException, InterruptedException {
        var changed = new Semaphore(0);
        folder.addMessageCountListener(new MessageCountAdapter() {
            @Override
            public void messagesAdded(MessageCountEvent event) { changed.release(); }
        });
        folder.addConnectionListener(new ConnectionAdapter() {
            @Override
            public void closed(ConnectionEvent event) { changed.release(); }

            @Override
            public void disconnected(ConnectionEvent event) { changed.release(); }
        });
        var idle = new IdleManager(session, notifications);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                changed.drainPermits();
                boolean handled = collectUnread(folder, generation);
                idle.watch(folder);
                Duration recheck = handled ? IDLE_RENEWAL : retryDelay();
                changed.tryAcquire(recheck.toMillis(), TimeUnit.MILLISECONDS);
            }
        } finally {
            idle.stop();
        }
    }

    private boolean collectUnread(IMAPFolder folder, long generation) throws MessagingException {
        Message[] unread = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        var metadata = new FetchProfile();
        metadata.add(FetchProfile.Item.ENVELOPE);
        metadata.add(FetchProfile.Item.SIZE);
        metadata.add(UIDFolder.FetchProfileItem.UID);
        boolean handled = true;
        int batchSize = connection.batchSize();
        for (int start = 0; start < unread.length && !Thread.currentThread().isInterrupted(); start += batchSize) {
            Message[] batch = Arrays.copyOfRange(unread, start, Math.min(unread.length, start + batchSize));
            folder.fetch(batch, metadata);
            for (Message message : batch) {
                if (Thread.currentThread().isInterrupted()) return false;
                if (!process(folder, generation, message)) handled = false;
            }
        }
        return handled;
    }

    private boolean process(UIDFolder identity, long generation, Message message) {
        try {
            String sender = parser.acceptedSender(message);
            if (sender != null) {
                long uid = identity.getUID(message);
                if (uid < 1) {
                    log.warn("Taxonomy mail message has no persistent IMAP identity");
                    return false;
                }
                var subject = message.getSubject();
                try {
                    if (message.getSize() < 0) {
                        throw new MailRequestParser.Rejected("Die Nachrichtengröße konnte nicht überprüft werden.");
                    }
                    var request = parser.parse(messageIdentity(generation, uid), sender, message);
                    MailAnalysisQueue.Submission reply = queue.submit(request);
                    if (!reply.receiptConfirmed()) {
                        if (reply.accepted()) {
                            delivery.sendReceipt(sender, request.subject(), reply.jobToken(), reply.position());
                        } else {
                            delivery.sendRejection(sender, request.subject(), reply.rejectionReason());
                        }
                        queue.confirmReceipt(reply.jobToken());
                    }
                } catch (MailRequestParser.Rejected rejected) {
                    delivery.sendRejection(sender, subject, rejected.getMessage());
                } catch (jakarta.mail.internet.ParseException invalidMime) {
                    delivery.sendRejection(sender, subject, "Die MIME-Struktur der Nachricht konnte nicht gelesen werden.");
                }
            }
            message.setFlag(Flags.Flag.SEEN, true);
            return true;
        } catch (MessagingException | IOException | RuntimeException unavailable) {
            log.warn("Taxonomy mail message remains unread for retry after processing or acknowledgment failed ({})",
                    unavailable.getClass().getSimpleName());
            return false;
        }
    }

    private String messageIdentity(long generation, long uid) {
        String identity = connection.host().toLowerCase(Locale.ROOT) + ':' + connection.port() + '\n'
                + connection.username() + '\n' + connection.folder() + '\n' + generation + '/' + uid;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", unavailable);
        }
    }
}

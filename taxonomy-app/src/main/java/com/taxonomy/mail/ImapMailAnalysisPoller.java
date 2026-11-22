package com.taxonomy.mail;

import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.UIDFolder;
import jakarta.mail.search.FlagTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

final class ImapMailAnalysisPoller {

    private static final Logger log = LoggerFactory.getLogger(ImapMailAnalysisPoller.class);

    private final MailTransportProperties.Imap connection;
    private final Session session;
    private final MailRequestParser parser;
    private final MailAnalysisQueue queue;
    private final SmtpMailAnalysisDelivery delivery;

    ImapMailAnalysisPoller(MailTransportProperties.Imap connection, MailRequestParser parser,
                           MailAnalysisQueue queue, SmtpMailAnalysisDelivery delivery) {
        this.connection = connection;
        this.parser = parser;
        this.queue = queue;
        this.delivery = delivery;
        var properties = SmtpMailAnalysisDelivery.connectionProperties("imaps");
        properties.setProperty("mail.imaps.peek", "true");
        session = Session.getInstance(properties);
    }

    @Scheduled(fixedDelayString = "${taxonomy.mail.imap.poll-interval:PT30S}")
    void collect() {
        try (var store = session.getStore("imaps")) {
            store.connect(connection.host(), connection.port(), connection.username(), connection.password());
            Folder folder = store.getFolder(connection.folder());
            if (!(folder instanceof UIDFolder identity)) {
                log.warn("Taxonomy mail folder does not provide persistent IMAP message identities");
                return;
            }
            folder.open(Folder.READ_WRITE);
            try {
                if (folder.getMode() != Folder.READ_WRITE || !folder.getPermanentFlags().contains(Flags.Flag.SEEN)) {
                    log.warn("Taxonomy mail intake requires a folder with writable seen flags");
                    return;
                }
                long generation = identity.getUIDValidity();
                if (generation < 1) {
                    log.warn("Taxonomy mail folder has no persistent IMAP generation");
                    return;
                }
                Message[] unread = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                Message[] batch = Arrays.copyOf(unread, Math.min(unread.length, connection.batchSize()));
                if (batch.length > 0) {
                    var metadata = new FetchProfile();
                    metadata.add(FetchProfile.Item.ENVELOPE);
                    metadata.add(FetchProfile.Item.SIZE);
                    metadata.add(UIDFolder.FetchProfileItem.UID);
                    folder.fetch(batch, metadata);
                }
                for (Message message : batch) {
                    process(identity, generation, message);
                }
            } finally {
                folder.close(false);
            }
        } catch (MessagingException | RuntimeException unavailable) {
            log.warn("Taxonomy mail intake could not complete this poll and will retry ({})",
                    unavailable.getClass().getSimpleName());
        }
    }

    private void process(UIDFolder identity, long generation, Message message) {
        try {
            String sender = parser.acceptedSender(message);
            if (sender != null) {
                long uid = identity.getUID(message);
                if (uid < 1) {
                    log.warn("Taxonomy mail message has no persistent IMAP identity");
                    return;
                }
                var subject = message.getSubject();
                try {
                    if (message.getSize() < 0) {
                        throw new MailRequestParser.Rejected("Die Nachrichtengröße konnte nicht überprüft werden.");
                    }
                    var request = parser.parse(messageIdentity(generation, uid), sender, message);
                    MailAnalysisQueue.Submission reply = queue.submit(request);
                    if (reply.accepted()) {
                        delivery.sendReceipt(sender, request.subject(), reply.jobToken(), reply.position());
                        queue.confirmReceipt(reply.jobToken());
                    } else {
                        delivery.sendRejection(sender, request.subject(), reply.rejectionReason());
                    }
                } catch (MailRequestParser.Rejected rejected) {
                    delivery.sendRejection(sender, subject, rejected.getMessage());
                } catch (jakarta.mail.internet.ParseException invalidMime) {
                    delivery.sendRejection(sender, subject, "Die MIME-Struktur der Nachricht konnte nicht gelesen werden.");
                }
            }
            message.setFlag(Flags.Flag.SEEN, true);
        } catch (MessagingException | IOException | RuntimeException unavailable) {
            log.warn("Taxonomy mail message remains unread for retry after processing or acknowledgment failed ({})",
                    unavailable.getClass().getSimpleName());
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

package com.taxonomy.mail;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Properties;

final class SmtpMailAnalysisDelivery implements MailAnalysisDelivery {

    private static final String UTF_8 = StandardCharsets.UTF_8.name();

    private final MailTransportProperties.Smtp connection;
    private final MailTransportProperties properties;
    private final String from;
    private final Session session;

    SmtpMailAnalysisDelivery(MailTransportProperties properties) {
        connection = properties.smtp();
        this.properties = properties;
        from = MailRequestParser.canonicalAddress(connection.from());
        Properties protocol = connectionProperties("smtp");
        protocol.setProperty("mail.smtp.auth", "true");
        if (connection.implicitTls()) {
            protocol.setProperty("mail.smtp.ssl.enable", "true");
        } else {
            protocol.setProperty("mail.smtp.starttls.enable", "true");
            protocol.setProperty("mail.smtp.starttls.required", "true");
        }
        session = Session.getInstance(protocol);
    }

    static Properties connectionProperties(String protocol) {
        var settings = new Properties();
        String prefix = "mail." + protocol + ".";
        settings.setProperty(prefix + "connectiontimeout", "10000");
        settings.setProperty(prefix + "timeout", "30000");
        settings.setProperty(prefix + "writetimeout", "30000");
        settings.setProperty(prefix + "ssl.checkserveridentity", "true");
        return settings;
    }

    void sendReceipt(String recipient, String subject, String jobToken, Integer queuePosition) {
        String position = queuePosition == null || queuePosition < 1
                ? "Der Auftrag wird bearbeitet oder ist bereits abgeschlossen."
                : "Position in der Warteschlange bei Eingang: " + queuePosition + ".";
        send(recipient, "Taxonomy · Auftrag eingegangen", subject,
                position + "\n\nLive-Status Ihres Auftrags:\n" + properties.statusLink(jobToken), List.of());
    }

    void sendRejection(String recipient, String subject, String message) {
        send(recipient, "Taxonomy · Auftrag nicht angenommen", subject, message, List.of());
    }

    @Override
    public void sendResult(String recipient, String subject, String summary, List<MailAnalysisAttachment> attachments) {
        send(recipient, "Taxonomy · Ergebnis", subject, summary, attachments);
    }

    @Override
    public void sendFailure(String recipient, String subject, String message) {
        send(recipient, "Taxonomy · Auftrag fehlgeschlagen", subject, message, List.of());
    }

    private void send(String recipient, String title, String originalSubject, String text,
                      List<MailAnalysisAttachment> attachments) {
        try (var transport = session.getTransport("smtp")) {
            MimeMessage reply = buildReply(recipient, title, originalSubject, text, attachments);
            transport.connect(connection.host(), connection.port(), connection.username(), connection.password());
            transport.sendMessage(reply, reply.getAllRecipients());
        } catch (MessagingException failed) {
            throw new MailDeliveryException(failed);
        }
    }

    MimeMessage buildReply(String recipient, String title, String originalSubject, String text,
                           List<MailAnalysisAttachment> attachments) throws MessagingException {
        var reply = new MimeMessage(session);
        reply.setFrom(new InternetAddress(from, true));
        reply.setRecipient(Message.RecipientType.TO, new InternetAddress(MailRequestParser.canonicalAddress(recipient), true));
        String cleanSubject = originalSubject == null ? "" : originalSubject.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (cleanSubject.length() > 180) {
            cleanSubject = cleanSubject.substring(0, 180) + "…";
        }
        reply.setSubject(cleanSubject.isEmpty() ? title : title + " — " + cleanSubject, UTF_8);
        reply.setSentDate(new Date());
        reply.setHeader("Auto-Submitted", "auto-replied");
        reply.setHeader("X-Auto-Response-Suppress", "All");
        String message = text == null || text.isBlank() ? "Der Auftrag konnte nicht verarbeitet werden." : text;
        if (attachments.isEmpty()) {
            reply.setText(message, UTF_8);
        } else {
            var contents = new MimeMultipart("mixed");
            var body = new MimeBodyPart();
            body.setText(message, UTF_8);
            contents.addBodyPart(body);
            for (MailAnalysisAttachment result : attachments) {
                var file = new MimeBodyPart();
                file.setDataHandler(new DataHandler(new ByteArrayDataSource(result.content(), result.contentType())));
                file.setFileName(MailRequestParser.cleanFilename(result.filename()));
                contents.addBodyPart(file);
            }
            reply.setContent(contents);
        }
        reply.saveChanges();
        return reply;
    }

    static final class MailDeliveryException extends RuntimeException {
        MailDeliveryException(MessagingException cause) {
            super("Taxonomy mail delivery failed", cause);
        }
    }
}

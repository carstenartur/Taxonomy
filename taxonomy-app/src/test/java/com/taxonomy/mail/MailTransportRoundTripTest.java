package com.taxonomy.mail;

import com.icegreen.greenmail.imap.ImapServer;
import com.icegreen.greenmail.util.DummySSLServerSocketFactory;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.taxonomy.provenance.service.DocumentParserService;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Runs the application's IMAPS collector and authenticated SMTPS sender on loopback. */
@Isolated("Installs a temporary TLS context for the local mail servers")
class MailTransportRoundTripTest {
    private static final String INBOX = "taxonomy@example.test";
    private static final String AUTHOR = "writer@example.test";
    private static final String PASSWORD = "test-password";
    private static final String SUBJECT = "Entscheidungsbaum BP als HTML";
    private static final String BODY = "Die Fachstelle prüft die Anfragen.";
    private static final String EXTRA = "Eine Ausnahme braucht eine Freigabe.";
    private static final String REQUIREMENT = BODY + "\n\nAnhang: Vorgabe.txt\n" + EXTRA;
    private static final String KEYSTORE_PROPERTY = DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_FILE_PROPERTY;

    @TempDir Path directory;

    @Test
    void receivesAcknowledgesRejectsAndReturnsRequestedFileAcrossTls() throws Exception {
        var previousContext = SSLContext.getDefault();
        var previousKeystore = System.getProperty(KEYSTORE_PROPERTY);
        var servers = new GreenMail(new ServerSetup[] {
                new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS),
                new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTPS)
        });
        try {
            configureLocalTls();
            servers.start();
            exerciseMailbox(servers);
        } finally {
            try {
                servers.stop();
            } finally {
                SSLContext.setDefault(previousContext);
                if (previousKeystore == null) System.clearProperty(KEYSTORE_PROPERTY);
                else System.setProperty(KEYSTORE_PROPERTY, previousKeystore);
            }
        }
    }

    private void exerciseMailbox(GreenMail servers) throws Exception {
        var inbox = servers.setUser(INBOX, PASSWORD);
        var recipient = servers.setUser(AUTHOR, PASSWORD);
        var properties = new MailTransportProperties(URI.create("https://example.test/taxonomy"), List.of(AUTHOR),
                1_000_000, 4, 5000, new MailTransportProperties.Imap("127.0.0.1", servers.getImaps().getPort(),
                INBOX, PASSWORD, "INBOX", Duration.ofMinutes(2), 20, true),
                new MailTransportProperties.Smtp("127.0.0.1", servers.getSmtps().getPort(), INBOX, PASSWORD, INBOX, true));
        var delivery = new SmtpMailAnalysisDelivery(properties);
        var processor = mock(MailAnalysisProcessor.class);
        var command = new MailAnalysisCommand(MailAnalysisCommand.Mode.DECISION_TREE,
                List.of("BP"), List.of(MailAnalysisCommand.Format.HTML));
        byte[] result = "<html><body>BP</body></html>".getBytes(StandardCharsets.UTF_8);
        when(processor.classify(SUBJECT, REQUIREMENT)).thenReturn(command);
        when(processor.process(anyString(), eq(command), eq(REQUIREMENT), any())).thenReturn(new MailAnalysisQueue.Outcome("Fertig",
                List.of(new MailAnalysisAttachment("entscheidung.html", "text/html", result)), true));

        var queue = new MailAnalysisQueue(directory.resolve("queue"), 1, Duration.ofHours(1),
                JsonMapper.builder().build(), processor, delivery, Clock.systemUTC());
        var collector = new ImapMailAnalysisPoller(properties.imap(), new MailRequestParser(
                new DocumentParserService(), properties), queue, delivery);
        try {
            var store = servers.getManagers().getImapHostManager();
            var received = store.getInbox(recipient);
            inbox.deliver(request(SUBJECT));
            collector.start();
            await().atMost(Duration.ofSeconds(10)).until(() -> received.getMessageCount() == 1);
            // A second arrival after the first mailbox scan must wake IDLE, well before the 2-minute fallback.
            inbox.deliver(request("Anderer Auftrag"));
            await().atMost(Duration.ofSeconds(10)).until(() -> received.getMessageCount() == 2);
            var receipt = received.getMessages().get(0).getMimeMessage();
            assertThat(receipt.getContent().toString()).contains("Warteschlange bei Eingang: 1",
                    "https://example.test/taxonomy/mail-analysis/jobs/");
            assertThat(received.getMessages().get(1).getMimeMessage().getContent().toString())
                    .contains("Warteschlange ist voll");
            queue.processNext();
            assertThat(received.getMessages()).hasSize(3);
            var exported = (Multipart) received.getMessages().get(2).getMimeMessage().getContent();
            assertThat(exported.getCount()).isEqualTo(2);
            assertThat(exported.getBodyPart(1).getFileName()).isEqualTo("entscheidung.html");
            try (var attachment = exported.getBodyPart(1).getInputStream()) {
                assertThat(attachment.readAllBytes()).isEqualTo(result);
            }

            // Simulate a lost IMAP flag write after both SMTP replies reached the sender.
            for (var original : store.getInbox(inbox).getMessages()) {
                assertThat(original.getFlags().contains(Flags.Flag.SEEN)).isTrue();
                original.setFlag(Flags.Flag.SEEN, false);
            }
            // Only an arrival wakes IDLE. The notification message is from a non-allowlisted sender.
            var notification = request("Notify");
            notification.setFrom(new InternetAddress("unregistered@example.test"));
            notification.saveChanges();
            inbox.deliver(notification);
            await().atMost(Duration.ofSeconds(10)).until(() -> store.getInbox(inbox).getMessages().stream()
                    .allMatch(mail -> mail.getFlags().contains(Flags.Flag.SEEN)));
            queue.processNext();
            assertThat(received.getMessages()).hasSize(3);
            verify(processor).classify(SUBJECT, REQUIREMENT);
            verify(processor).process(anyString(), eq(command), eq(REQUIREMENT), any());
            verifyNoMoreInteractions(processor);

            // Drop the server-side IDLE connection. Leave the message in the same persistent mailbox.
            var replacement = new ImapServer(servers.getImaps().getServerSetup(), servers.getManagers());
            servers.getImaps().stopService();
            inbox.deliver(request("Auftrag während Verbindungsunterbrechung"));
            replacement.startService();
            try {
                await().atMost(Duration.ofSeconds(15)).until(() -> received.getMessageCount() == 4);
                assertThat(received.getMessages().get(3).getMimeMessage().getContent().toString())
                        .contains("Warteschlange bei Eingang: 1");
                collector.stop();
            } finally {
                replacement.stopService();
            }
        } finally {
            collector.stop();
            queue.destroy();
        }
    }

    private static MimeMessage request(String subject) throws Exception {
        var message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(AUTHOR));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(INBOX));
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        var body = new MimeBodyPart();
        body.setText(BODY, StandardCharsets.UTF_8.name());
        var file = new MimeBodyPart();
        file.setText(EXTRA, StandardCharsets.UTF_8.name());
        file.setFileName("Vorgabe.txt");
        var parts = new MimeMultipart();
        parts.addBodyPart(body);
        parts.addBodyPart(file);
        message.setContent(parts);
        message.saveChanges();
        return message;
    }

    private void configureLocalTls() throws Exception {
        Path certificate = directory.resolve("localhost.p12");
        var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        var generated = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "localhost",
                "-keyalg", "EC", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", certificate.toString(), "-storepass", "changeit",
                "-validity", "1").redirectErrorStream(true).start();
        String output;
        try (var stream = generated.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(generated.waitFor()).as(output).isZero();
        var trusted = KeyStore.getInstance(certificate.toFile(), "changeit".toCharArray());
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trusted);
        var context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        SSLContext.setDefault(context);
        System.setProperty(KEYSTORE_PROPERTY, certificate.toString());
    }
}

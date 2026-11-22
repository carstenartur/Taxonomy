package com.taxonomy.mail;

import com.taxonomy.provenance.service.DocumentParserService;
import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailRequestParserTest {

    private static final String SENDER = "author@example.test";
    private static final Session SESSION = Session.getInstance(new Properties());

    @Test
    void prefersPlainBodyAndIncludesFullDocxAndMarkdownAttachments() throws Exception {
        var documentBytes = new ByteArrayOutputStream();
        try (var document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Der lange Anhang enthält eindeutige Vorgaben.");
            document.write(documentBytes);
        }
        MimeMessage original = message();
        var alternative = new MimeMultipart("alternative");
        var html = new MimeBodyPart();
        html.setContent("<p>Andere Repräsentation</p>", "text/html; charset=UTF-8");
        alternative.addBodyPart(html);
        var text = new MimeBodyPart();
        text.setText("Die Fachstelle erstellt die Auswertung.", "UTF-8");
        alternative.addBodyPart(text);
        var body = new MimeBodyPart();
        body.setContent(alternative);
        var mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(body);
        var docx = new MimeBodyPart();
        docx.setFileName("Vorgaben.docx");
        docx.setDataHandler(new DataHandler(new ByteArrayDataSource(documentBytes.toByteArray(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document")));
        mixed.addBodyPart(docx);
        var markdown = new MimeBodyPart();
        markdown.setText("# Sonderfall\nArchitekturprüfung", "UTF-8");
        markdown.setFileName("Abgrenzung.md");
        mixed.addBodyPart(markdown);
        original.setContent(mixed);

        MimeMessage incoming = reread(original);
        var parser = new MailRequestParser(new DocumentParserService(), properties(5000));
        var request = parser.parse("uid", parser.acceptedSender(incoming), incoming);

        assertThat(request.sender()).isEqualTo(SENDER);
        assertThat(request.subject()).isEqualTo("Architektur als PDF erstellen");
        assertThat(request.requirementText()).startsWith("Die Fachstelle erstellt die Auswertung.")
                .contains("Anhang: Vorgaben.docx", "Der lange Anhang enthält eindeutige Vorgaben.",
                        "Anhang: Abgrenzung.md", "# Sonderfall\nArchitekturprüfung")
                .doesNotContain("Andere Repräsentation");
    }

    @Test
    void handlesHtmlOnlyBodyWithoutExecutingOrIncludingScript() throws Exception {
        var source = message();
        source.setContent("<p>Erster Teil</p><p>Zweiter Teil<script>ignore_me()</script></p>",
                "text/html; charset=UTF-8");
        var parser = new MailRequestParser(new DocumentParserService(), properties(5000));
        assertThat(parser.parse("uid", SENDER, reread(source)).requirementText()).isEqualTo("Erster Teil Zweiter Teil");
    }

    @Test
    void usesOnlyApprovedFromAddressAndSkipsAutomatedReplies() throws Exception {
        var parser = new MailRequestParser(new DocumentParserService(), properties(5000));
        var source = message();
        source.setReplyTo(new InternetAddress[]{new InternetAddress("do-not-use@example.test")});
        assertThat(parser.acceptedSender(source)).isEqualTo(SENDER);
        source.setHeader("Auto-Submitted", "auto-replied");
        assertThat(parser.acceptedSender(source)).isNull();
        source.removeHeader("Auto-Submitted");
        source.setFrom(new InternetAddress("unapproved@example.test"));
        assertThat(parser.acceptedSender(source)).isNull();
        source.setFrom(new InternetAddress("taxonomy@example.test"));
        assertThat(parser.acceptedSender(source)).isNull();
    }

    @Test
    void rejectsOversizedTextWithoutSilentlyTruncatingRequirements() throws Exception {
        var source = message();
        source.setText("Äußerst lange Vorgabe. ".repeat(10), "UTF-8");
        var parser = new MailRequestParser(new DocumentParserService(), properties(100));
        MimeMessage incoming = reread(source);
        assertThatThrownBy(() -> parser.parse("uid", SENDER, incoming)).isInstanceOf(MailRequestParser.Rejected.class)
                .hasMessageContaining("länger als erlaubt");
    }

    private static MimeMessage message() throws Exception {
        var source = new MimeMessage(SESSION);
        source.setFrom(new InternetAddress(SENDER));
        source.setSubject("Architektur als PDF erstellen", "UTF-8");
        return source;
    }

    private static MimeMessage reread(MimeMessage source) throws Exception {
        var bytes = new ByteArrayOutputStream();
        source.writeTo(bytes);
        return new MimeMessage(SESSION, new ByteArrayInputStream(bytes.toByteArray()));
    }

    static MailTransportProperties properties(int characterLimit) {
        return new MailTransportProperties(URI.create("https://example.test/taxonomy/"),
                List.of(SENDER, "taxonomy@example.test"), 10485760, 8, characterLimit,
                new MailTransportProperties.Imap("imap.example.test", 993, "account", "fixture-password",
                        "INBOX", Duration.ofSeconds(30), 20, true),
                new MailTransportProperties.Smtp("smtp.example.test", 587, "account", "fixture-password",
                        "taxonomy@example.test", false));
    }
}

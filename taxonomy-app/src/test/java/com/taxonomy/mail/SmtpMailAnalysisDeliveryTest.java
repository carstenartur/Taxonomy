package com.taxonomy.mail;

import jakarta.mail.Message;
import jakarta.mail.Multipart;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SmtpMailAnalysisDeliveryTest {

    @Test
    void composesResultWithLoopSuppressionAndExportAttachment() throws Exception {
        var delivery = new SmtpMailAnalysisDelivery(MailRequestParserTest.properties(5000));
        byte[] export = "Diagramm".getBytes(StandardCharsets.UTF_8);
        var result = delivery.buildReply("author@example.test", "Taxonomy · Ergebnis", "Anforderung\r\nBcc: example.test",
                "Teilweise abgeschlossen", List.of(new MailAnalysisAttachment("Entscheidungsbaum.svg", "image/svg+xml", export)));

        assertThat(result.getRecipients(Message.RecipientType.TO)).hasSize(1);
        assertThat(result.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(result.getHeader("Auto-Submitted")).containsExactly("auto-replied");
        assertThat(result.getSubject()).doesNotContain("\n", "\r");
        var content = (Multipart) result.getContent();
        assertThat(content.getCount()).isEqualTo(2);
        assertThat(content.getBodyPart(0).getContent()).isEqualTo("Teilweise abgeschlossen");
        assertThat(content.getBodyPart(1).getFileName()).isEqualTo("Entscheidungsbaum.svg");
        try (var bytes = content.getBodyPart(1).getInputStream()) {
            assertThat(bytes.readAllBytes()).isEqualTo(export);
        }
    }
}

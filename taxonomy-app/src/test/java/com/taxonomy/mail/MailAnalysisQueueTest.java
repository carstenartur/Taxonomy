package com.taxonomy.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class MailAnalysisQueueTest {
    private static final Instant NOW = Instant.parse("2026-11-22T08:00:00Z");
    private static final Duration RETENTION = Duration.ofHours(48);
    private static final IncomingMail FIRST = new IncomingMail("uid-1", "sender@example.test", "Architektur", "Anforderung 1");
    private static final IncomingMail SECOND = new IncomingMail("uid-2", "second@example.test", "Entscheidungsbaum", "Anforderung 2");

    @TempDir Path directory;

    private final MailAnalysisProcessor processor = mock(MailAnalysisProcessor.class);
    private final MailAnalysisDelivery delivery = mock(MailAnalysisDelivery.class);

    @Test
    void admissionRequiresReceiptAndReusesTheOriginalTokenAfterRestart() throws Exception {
        String token;
        var attachment = new MailAnalysisAttachment("architecture.pdf", "application/pdf", new byte[] {1, 2, 3});
        when(processor.process(FIRST.subject(), FIRST.requirementText()))
                .thenReturn(new MailAnalysisQueue.Outcome("Fertig", List.of(attachment), true));

        var before = queue(1, NOW);
        try {
            var admission = before.submit(FIRST);
            token = admission.jobToken();
            assertThat(admission.position()).isEqualTo(1);
            assertThat(before.submit(SECOND).accepted()).isFalse();
            assertThat(before.status(token).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.QUEUED);
            before.processNext();
            verifyNoInteractions(processor, delivery);
            assertThatThrownBy(() -> queue(1, NOW)).isInstanceOf(IllegalStateException.class);
        } finally {
            before.destroy();
        }
        var after = queue(1, NOW);
        try {
            var duplicate = after.submit(FIRST);
            assertThat(duplicate.duplicate()).isTrue();
            assertThat(duplicate.jobToken()).isEqualTo(token);
            after.confirmReceipt(token);
            after.processNext();
            verify(delivery).sendResult(FIRST.sender(), FIRST.subject(), "Fertig", List.of(attachment));
            assertThat(after.status(token).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.COMPLETED);
            assertThat(after.status(token).orElseThrow().position()).isNull();
            assertThat(after.status(token).orElseThrow().queuedJobs()).isZero();
            assertThat(Files.readString(directory.resolve(token + ".job")))
                    .doesNotContain(FIRST.subject(), FIRST.requirementText(), FIRST.sender());
            assertThat(after.submit(SECOND).accepted()).isTrue();
        } finally {
            after.destroy();
        }
    }

    @Test
    void failedDeliveryLetsAnotherRecipientProceedAndRetriesExactBytesAfterRestart() {
        var attachment = new MailAnalysisAttachment("architecture.pdf", "application/pdf", new byte[] {1, 2, 3});
        when(processor.process(FIRST.subject(), FIRST.requirementText()))
                .thenReturn(new MailAnalysisQueue.Outcome("Fertig", List.of(attachment), true));
        when(processor.process(SECOND.subject(), SECOND.requirementText()))
                .thenReturn(MailAnalysisQueue.Outcome.failed("Fachlicher Fehler"));
        doThrow(new IllegalStateException("temporary SMTP failure")).doNothing().when(delivery)
                .sendResult(eq(FIRST.sender()), eq(FIRST.subject()), eq("Fertig"), anyList());
        String firstToken;
        String secondToken;
        var before = queue(2, NOW);
        try {
            firstToken = before.submit(FIRST).jobToken();
            var second = before.submit(SECOND);
            secondToken = second.jobToken();
            assertThat(second.position()).isEqualTo(2);
            before.confirmReceipt(firstToken);
            before.confirmReceipt(secondToken);
            before.processNext();
            assertThat(before.status(firstToken).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.DELIVERING);
            assertThat(before.status(secondToken).orElseThrow().position()).isEqualTo(1);
            assertThat(Files.exists(directory.resolve(firstToken + ".outgoing"))).isTrue();
            before.processNext();
            verify(delivery).sendFailure(SECOND.sender(), SECOND.subject(), "Fachlicher Fehler");
            assertThat(before.status(secondToken).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.FAILED);
        } finally {
            before.destroy();
        }
        var after = queue(2, NOW.plusSeconds(61));
        try {
            after.processNext();
            verify(processor, times(1)).process(FIRST.subject(), FIRST.requirementText());
            ArgumentCaptor<List<MailAnalysisAttachment>> captured = ArgumentCaptor.captor();
            verify(delivery, times(2)).sendResult(eq(FIRST.sender()), eq(FIRST.subject()), eq("Fertig"), captured.capture());
            for (var attachments : captured.getAllValues()) {
                assertThat(attachments).singleElement().satisfies(file -> {
                    assertThat(file.filename()).isEqualTo(attachment.filename());
                    assertThat(file.contentType()).isEqualTo(attachment.contentType());
                    assertThat(file.content()).containsExactly(attachment.content());
                });
            }
            assertThat(after.status(firstToken).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.COMPLETED);
            assertThat(after.status(secondToken).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.FAILED);
            assertThat(Files.exists(directory.resolve(firstToken + ".outgoing"))).isFalse();
        } finally {
            after.destroy();
        }
    }

    @Test
    void undeliverableStageExpiresWithAPublicFailureState() throws Exception {
        String token;
        when(processor.process(FIRST.subject(), FIRST.requirementText())).thenReturn(MailAnalysisQueue.Outcome.failed("Kein Ergebnis"));
        doThrow(new IllegalStateException()).when(delivery).sendFailure(FIRST.sender(), FIRST.subject(), "Kein Ergebnis");
        var before = queue(1, NOW);
        try {
            token = before.submit(FIRST).jobToken();
            before.confirmReceipt(token);
            before.processNext();
            assertThat(Files.readString(directory.resolve(token + ".job"))).doesNotContain(FIRST.requirementText());
        } finally {
            before.destroy();
        }
        var after = queue(1, NOW.plus(RETENTION));
        try {
            assertThat(after.status(token).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.FAILED);
            assertThat(Files.exists(directory.resolve(token + ".outgoing"))).isFalse();
            assertThat(Files.readString(directory.resolve(token + ".job"))).doesNotContain(FIRST.sender());
            after.processNext();
            verify(delivery, times(1)).sendFailure(FIRST.sender(), FIRST.subject(), "Kein Ergebnis");
        } finally {
            after.destroy();
        }
    }

    @Test
    void restartResumesInterruptedAnalysisBeforeAnotherQueuedJob() {
        when(processor.process(FIRST.subject(), FIRST.requirementText()))
                .thenThrow(new AssertionError("interrupted worker"))
                .thenReturn(MailAnalysisQueue.Outcome.failed("Kein Ergebnis"));
        String firstToken;
        String secondToken;
        var before = queue(2, NOW);
        try {
            firstToken = before.submit(FIRST).jobToken();
            secondToken = before.submit(SECOND).jobToken();
            before.confirmReceipt(firstToken);
            before.confirmReceipt(secondToken);
            assertThatThrownBy(before::processNext).isInstanceOf(AssertionError.class).hasMessage("interrupted worker");
            assertThat(before.status(firstToken).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.RUNNING);
        } finally {
            before.destroy();
        }
        var after = queue(2, NOW.plusSeconds(1));
        try {
            after.processNext();
            verify(delivery).sendFailure(FIRST.sender(), FIRST.subject(), "Kein Ergebnis");
            assertThat(after.status(firstToken).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.FAILED);
            assertThat(after.status(secondToken).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.QUEUED);
        } finally {
            after.destroy();
        }
    }

    @Test
    void moreThanOneDueRetryDoesNotMonopolizeTheWorker() {
        IncomingMail third = new IncomingMail("uid-3", "third@example.test", "Architektur", "Anforderung 3");
        for (var mail : List.of(FIRST, SECOND, third)) {
            when(processor.process(mail.subject(), mail.requirementText())).thenReturn(MailAnalysisQueue.Outcome.failed("Kein Ergebnis"));
        }
        for (var mail : List.of(FIRST, SECOND)) {
            doThrow(new IllegalStateException()).when(delivery).sendFailure(mail.sender(), mail.subject(), "Kein Ergebnis");
        }
        String thirdToken;
        var before = queue(3, NOW);
        try {
            for (var mail : List.of(FIRST, SECOND)) {
                before.confirmReceipt(before.submit(mail).jobToken());
                before.processNext();
            }
            thirdToken = before.submit(third).jobToken();
            before.confirmReceipt(thirdToken);
        } finally {
            before.destroy();
        }
        var after = queue(3, NOW.plusSeconds(60));
        try {
            after.processNext();
            after.processNext();
            verify(delivery, times(2)).sendFailure(FIRST.sender(), FIRST.subject(), "Kein Ergebnis");
            verify(delivery, times(1)).sendFailure(SECOND.sender(), SECOND.subject(), "Kein Ergebnis");
            verify(delivery).sendFailure(third.sender(), third.subject(), "Kein Ergebnis");
            assertThat(after.status(thirdToken).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.FAILED);
        } finally {
            after.destroy();
        }
    }

    private MailAnalysisQueue queue(int capacity, Instant now) {
        return new MailAnalysisQueue(directory, capacity, RETENTION, JsonMapper.builder().build(),
                processor, delivery, Clock.fixed(now, ZoneOffset.UTC));
    }
}

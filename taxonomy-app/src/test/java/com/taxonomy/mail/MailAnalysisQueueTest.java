package com.taxonomy.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class MailAnalysisQueueTest {
    private static final Instant NOW = Instant.parse("2026-11-22T08:00:00Z");
    private static final Duration RETENTION = Duration.ofHours(48);
    private static final IncomingMail FIRST = new IncomingMail("uid-1", "sender@example.test", "Architektur", "Anforderung 1");
    private static final IncomingMail SECOND = new IncomingMail("uid-2", "second@example.test", "Entscheidungsbaum", "Anforderung 2");
    private static final MailAnalysisCommand COMMAND = new MailAnalysisCommand(MailAnalysisCommand.Mode.ARCHITECTURE,
            List.of(), List.of(MailAnalysisCommand.Format.PDF));

    @TempDir Path directory;

    private final MailAnalysisProcessor processor = mock(MailAnalysisProcessor.class);
    private final MailAnalysisDelivery delivery = mock(MailAnalysisDelivery.class);

    @BeforeEach
    void classifyRequests() {
        when(processor.classify(anyString(), anyString())).thenReturn(COMMAND);
    }

    @Test
    void receiptViewShowsAnonymousQueueAndUsesMeasuredDurationsAcrossRestarts() throws Exception {
        var now = new AtomicReference<>(NOW);
        var clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        var mapper = JsonMapper.builder().build();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var count = new AtomicInteger();
        when(processor.process(anyString(), eq(COMMAND), anyString(), any())).thenAnswer(invocation -> {
            int number = count.incrementAndGet();
            now.updateAndGet(time -> time.plusSeconds(20L * number));
            return new MailAnalysisQueue.Outcome("Fertig", List.of(), true);
        });

        var history = new MailAnalysisQueue(directory, 4, RETENTION, mapper, processor, delivery, clock);
        try {
            for (int i = 0; i < 3; i++) {
                String sample = history.submit(new IncomingMail("sample-" + i, FIRST.sender(),
                        FIRST.subject(), FIRST.requirementText())).jobToken();
                history.confirmReceipt(sample);
                if (i < 2) assertThat(history.snapshot(sample).orElseThrow().typicalDurationSeconds()).isNull();
                history.processNext();
                var finished = history.snapshot(sample).orElseThrow().job();
                assertThat(finished.state()).isEqualTo(MailAnalysisQueue.State.COMPLETED);
                assertThat(finished.settings().exports()).containsExactly(MailAnalysisCommand.Format.PDF);
            }
        } finally {
            history.destroy();
        }
        var queue = new MailAnalysisQueue(directory, 4, RETENTION, mapper, processor, delivery, clock);
        try {
            var confidential = new MailAnalysisCommand(MailAnalysisCommand.Mode.DECISION_TREE,
                    List.of("private-taxonomy"), List.of(MailAnalysisCommand.Format.DOCX));
            when(processor.classify(FIRST.subject(), FIRST.requirementText())).thenReturn(confidential);
            when(processor.process(anyString(), eq(confidential), anyString(), any())).thenAnswer(invocation -> {
                started.countDown();
                release.await(10, TimeUnit.SECONDS);
                return new MailAnalysisQueue.Outcome("Fertig", List.of(), true);
            });
            String running = queue.submit(FIRST).jobToken();
            String firstWaiting = queue.submit(SECOND).jobToken();
            String own = queue.submit(new IncomingMail("private-id", "person@example.test",
                    "private subject", "private requirement")).jobToken();
            for (String token : List.of(running, firstWaiting, own)) queue.confirmReceipt(token);
            var pending = queue.snapshot(own).orElseThrow().job();
            assertThat(pending.estimatedWaitSeconds()).isNull();
            assertThat(pending.settings()).isNull();
            queue.onApplicationEvent(null);
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            now.updateAndGet(time -> time.plusSeconds(5));
            var view = queue.snapshot(own).orElseThrow();

            assertThat(view.summary().position()).isEqualTo(2);
            assertThat(view.workerOnline()).isTrue();
            assertThat(view.workerCount()).isEqualTo(1);
            assertThat(view.estimateSamples()).isEqualTo(3);
            assertThat(view.typicalDurationSeconds()).isEqualTo(40L);
            assertThat(view.job()).isEqualTo(view.jobs().get(2));
            assertThat(view.jobs()).extracting(MailAnalysisQueue.JobView::own)
                    .containsExactly(false, false, true);
            assertThat(view.jobs()).extracting(MailAnalysisQueue.JobView::displayId).doesNotHaveDuplicates();
            assertThat(view.jobs().getFirst().settings()).isEqualTo(new MailAnalysisQueue.SettingsView(
                    confidential.mode(), List.of(), 1, confidential.exports()));
            assertThat(queue.snapshot(running).orElseThrow().job().settings().taxonomyCodes())
                    .containsExactly("private-taxonomy");
            assertThat(view.jobs().getFirst().startedAt()).isNotNull();
            assertThat(view.jobs().get(1).estimatedWaitSeconds()).isEqualTo(35L);
            assertThat(view.job().estimatedWaitSeconds()).isEqualTo(75L);
            assertThat(mapper.writeValueAsString(view)).doesNotContain(running, firstWaiting, own,
                    "private", FIRST.sender(), SECOND.sender(), FIRST.subject(), SECOND.requirementText());
            now.updateAndGet(time -> time.plusSeconds(60));
            assertThat(queue.snapshot(own).orElseThrow().job().estimatedWaitSeconds()).isNull();
        } finally {
            release.countDown();
            queue.destroy();
        }
    }

    @Test
    void preparesConfirmedWaitingSettingsDuringAnalysisAndReusesThemAfterRestart() throws Exception {
        var custom = new MailAnalysisCommand(MailAnalysisCommand.Mode.DECISION_TREE,
                List.of("confidential-root"), List.of(MailAnalysisCommand.Format.DOCX));
        when(processor.classify(SECOND.subject(), SECOND.requirementText())).thenReturn(custom);
        var started = new CountDownLatch(1);
        var pauseFirst = new CountDownLatch(1);
        var result = new MailAnalysisQueue.Outcome("Fertig", List.of(), true);
        when(processor.process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    pauseFirst.await(15, TimeUnit.SECONDS);
                    throw new MailAnalysisProcessor.SourceUnavailable();
                }).thenReturn(result);
        when(processor.process(anyString(), eq(custom), eq(SECOND.requirementText()), any())).thenReturn(result);
        var unconfirmed = new IncomingMail("third", FIRST.sender(), "Unconfirmed", "Third requirement");
        var rejected = new IncomingMail("fourth", FIRST.sender(), "Rejected", "Fourth requirement");
        String waiting;
        var before = queue(2, NOW);
        try {
            String first = before.submit(FIRST).jobToken();
            before.confirmReceipt(first);
            before.onApplicationEvent(null);
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            before.submit(unconfirmed);
            waiting = before.submit(SECOND).jobToken();
            before.confirmReceipt(waiting);
            var declined = before.submit(rejected);
            assertThat(declined.accepted()).isFalse();
            before.confirmReceipt(declined.jobToken());

            await().atMost(Duration.ofSeconds(10)).until(() -> before.snapshot(waiting).orElseThrow().job().settings() != null);
            var own = before.snapshot(waiting).orElseThrow();
            assertThat(own.workerState()).isEqualTo(MailAnalysisQueue.WorkerState.RUNNING);
            assertThat(own.job().state()).isEqualTo(MailAnalysisQueue.State.QUEUED);
            assertThat(own.job().startedAt()).isNull();
            assertThat(own.job().settings()).isEqualTo(new MailAnalysisQueue.SettingsView(
                    custom.mode(), custom.taxonomyCodes(), 1, custom.exports()));
            var observer = before.snapshot(first).orElseThrow();
            var foreign = observer.jobs().stream().filter(job -> job.displayId().equals(own.job().displayId()))
                    .findFirst().orElseThrow();
            assertThat(foreign.settings()).isEqualTo(new MailAnalysisQueue.SettingsView(
                    custom.mode(), List.of(), 1, custom.exports()));
            verify(processor, never()).process(anyString(), eq(custom), eq(SECOND.requirementText()), any());
            pauseFirst.countDown();
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    before.snapshot(first).orElseThrow().workerState() == MailAnalysisQueue.WorkerState.WAITING_FOR_SOURCE);
        } finally {
            pauseFirst.countDown();
            before.destroy();
        }
        var after = queue(2, NOW.plusSeconds(6));
        try {
            assertThat(after.snapshot(waiting).orElseThrow().job().settings().taxonomyCodes()).containsExactly("confidential-root");
            after.processNext();
            after.processNext();
            assertThat(after.status(waiting).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.COMPLETED);
            verify(processor).classify(SECOND.subject(), SECOND.requirementText());
            verify(processor).process(anyString(), eq(custom), eq(SECOND.requirementText()), any());
            verify(processor, never()).classify(unconfirmed.subject(), unconfirmed.requirementText());
            verify(processor, never()).classify(rejected.subject(), rejected.requirementText());
        } finally {
            after.destroy();
        }
    }

    @Test
    void reattemptsAnUnavailableWaitingClassificationAtExecutionWithoutParallelCalls() throws Exception {
        var firstProcessing = new CountDownLatch(1);
        var finishFirst = new CountDownLatch(1);
        var preparingSecond = new CountDownLatch(1);
        var finishPreparing = new CountDownLatch(1);
        var result = new MailAnalysisQueue.Outcome("Fertig", List.of(), true);
        when(processor.process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any())).thenAnswer(invocation -> {
            firstProcessing.countDown();
            finishFirst.await(15, TimeUnit.SECONDS);
            return result;
        });
        when(processor.classify(SECOND.subject(), SECOND.requirementText())).thenAnswer(invocation -> {
            preparingSecond.countDown();
            finishPreparing.await(15, TimeUnit.SECONDS);
            throw new MailAnalysisProcessor.SourceUnavailable();
        }).thenReturn(COMMAND);
        when(processor.process(anyString(), eq(COMMAND), eq(SECOND.requirementText()), any())).thenReturn(result);
        var queue = queue(2, NOW);
        try {
            String first = queue.submit(FIRST).jobToken();
            queue.confirmReceipt(first);
            queue.onApplicationEvent(null);
            assertThat(firstProcessing.await(10, TimeUnit.SECONDS)).isTrue();
            String second = queue.submit(SECOND).jobToken();
            queue.confirmReceipt(second);
            assertThat(preparingSecond.await(10, TimeUnit.SECONDS)).isTrue();
            finishFirst.countDown();
            await().atMost(Duration.ofSeconds(10)).until(() -> queue.status(first).orElseThrow().finished());
            // At least one full worker polling interval elapses while the classifier owns this job.
            await().during(Duration.ofMillis(1250)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                verify(processor, times(1)).classify(SECOND.subject(), SECOND.requirementText());
                verify(processor, never()).process(anyString(), eq(COMMAND), eq(SECOND.requirementText()), any());
            });
            finishPreparing.countDown();
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    queue.status(second).orElseThrow().state() == MailAnalysisQueue.State.COMPLETED);
            verify(processor, times(2)).classify(SECOND.subject(), SECOND.requirementText());
            verify(processor).process(anyString(), eq(COMMAND), eq(SECOND.requirementText()), any());
        } finally {
            finishFirst.countDown();
            finishPreparing.countDown();
            queue.destroy();
        }
    }

    @Test
    void reportsSourceRecoveryAndDistinguishesActiveDeliveryFromDeliveryBackoff() {
        var ready = new AtomicBoolean(false);
        when(processor.sourceReady()).thenAnswer(invocation -> ready.get());
        var finishAnalysis = new CountDownLatch(1);
        var finishDelivery = new CountDownLatch(1);
        when(processor.process(anyString(), eq(COMMAND), anyString(), any())).thenAnswer(invocation -> {
            finishAnalysis.await(10, TimeUnit.SECONDS);
            return new MailAnalysisQueue.Outcome("Fertig", List.of(), true);
        });
        doAnswer(invocation -> {
            finishDelivery.await(10, TimeUnit.SECONDS);
            throw new IllegalStateException("temporary SMTP failure");
        }).when(delivery).sendResult(anyString(), anyString(), anyString(), anyList());

        var queue = new MailAnalysisQueue(directory, 2, RETENTION, JsonMapper.builder().build(), processor, delivery);
        String token = queue.submit(FIRST).jobToken();
        Supplier<MailAnalysisQueue.WorkerState> current = () -> queue.snapshot(token).orElseThrow().workerState();
        try {
            assertThat(current.get()).isEqualTo(MailAnalysisQueue.WorkerState.STOPPED);
            queue.onApplicationEvent(null);
            assertThat(current.get()).isEqualTo(MailAnalysisQueue.WorkerState.IDLE);
            queue.confirmReceipt(token);
            await().atMost(Duration.ofSeconds(10)).until(current::get,
                    state -> state == MailAnalysisQueue.WorkerState.WAITING_FOR_SOURCE);
            ready.set(true);
            await().atMost(Duration.ofSeconds(10)).until(current::get,
                    state -> state == MailAnalysisQueue.WorkerState.RUNNING);
            finishAnalysis.countDown();
            await().atMost(Duration.ofSeconds(10)).until(current::get,
                    state -> state == MailAnalysisQueue.WorkerState.DELIVERING);
            finishDelivery.countDown();
            await().atMost(Duration.ofSeconds(10)).until(current::get,
                    state -> state == MailAnalysisQueue.WorkerState.RETRYING);
        } finally {
            finishAnalysis.countDown();
            finishDelivery.countDown();
            queue.destroy();
        }
        assertThat(current.get()).isEqualTo(MailAnalysisQueue.WorkerState.STOPPED);
    }

    @Test
    void confirmedMailWaitsUntilItsSourceReadinessProbeSucceeds() {
        when(processor.sourceReady()).thenReturn(false, false, true);
        when(processor.process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any())).thenReturn(MailAnalysisQueue.Outcome.failed("Result"));

        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("mail-test", Map.of(
                    "taxonomy.mail.enabled", "true", "taxonomy.mail.queue.directory", directory.toString())));
            context.registerBean(ObjectMapper.class, JsonMapper.builder()::build);
            context.registerBean(MailAnalysisProcessor.class, () -> processor);
            context.registerBean(MailAnalysisDelivery.class, () -> delivery);
            context.register(MailAnalysisQueue.class);
            context.refresh();

            var queue = context.getBean(MailAnalysisQueue.class);
            String token = queue.submit(FIRST).jobToken();
            queue.confirmReceipt(token);
            queue.processNext();
            assertThat(queue.status(token).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.QUEUED);

            queue.processNext();
            assertThat(queue.status(token).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.QUEUED);
            verify(processor, never()).classify(anyString(), anyString());
            verify(processor, never()).process(anyString(), any(), anyString(), any());
            verifyNoInteractions(delivery);

            queue.processNext();
            verify(delivery).sendFailure(FIRST.sender(), FIRST.subject(), "Result");
            assertThat(queue.status(token).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.FAILED);
        }
    }

    @Test
    void admissionRequiresReceiptAndReusesTheOriginalTokenAfterRestart() throws Exception {
        String token;
        var attachment = new MailAnalysisAttachment("architecture.pdf", "application/pdf", new byte[] {1, 2, 3});
        when(processor.process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any()))
                .thenReturn(new MailAnalysisQueue.Outcome("Fertig", List.of(attachment), true));

        var before = queue(1, NOW);
        try {
            var admission = before.submit(FIRST);
            token = admission.jobToken();
            assertThat(admission.position()).isEqualTo(1);
            assertThat(admission.receiptConfirmed()).isFalse();
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
            assertThat(after.submit(FIRST).receiptConfirmed()).isTrue();
            assertThat(after.submit(SECOND).accepted()).isTrue();
        } finally {
            after.destroy();
        }
    }

    @Test
    void aFullQueueRejectionSurvivesFreedCapacityRestartAndRedelivery() throws Exception {
        var before = queue(1, NOW);
        String rejectionId;
        when(processor.process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any())).thenReturn(MailAnalysisQueue.Outcome.failed("test"));
        try {
            var admitted = before.submit(FIRST);
            var rejected = before.submit(SECOND);
            rejectionId = rejected.jobToken();
            assertThat(rejected.accepted()).isFalse();
            assertThat(rejected.receiptConfirmed()).isFalse();
            assertThat(rejected.position()).isNull();
            assertThat(before.status(rejectionId)).isEmpty();
            assertThat(Files.readString(directory.resolve(rejectionId + ".job")))
                    .doesNotContain(SECOND.sender(), SECOND.subject(), SECOND.requirementText());
            before.confirmReceipt(admitted.jobToken());
            before.processNext();
        } finally {
            before.destroy();
        }
        var after = queue(1, NOW.plusSeconds(1));
        try {
            var retry = after.submit(SECOND);
            assertThat(retry.accepted()).isFalse();
            assertThat(retry.duplicate()).isTrue();
            assertThat(retry.jobToken()).isEqualTo(rejectionId);
            after.confirmReceipt(rejectionId);
            assertThat(after.submit(SECOND).receiptConfirmed()).isTrue();
            var resubmitted = new IncomingMail("new-uid", SECOND.sender(), SECOND.subject(), SECOND.requirementText());
            assertThat(after.submit(resubmitted).accepted()).isTrue();
        } finally {
            after.destroy();
        }
    }

    @Test
    void failedDeliveryLetsAnotherRecipientProceedAndRetriesExactBytesAfterRestart() {
        var attachment = new MailAnalysisAttachment("architecture.pdf", "application/pdf", new byte[] {1, 2, 3});
        when(processor.process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any()))
                .thenReturn(new MailAnalysisQueue.Outcome("Fertig", List.of(attachment), true));
        when(processor.process(anyString(), eq(COMMAND), eq(SECOND.requirementText()), any()))
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
            assertThat(before.status(secondToken).orElseThrow()).isEqualTo(
                    new MailAnalysisQueue.Status(MailAnalysisQueue.State.QUEUED, 1, 1, 2, 0, 1, false));
            assertThat(before.submit(SECOND).position()).isEqualTo(2);
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
            verify(processor, times(1)).process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any());
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
    void spoolWriteFailureRetriesTheComputedResultAfterBackoff() throws Exception {
        Path active = directory.resolve("queue");
        Path unavailable = directory.resolve("temporarily-unavailable");
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        var attachment = new MailAnalysisAttachment("result.pdf", "application/pdf", new byte[] {1, 2, 3});
        when(processor.process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any())).thenAnswer(call -> {
            Files.move(active, unavailable);
            return new MailAnalysisQueue.Outcome("Original", List.of(attachment), true);
        });
        var queue = new MailAnalysisQueue(active, 1, RETENTION, JsonMapper.builder().build(), processor, delivery, clock);
        try {
            String token = queue.submit(FIRST).jobToken();
            queue.confirmReceipt(token);
            try {
                queue.processNext();
            } catch (UncheckedIOException unavailableStorage) {
                // The periodic worker also catches this at the boundary and schedules another turn.
            } finally {
                Files.move(unavailable, active);
            }

            queue.processNext();
            verify(processor).process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any());
            verifyNoInteractions(delivery);
            assertThat(queue.status(token).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.RUNNING);

            when(clock.instant()).thenReturn(NOW.plusSeconds(61));
            queue.processNext();
            verify(processor).process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any());
            verify(delivery).sendResult(FIRST.sender(), FIRST.subject(), "Original", List.of(attachment));
            assertThat(queue.status(token).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.COMPLETED);
        } finally {
            queue.destroy();
        }
    }

    @Test
    void deliveryBacklogPausesNewAnalysesAndPreservesWaitingAdmissionsAfterRestart() {
        when(processor.process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any())).thenReturn(MailAnalysisQueue.Outcome.failed("First"));
        when(processor.process(anyString(), eq(COMMAND), eq(SECOND.requirementText()), any())).thenReturn(MailAnalysisQueue.Outcome.failed("Second"));
        doThrow(new IllegalStateException("temporary SMTP failure")).doNothing().when(delivery)
                .sendFailure(FIRST.sender(), FIRST.subject(), "First");
        String firstToken;
        String secondToken;
        var before = queue(1, NOW);
        try {
            firstToken = before.submit(FIRST).jobToken();
            before.confirmReceipt(firstToken);
            before.processNext();
            var waiting = before.submit(SECOND);
            assertThat(waiting.accepted()).isTrue();
            secondToken = waiting.jobToken();
            before.confirmReceipt(secondToken);
        } finally {
            before.destroy();
        }

        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        var after = new MailAnalysisQueue(directory, 1, RETENTION, JsonMapper.builder().build(), processor, delivery, clock);
        try {
            after.processNext();
            assertThat(after.status(secondToken).orElseThrow()).isEqualTo(
                    new MailAnalysisQueue.Status(MailAnalysisQueue.State.QUEUED, 1, 1, 1, 0, 1, false));
            assertThat(after.submit(SECOND).jobToken()).isEqualTo(secondToken);

            when(clock.instant()).thenReturn(NOW.plusSeconds(61));
            after.processNext();
            assertThat(after.status(firstToken).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.FAILED);
            after.processNext();
            assertThat(after.status(secondToken).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.FAILED);
        } finally {
            after.destroy();
        }
    }

    @Test
    void aDeliveredPartialResultHasItsOwnTerminalStatus() {
        when(processor.process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any())).thenReturn(
                new MailAnalysisQueue.Outcome("Teilergebnis", List.of(), true, true));
        var queue = queue(1, NOW);
        try {
            String token = queue.submit(FIRST).jobToken();
            queue.confirmReceipt(token);
            queue.processNext();
            verify(delivery).sendResult(FIRST.sender(), FIRST.subject(), "Teilergebnis", List.of());
            assertThat(queue.status(token).orElseThrow()).isEqualTo(
                    new MailAnalysisQueue.Status(MailAnalysisQueue.State.PARTIAL, null, 0, 1, 0, 0, true));
        } finally {
            queue.destroy();
        }
    }

    @Test
    void undeliverableStageExpiresWithAPublicFailureState() throws Exception {
        String token;
        when(processor.process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any())).thenReturn(MailAnalysisQueue.Outcome.failed("Kein Ergebnis"));
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
        when(processor.process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any()))
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
            verify(processor).classify(FIRST.subject(), FIRST.requirementText());
            ArgumentCaptor<String> executionIds = ArgumentCaptor.captor();
            verify(processor, times(2)).process(executionIds.capture(), eq(COMMAND), eq(FIRST.requirementText()), any());
            assertThat(executionIds.getAllValues()).containsOnly(executionIds.getValue()).doesNotContain(firstToken);
            verify(delivery).sendFailure(FIRST.sender(), FIRST.subject(), "Kein Ergebnis");
            assertThat(after.status(firstToken).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.FAILED);
            assertThat(after.status(secondToken).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.QUEUED);
        } finally {
            after.destroy();
        }
    }

    @Test
    void legacyRunningJobKeepsItsAdmissionAndPersistsAnExecutionBeforeAnalysis() throws Exception {
        String publicToken;
        var before = queue(1, NOW);
        try {
            publicToken = before.submit(FIRST).jobToken();
            before.confirmReceipt(publicToken);
        } finally {
            before.destroy();
        }

        Path jobFile = directory.resolve(publicToken + ".job");
        var mapper = JsonMapper.builder().build();
        var legacy = (ObjectNode) mapper.readTree(Files.readString(jobFile));
        legacy.remove("execution");
        legacy.put("state", "RUNNING");
        Files.writeString(jobFile, mapper.writeValueAsString(legacy));

        when(processor.process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any())).thenAnswer(call -> {
            var execution = mapper.readTree(Files.readString(jobFile)).get("execution");
            assertThat(execution.get("id").asString()).isEqualTo(call.getArgument(0, String.class)).isNotEqualTo(publicToken);
            assertThat(mapper.treeToValue(execution.get("command"), MailAnalysisCommand.class)).isEqualTo(COMMAND);
            return new MailAnalysisQueue.Outcome("Recovered", List.of(), true);
        });
        var after = queue(1, NOW.plusSeconds(1));
        try {
            var receipt = after.submit(FIRST);
            assertThat(receipt.jobToken()).isEqualTo(publicToken);
            assertThat(receipt.position()).isEqualTo(1);
            assertThat(receipt.duplicate()).isTrue();
            assertThat(receipt.accepted()).isTrue();
            assertThat(receipt.receiptConfirmed()).isTrue();

            after.processNext();
            verify(processor).classify(FIRST.subject(), FIRST.requirementText());
            verify(delivery).sendResult(FIRST.sender(), FIRST.subject(), "Recovered", List.of());
            assertThat(after.status(publicToken).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.COMPLETED);
        } finally {
            after.destroy();
        }
    }

    @Test
    void transientSourceFailureRetainsTheClassifiedExecutionUntilRetry() {
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        when(processor.process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any()))
                .thenThrow(new MailAnalysisProcessor.SourceUnavailable())
                .thenReturn(new MailAnalysisQueue.Outcome("Recovered", List.of(), true));
        var queue = new MailAnalysisQueue(directory, 1, RETENTION, JsonMapper.builder().build(), processor, delivery, clock);
        try {
            String token = queue.submit(FIRST).jobToken();
            queue.confirmReceipt(token);
            queue.processNext();
            queue.processNext();
            assertThat(queue.status(token).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.RUNNING);
            verify(processor).process(anyString(), eq(COMMAND), eq(FIRST.requirementText()), any());
            verifyNoInteractions(delivery);

            when(clock.instant()).thenReturn(NOW.plusSeconds(6));
            queue.processNext();
            verify(processor).classify(FIRST.subject(), FIRST.requirementText());
            ArgumentCaptor<String> executionIds = ArgumentCaptor.captor();
            verify(processor, times(2)).process(executionIds.capture(), eq(COMMAND), eq(FIRST.requirementText()), any());
            assertThat(executionIds.getAllValues()).containsOnly(executionIds.getValue()).doesNotContain(token);
            verify(delivery).sendResult(FIRST.sender(), FIRST.subject(), "Recovered", List.of());
            assertThat(queue.status(token).orElseThrow().state()).isEqualTo(MailAnalysisQueue.State.COMPLETED);
        } finally {
            queue.destroy();
        }
    }

    @Test
    void moreThanOneDueRetryDoesNotMonopolizeTheWorker() {
        IncomingMail third = new IncomingMail("uid-3", "third@example.test", "Architektur", "Anforderung 3");
        for (var mail : List.of(FIRST, SECOND, third)) {
            when(processor.process(anyString(), eq(COMMAND), eq(mail.requirementText()), any())).thenReturn(MailAnalysisQueue.Outcome.failed("Kein Ergebnis"));
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

    @Test
    void expiryRemovesOldReceiptsAndPositionsWhileKeepingLaterAdmissions() {
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        var queue = new MailAnalysisQueue(directory, 2, RETENTION, JsonMapper.builder().build(),
                processor, delivery, clock);
        try {
            String unconfirmed = queue.submit(FIRST).jobToken();
            String finished = queue.submit(SECOND).jobToken();
            queue.confirmReceipt(finished);
            when(processor.process(anyString(), eq(COMMAND), eq(SECOND.requirementText()), any()))
                    .thenReturn(MailAnalysisQueue.Outcome.failed("Kein Ergebnis"));
            queue.processNext();
            when(clock.instant()).thenReturn(NOW.plusSeconds(60));
            var newer = new IncomingMail("uid-3", FIRST.sender(), FIRST.subject(), FIRST.requirementText());
            String waiting = queue.submit(newer).jobToken();
            assertThat(queue.status(waiting).orElseThrow().position()).isEqualTo(2);

            when(clock.instant()).thenReturn(NOW.plus(RETENTION));
            var again = queue.submit(FIRST);
            assertThat(again.accepted()).isTrue();
            assertThat(again.duplicate()).isFalse();
            assertThat(again.jobToken()).isNotEqualTo(unconfirmed);
            assertThat(again.position()).isEqualTo(2);
            assertThat(queue.status(unconfirmed)).isEmpty();
            assertThat(queue.status(finished)).isEmpty();
            assertThat(queue.status(waiting).orElseThrow()).isEqualTo(
                    new MailAnalysisQueue.Status(MailAnalysisQueue.State.QUEUED, 1, 2, 2, 0, 0, false));
        } finally {
            queue.destroy();
        }
    }

    @Test
    void simultaneousAdmissionsReturnUniquePositionsUpToCapacity() throws Exception {
        var queue = queue(3, NOW);
        var start = new CountDownLatch(1);
        try (var writers = Executors.newVirtualThreadPerTaskExecutor()) {
            var submissions = new ArrayList<Future<MailAnalysisQueue.Submission>>();
            for (int i = 0; i < 12; i++) {
                var mail = new IncomingMail("uid-" + i, FIRST.sender(), FIRST.subject(), FIRST.requirementText());
                submissions.add(writers.submit(() -> {
                    start.await();
                    return queue.submit(mail);
                }));
            }
            start.countDown();
            var acceptedPositions = new ArrayList<Integer>();
            for (var submission : submissions) {
                var result = submission.get();
                if (result.accepted()) acceptedPositions.add(result.position());
                else assertThat(result.rejectionReason()).contains("voll");
            }
            assertThat(acceptedPositions).containsExactlyInAnyOrder(1, 2, 3);
        } finally {
            queue.destroy();
        }
    }

    private MailAnalysisQueue queue(int capacity, Instant now) {
        return new MailAnalysisQueue(directory, capacity, RETENTION, JsonMapper.builder().build(),
                processor, delivery, Clock.fixed(now, ZoneOffset.UTC));
    }
}

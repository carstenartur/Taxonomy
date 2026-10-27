package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.SnapshotSelection;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real overlapping reader calls; only the JDBC locator boundary and parse scheduling are controlled. */
class PortfolioSnapshotSelectionInflightTest {
    private static final String PAYLOAD = """
            {"scores":{"P":99},"reasons":{"P":"Retained full explanation"},
             "rawScores":{"Z":10,"F":25,"P":80},"warnings":["Late warning"],
             "scoreSemanticsContext":{"F":{"analysisRole":"CATEGORY"},
               "P":{"parentCode":"F","analysisRole":"PRODUCT"}}}
            """;

    @Test
    void overlappingReadsShareOnlyParsedEvidenceAndKeepEachCallersCurrentMetadata() throws Exception {
        try (var f = new Fixture()) {
            var first = f.request("snapshot");
            var second = f.request("snapshot");
            second.status = "SUCCESS"; second.duration = 456; second.elements = 5; second.relations = 8;
            var owner = f.start(first); f.awaitParse(first);
            var follower = f.start(second); f.awaitBlocked(follower);
            assertThat(f.parseCalls).hasValue(1);
            first.release();
            var original = owner.result(); var current = follower.result();
            var expected = new PortfolioJsonCodec(JsonMapper.builder().build()).read(PAYLOAD, AnalysisResult.class);
            assertThat(original.scores()).containsExactlyEntriesOf(expected.getScores());
            assertThat(current.scores()).containsExactlyEntriesOf(expected.getScores());
            assertThat(current.scores()).containsEntry("P", 20d);
            assertThat(current.warningCount()).isEqualTo(1);
            assertThat(original.status()).isEqualTo(AnalysisStatus.PARTIAL);
            assertThat(original.durationMs()).isEqualTo(123);
            assertThat(original.elementMappingCount()).isEqualTo(2);
            assertThat(original.relationMappingCount()).isEqualTo(3);
            assertThat(current.status()).isEqualTo(AnalysisStatus.SUCCESS);
            assertThat(current.durationMs()).isEqualTo(456);
            assertThat(current.elementMappingCount()).isEqualTo(5);
            assertThat(current.relationMappingCount()).isEqualTo(8);
            assertThatThrownBy(() -> current.scores().put("mutated", 100d)).isInstanceOf(UnsupportedOperationException.class);
            assertThat(first.parameters).containsExactlyInAnyOrderEntriesOf(Map.of("id", "snapshot", "projectId", 7L, "scopeKey", "scope"));
            assertThat(second.parameters).containsExactlyInAnyOrderEntriesOf(first.parameters);
            assertThat(first.selected).hasValue(1); assertThat(second.selected).hasValue(1);
            assertThat(first.input.position).isEqualTo(PAYLOAD.length());
            assertThat(first.input.closes).hasValue(1); assertThat(first.frees).hasValue(1);
            assertThat(second.frees).hasValue(1);
        }
    }

    @Test
    void aLaterReadParsesAgainWithFreshMappingCounts() throws Exception {
        try (var f = new Fixture()) {
            var first = f.request("snapshot"); first.release();
            var original = f.start(first).result();
            var later = f.request("snapshot"); later.elements = 9; later.relations = 12; later.release();
            var current = f.start(later).result();
            assertThat(f.parseCalls).hasValue(2);
            assertThat(current.scores()).containsExactlyEntriesOf(original.scores());
            assertThat(current.elementMappingCount()).isEqualTo(9);
            assertThat(current.relationMappingCount()).isEqualTo(12);
            assertThat(first.frees).hasValue(1); assertThat(later.frees).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"scope", "project", "snapshot", "factory", "reader"})
    void differentAuthoritiesAndReaderLifetimesNeverShareEvidence(String boundary) throws Exception {
        try (var f = new Fixture()) {
            var first = f.request("snapshot");
            var different = f.request("snapshot", "{\"rawScores\":{\"OTHER\":70}}");
            switch (boundary) {
                case "scope" -> different.scope = "other-scope";
                case "project" -> different.project = 8L;
                case "snapshot" -> different.id = "other-snapshot";
                case "factory" -> different.factory = mock(SessionFactory.class);
                case "reader" -> different.reader = new PortfolioSnapshotSelectionReader(f.entityManager, f.codec);
                default -> throw new AssertionError(boundary);
            }
            var owner = f.start(first); f.awaitParse(first);
            var independent = f.start(different); f.awaitParse(different);
            assertThat(f.parseCalls).hasValue(2);
            first.release(); different.release();
            assertThat(owner.result().scores()).containsEntry("P", 20d);
            assertThat(independent.result().scores()).containsExactlyEntriesOf(Map.of("OTHER", 70d));
            assertThat(different.parameters).containsEntry("scopeKey", different.scope)
                    .containsEntry("projectId", different.project).containsEntry("id", different.id);
        }
    }

    @Test
    void malformedEnvelopeFailureAndCleanupFailuresStayCallerLocalAndAreNotRetained() throws Exception {
        try (var f = new Fixture()) {
            String malformed = "{\"scores\":{\"A\":40},\"warnings\":[";
            var first = f.request("snapshot", malformed);
            var second = f.request("snapshot", malformed);
            var ownerClose = new IOException("owner reader close");
            var ownerFree = new SQLException("owner clob free");
            var followerFree = new SQLException("follower clob free");
            first.input.closeFailure = ownerClose; first.freeFailure = ownerFree; second.freeFailure = followerFree;
            var owner = f.start(first); f.awaitParse(first);
            var follower = f.start(second); f.awaitBlocked(follower);
            assertThat(f.parseCalls).hasValue(1);
            first.release();
            Throwable ownerFailure = owner.failure(), followerFailure = follower.failure();
            assertThat(ownerFailure).isInstanceOf(PortfolioException.class).hasMessageContaining("deserialize portfolio selection");
            assertThat(ownerFailure.getSuppressed()).containsExactly(ownerClose, ownerFree);
            assertThat(followerFailure).isInstanceOf(PortfolioException.class).isNotSameAs(ownerFailure);
            assertThat(followerFailure.getSuppressed()).containsExactly(followerFree);
            assertThat(followerFailure).hasCause(ownerFailure);
            assertThat(first.frees).hasValue(1); assertThat(second.frees).hasValue(1);
            var later = f.request("snapshot"); later.release();
            assertThat(f.start(later).result().scores()).containsEntry("P", 20d);
            assertThat(f.parseCalls).hasValue(2);
        }
    }

    @Test
    void successfulParsingIsNotPublishedUntilOwnerResourcesCloseSuccessfully() throws Exception {
        try (var f = new Fixture()) {
            var first = f.request("snapshot");
            var second = f.request("snapshot");
            var closing = new IOException("owner reader close");
            first.input.closeFailure = closing;
            var owner = f.start(first); f.awaitParse(first);
            var follower = f.start(second); f.awaitBlocked(follower);
            assertThat(f.parseCalls).hasValue(1);
            first.release();
            assertThat(owner.failure()).isInstanceOf(PortfolioException.class).hasCause(closing);
            assertThat(follower.failure()).isInstanceOf(PortfolioException.class).hasRootCause(closing);
            assertThat(first.frees).hasValue(1); assertThat(second.frees).hasValue(1);
        }
    }

    @Test
    void ownerCancellationDuringCleanupStaysCallerLocalAndIsNotRetained() throws Exception {
        try (var f = new Fixture()) {
            var first = f.request("snapshot"); var second = f.request("snapshot");
            var cancellation = new CancellationException("owner cancelled while closing");
            var followerFree = new SQLException("follower clob free");
            first.freeFailure = cancellation; second.freeFailure = followerFree;
            var owner = f.start(first); f.awaitParse(first);
            var follower = f.start(second); f.awaitBlocked(follower);
            assertThat(f.parseCalls).hasValue(1);
            first.release();
            Throwable ownerFailure = owner.failure(), followerFailure = follower.failure();
            // Java 23+ adds a caller-side cancellation wrapper for each Future.get().
            assertThat(ownerFailure).isInstanceOf(CancellationException.class).hasCause(cancellation);
            assertThat(ownerFailure.getSuppressed()).isEmpty();
            assertThat(cancellation.getSuppressed()).isEmpty();
            assertThat(followerFailure).isInstanceOf(PortfolioException.class).isNotSameAs(ownerFailure)
                    .hasRootCause(cancellation);
            assertThat(followerFailure.getCause()).isInstanceOf(CancellationException.class)
                    .isNotSameAs(ownerFailure).hasCause(cancellation);
            assertThat(followerFailure.getSuppressed()).containsExactly(followerFree);
            assertThat(first.frees).hasValue(1); assertThat(second.frees).hasValue(1);
            var later = f.request("snapshot"); later.release();
            assertThat(f.start(later).result().scores()).containsEntry("P", 20d);
            assertThat(f.parseCalls).hasValue(2);
        }
    }

    @Test
    void followerCleanupFailureCannotPoisonTheOwnerOrAnotherFollower() throws Exception {
        try (var f = new Fixture()) {
            var first = f.request("snapshot"); var second = f.request("snapshot"); var third = f.request("snapshot");
            var freeing = new SQLException("follower locator free"); second.freeFailure = freeing;
            var owner = f.start(first); f.awaitParse(first);
            var failedFollower = f.start(second); f.awaitBlocked(failedFollower);
            var successfulFollower = f.start(third); f.awaitBlocked(successfulFollower);
            assertThat(f.parseCalls).hasValue(1);
            first.release();
            assertThat(owner.result().scores()).containsEntry("P", 20d);
            assertThat(failedFollower.failure()).isInstanceOf(PortfolioException.class).hasCause(freeing);
            assertThat(successfulFollower.result().scores()).containsEntry("P", 20d);
            assertThat(first.frees).hasValue(1); assertThat(second.frees).hasValue(1); assertThat(third.frees).hasValue(1);
        }
    }

    @Test
    void interruptedFollowerLeavesTheOwnersFlightAvailableForAnotherCaller() throws Exception {
        try (var f = new Fixture()) {
            var first = f.request("snapshot"); var second = f.request("snapshot"); var third = f.request("snapshot");
            var owner = f.start(first); f.awaitParse(first);
            var interrupted = f.start(second); f.awaitBlocked(interrupted);
            assertThat(f.parseCalls).hasValue(1);
            interrupted.thread.interrupt();
            assertThat(interrupted.failure()).isInstanceOf(PortfolioException.class).hasRootCauseInstanceOf(InterruptedException.class);
            assertThat(interrupted.interruptedOnReturn).isTrue();
            assertThat(second.frees).hasValue(1);
            var another = f.start(third); f.awaitBlocked(another);
            assertThat(f.parseCalls).hasValue(1);
            first.release();
            assertThat(owner.result().scores()).containsEntry("P", 20d);
            assertThat(another.result().scores()).containsEntry("P", 20d);
        }
    }

    @Test
    void failureToOpenAStreamDoesNotLeaveAnInflightEntryBehind() throws Exception {
        try (var f = new Fixture()) {
            var failed = f.request("snapshot"); failed.openFailure = new SQLException("stream open"); failed.release();
            assertThat(f.start(failed).failure()).isInstanceOf(PortfolioException.class).hasCause(failed.openFailure);
            assertThat(failed.frees).hasValue(1);
            var later = f.request("snapshot"); later.release();
            assertThat(f.start(later).result().scores()).containsEntry("P", 20d);
            assertThat(f.parseCalls).hasValue(1);
        }
    }

    @Test
    void registryHasExactly32ConcurrentSlotsAndOverflowRetainsTheIndependentReadPath() throws Exception {
        try (var f = new Fixture()) {
            var owners = new ArrayList<Request>(); var calls = new ArrayList<Call>();
            for (int index = 0; index < 32; index++) {
                var request = f.request("snapshot-" + index); owners.add(request);
                calls.add(f.start(request)); f.awaitParse(request);
            }
            var overflow = f.request("overflow"); calls.add(f.start(overflow)); f.awaitParse(overflow);
            var anotherOverflow = f.request("overflow"); calls.add(f.start(anotherOverflow)); f.awaitParse(anotherOverflow);
            var firstSlot = f.start(f.request("snapshot-0")); calls.add(firstSlot); f.awaitBlocked(firstSlot);
            var lastSlot = f.start(f.request("snapshot-31")); calls.add(lastSlot); f.awaitBlocked(lastSlot);
            assertThat(f.parseCalls).as("32 registered parses, two independent overflow reads, two joined callers").hasValue(34);
            owners.forEach(Request::release); overflow.release(); anotherOverflow.release();
            for (var call : calls) assertThat(call.result().scores()).containsEntry("P", 20d);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final EntityManager entityManager = mock(EntityManager.class);
        final Session session = mock(Session.class);
        final SessionFactory factory = mock(SessionFactory.class);
        final ThreadLocal<Request> current = new ThreadLocal<>();
        final AtomicInteger parseCalls = new AtomicInteger();
        final List<Request> requests = new ArrayList<>();
        final List<Call> calls = new ArrayList<>();
        final PortfolioJsonCodec codec = new PortfolioJsonCodec(JsonMapper.builder().build()) {
            @Override AnalysisResult readSelectionAnalysis(Reader input) {
                Request request = current.get(); parseCalls.incrementAndGet(); request.parsing.countDown();
                try {
                    if (!request.allowParse.await(10, TimeUnit.SECONDS)) throw new AssertionError("Parser gate was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw PortfolioException.analysisFailed("Interrupted parser fixture", interrupted);
                }
                return super.readSelectionAnalysis(input);
            }
        };
        final PortfolioSnapshotSelectionReader reader = new PortfolioSnapshotSelectionReader(entityManager, codec);

        Fixture() {
            when(entityManager.unwrap(Session.class)).thenReturn(session);
            when(session.getSessionFactory()).thenAnswer(ignored -> current.get().factory);
            when(session.createNativeQuery(anyString(), eq(Object[].class))).thenAnswer(ignored -> query(current.get()));
        }

        @SuppressWarnings("unchecked") NativeQuery<Object[]> query(Request request) {
            NativeQuery<Object[]> query = mock(NativeQuery.class, Answers.RETURNS_SELF);
            when(query.setParameter(anyString(), any())).thenAnswer(invocation -> {
                request.parameters.put(invocation.getArgument(0), invocation.getArgument(1)); return query;
            });
            when(query.getSingleResultOrNull()).thenAnswer(ignored -> {
                request.selected.incrementAndGet();
                return new Object[]{request.id, request.status, request.duration, request.clob, request.elements, request.relations};
            });
            return query;
        }

        Request request(String id) throws SQLException { return request(id, PAYLOAD); }
        Request request(String id, String json) throws SQLException {
            var request = new Request(id, json); request.factory = factory; request.reader = reader; requests.add(request); return request;
        }

        Call start(Request request) {
            var call = new Call(request, () -> {
                current.set(request);
                try { return request.reader.read(request.project, request.id, request.scope); }
                finally { current.remove(); }
            });
            calls.add(call); call.thread.start(); return call;
        }

        void awaitParse(Request request) throws InterruptedException {
            assertThat(request.parsing.await(5, TimeUnit.SECONDS)).as("Independent parser must enter for %s", request.id).isTrue();
        }

        void awaitBlocked(Call call) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                // The old implementation reaches the controlled second parser; a joined caller
                // parks awaiting the first result. Both witness genuine overlap after its SQL read.
                boolean parsing = call.request.parsing.getCount() == 0;
                boolean waitingForResult = call.thread.getState() == Thread.State.WAITING
                        && Arrays.stream(call.thread.getStackTrace()).anyMatch(frame ->
                        frame.getClassName().equals(CompletableFuture.class.getName()) && frame.getMethodName().equals("get"));
                if (call.request.selected.get() == 1 && (parsing || waitingForResult)) return;
                if (call.result.isDone()) throw new AssertionError("Read finished before the overlapping parse was released");
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            throw new AssertionError("Second read never reached its overlapping parse/wait");
        }

        @Override public void close() throws InterruptedException {
            requests.forEach(Request::release);
            for (var call : calls) call.thread.join(200);
            for (var call : calls) if (call.thread.isAlive()) call.thread.interrupt();
        }
    }

    private static final class Request {
        String id, scope = "scope", status = "PARTIAL";
        Long project = 7L;
        long duration = 123, elements = 2, relations = 3;
        SessionFactory factory;
        PortfolioSnapshotSelectionReader reader;
        final CountDownLatch parsing = new CountDownLatch(1), allowParse = new CountDownLatch(1);
        final AtomicInteger selected = new AtomicInteger(), frees = new AtomicInteger();
        final Map<String, Object> parameters = new LinkedHashMap<>();
        final Clob clob = mock(Clob.class);
        final Input input;
        SQLException openFailure;
        Throwable freeFailure;
        Request(String id, String json) throws SQLException {
            this.id = id; input = new Input(json);
            when(clob.getCharacterStream()).thenAnswer(ignored -> {
                if (openFailure != null) throw openFailure; return input;
            });
            doAnswer(ignored -> {
                frees.incrementAndGet(); if (freeFailure != null) throw freeFailure; return null;
            }).when(clob).free();
        }
        void release() { allowParse.countDown(); }
    }

    private static final class Call {
        final Request request;
        final CompletableFuture<Optional<SnapshotSelection>> result = new CompletableFuture<>();
        final AtomicBoolean interruptedOnReturn = new AtomicBoolean();
        final Thread thread;
        Call(Request request, java.util.concurrent.Callable<Optional<SnapshotSelection>> work) {
            this.request = request;
            thread = new Thread(() -> {
                Optional<SnapshotSelection> value = null; Throwable failure = null;
                try { value = work.call(); } catch (Throwable problem) { failure = problem; }
                finally { interruptedOnReturn.set(Thread.currentThread().isInterrupted()); }
                if (failure == null) result.complete(value); else result.completeExceptionally(failure);
            }, "selection-overlap-" + request.id);
            thread.setDaemon(true);
        }
        SnapshotSelection result() throws Exception { return result.get(5, TimeUnit.SECONDS).orElseThrow(); }
        Throwable failure() throws Exception {
            try { result.get(5, TimeUnit.SECONDS); throw new AssertionError("Read should have failed"); }
            catch (ExecutionException failure) { return failure.getCause(); }
            catch (CancellationException failure) { return failure; }
        }
    }

    private static final class Input extends Reader {
        final StringReader delegate;
        final AtomicInteger closes = new AtomicInteger();
        int position;
        IOException closeFailure;
        Input(String json) { delegate = new StringReader(json); }
        @Override public int read(char[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, Math.min(length, 17));
            if (count > 0) position += count; return count;
        }
        @Override public void close() throws IOException {
            closes.incrementAndGet(); delegate.close(); if (closeFailure != null) throw closeFailure;
        }
    }
}

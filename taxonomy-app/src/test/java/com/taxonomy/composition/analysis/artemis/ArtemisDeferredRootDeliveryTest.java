package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.cluster.ClusterAnalysisSignals;
import com.taxonomy.analysis.cluster.ClusterAnalysisStore;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import jakarta.jms.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Broker-free JMS protocol checks: a deferred task is consumed without an effect or retry. */
class ArtemisDeferredRootDeliveryTest {
    @Test void waitingRootDeliveryIsAcknowledgedWithoutACompletionOrBrokerRetry() throws Exception {
        var harness = new Harness();
        var ledger = mock(AnalysisTaskCompletionStore.class);
        var handlers = new AnalysisTaskHandlers(task -> { throw new AnalysisTaskDeferredException(); }, null);
        try (var worker = new ArtemisAnalysisWorker(harness.connection, harness.destinations,
                AnalysisWorkerShards.parse("CP"), 1, handlers, ledger, harness.codec)) {
            worker.start();
            harness.deliver(harness.destinations.subtaxonomy(TaxonomyShardRoot.of("CP")));
            verify(harness.session).commit();
            verify(harness.session, never()).rollback();
            verify(ledger, never()).commit(any());
            verify(harness.producer, never()).send(any(Destination.class), any(Message.class));
            assertEquals(0, worker.rolledBack());
        }
    }

    @Test void waitingRootExpiryIsAcknowledgedWithoutRecordingFailureOrCompletion() throws Exception {
        var harness = new Harness();
        var store = mock(ClusterAnalysisStore.class);
        var ledger = mock(AnalysisTaskCompletionStore.class);
        when(ledger.find(any(AnalysisTaskMessage.class))).thenReturn(Optional.empty());
        when(ledger.commit(any())).thenAnswer(invocation -> {
            PreparedAnalysisCompletion<?> prepared = invocation.getArgument(0);
            prepared.persistEffect().run();
            return prepared.completion();
        });
        doThrow(new AnalysisTaskDeferredException()).when(store).persistFailure(any(), eq("BROKER_EXPIRED"));
        try (var coordinator = new ArtemisClusterCoordinator(harness.connection, harness.destinations,
                harness.codec, store, ledger, new ClusterAnalysisSignals(), true)) {
            coordinator.start();
            harness.deliver(harness.destinations.expiry());
            verify(harness.session).commit();
            verify(harness.session, never()).rollback();
            verify(store, never()).accept(any());
            verify(harness.producer, never()).send(any(Destination.class), any(Message.class));
            assertEquals(0, coordinator.failures());
        }
    }

    private static final class Harness {
        final ArtemisAnalysisConnection connection = mock(ArtemisAnalysisConnection.class);
        final Session session = mock(Session.class);
        final MessageProducer producer = mock(MessageProducer.class);
        final AnalysisDestinations destinations = new AnalysisDestinations(AnalysisDestinations.DEFAULT_PREFIX);
        final AnalysisMessageCodec codec = new AnalysisMessageCodec();
        final Map<String, MessageListener> listeners = new HashMap<>();
        Harness() throws Exception {
            when(connection.createSession(anyBoolean())).thenReturn(session);
            when(session.createProducer(null)).thenReturn(producer);
            when(session.createMessage()).thenReturn(mock(Message.class));
            when(session.createQueue(anyString())).thenAnswer(invocation -> {
                var queue = mock(Queue.class);
                when(queue.getQueueName()).thenReturn(invocation.getArgument(0));
                return queue;
            });
            when(session.createTopic(anyString())).thenAnswer(invocation -> {
                var topic = mock(Topic.class);
                when(topic.getTopicName()).thenReturn(invocation.getArgument(0));
                return topic;
            });
            when(session.createConsumer(any(Destination.class))).thenAnswer(invocation -> {
                Destination destination = invocation.getArgument(0);
                String name = destination instanceof Queue queue ? queue.getQueueName() : ((Topic) destination).getTopicName();
                var consumer = mock(MessageConsumer.class);
                doAnswer(call -> { listeners.put(name, call.getArgument(0)); return null; })
                        .when(consumer).setMessageListener(any());
                return consumer;
            });
        }
        void deliver(String destination) throws Exception {
            var context = new AnalysisOperationContext("deferred-root", new AnalysisSourceAuthority("repo", "workspace", "draft", "source"),
                    RequirementReference.adHoc("requirement"), "deferred-root");
            var task = new AnalysisMessageFactory(context, Clock.systemUTC()).task(
                    AnalysisTaskGraph.plan(context.operationId(), List.of(TaxonomyShardRoot.of("CP")), false).tasks().getFirst());
            byte[] body = codec.encode(task);
            var message = mock(BytesMessage.class);
            when(message.getBodyLength()).thenReturn((long) body.length);
            when(message.readBytes(any(byte[].class))).thenAnswer(invocation -> {
                System.arraycopy(body, 0, invocation.getArgument(0), 0, body.length);
                return body.length;
            });
            listeners.get(destination).onMessage(message);
        }
    }
}

package com.taxonomy.composition.analysis.artemis;

import org.apache.activemq.artemis.api.core.ActiveMQAddressExistsException;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientSession;

/** Administrative creation used by both one-shot initializers on brokers with automatic creation disabled. */
final class ArtemisProviderCapacityQueue {
    private ArtemisProviderCapacityQueue() { }

    static void create(ClientSession session, String name, boolean exclusive) throws ActiveMQException {
        try {
            session.createAddress(SimpleString.of(name), RoutingType.ANYCAST, false);
        } catch (ActiveMQAddressExistsException existing) {
            // The durable queue is the exclusive initialization claim, including when its address already exists.
        }
        session.createQueue(QueueConfiguration.of(name).setAddress(name).setRoutingType(RoutingType.ANYCAST)
                .setDurable(true).setExclusive(exclusive).setAutoCreated(false).setAutoDelete(false)
                .setPurgeOnNoConsumers(false));
    }
}

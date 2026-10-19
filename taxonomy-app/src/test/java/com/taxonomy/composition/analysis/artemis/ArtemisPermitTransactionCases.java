package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.service.ProviderConcurrencyPermits;
import jakarta.jms.*;
import org.apache.activemq.artemis.jms.client.ActiveMQMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQQueue;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Deterministic transaction fault model. Not a substitute for real-broker acceptance. */
final class ArtemisPermitTransactionCases {
    static final String GROUP = "fixture-group";
    static final String ID = "76f378cc-b929-4b09-9dce-2fce589a4f50";
    record Check(String name, Runnable action) { }
    static List<Check> checks() {
        var checks = new ArrayList<Check>();
        checks.add(new Check("unused close returns one slot", () -> {
            var model = new Model(); var held = model.hold(); held.close(); held.close(); model.count(1,0);
        }));
        for (boolean applied : new boolean[]{false, true}) {
            checks.add(new Check("unused failed return preserves HA contract; applied=" + applied, () -> {
                var model = new Model(); model.failCommit = 1; model.failAfterApply = applied;
                var held = model.hold();
                try {
                    held.close();
                    throw new AssertionError("Unconfirmed unused return must fail visibly");
                } catch (ProviderConcurrencyPermits.UnavailableException expected) {
                    if (!"Cluster provider permit return was not confirmed".equals(expected.getMessage()))
                        throw new AssertionError("HA return failure message changed: " + expected.getMessage());
                    if (!(expected.getCause() instanceof JMSException))
                        throw new AssertionError("The original JMS failure must remain the cause");
                }
                held.close();
                model.count(1, 0);
                if (model.commits != 1) throw new AssertionError("Failed return must not retry or compensate");
            }));
        }
        checks.add(new Check("unknown outcome keeps durable receipt", () -> {
            var model = new Model(); var held = model.hold(); held.beforeTransport(); held.close(); model.count(0,1);
        }));
        checks.add(new Check("holder loss cannot redeliver available capacity", () -> {
            var model = new Model(); var held = model.hold(); held.beforeTransport(); model.loseHolder(); model.count(0,1);
        }));
        checks.add(new Check("failure before start commit permits no HTTP", () -> {
            var model = new Model(); model.failCommit = 1; var held = model.hold();
            fails(held::beforeTransport); held.close(); model.count(1,0);
        }));
        checks.add(new Check("lost start acknowledgement cannot return a slot", () -> {
            var model = new Model(); model.failCommit = 1; model.failAfterApply = true; var held = model.hold();
            fails(held::beforeTransport); held.close(); model.count(0,1);
        }));
        checks.add(new Check("lost return acknowledgement never doubles capacity", () -> {
            var model = new Model(); model.failCommit = 2; model.failAfterApply = true; var held = model.hold();
            held.beforeTransport(); held.responseReceived(200); held.close(); held.close(); model.count(1,0);
        }));
        checks.add(new Check("late owner cannot return a reconciled receipt", () -> {
            var model = new Model(); var held = model.hold(); held.beforeTransport(); model.loseHolder();
            try (var admin = model.session().proxy) {
                var receipt = model.queue.getFirst();
                ArtemisProviderConcurrencyPermits.returnReceipt(admin, model.destination, GROUP, ID,
                        receipt.getStringProperty("attemptId"), 1);
            } catch (JMSException e) { throw new AssertionError(e); }
            model.count(1,0); held.responseReceived(200); held.close(); held.close(); model.count(1,0);
        }));
        checks.add(new Check("second start is rejected without extra capacity", () -> {
            var model = new Model(); var held = model.hold(); held.beforeTransport(); fails(held::beforeTransport);
            held.close(); model.count(0,1);
        }));
        checks.add(new Check("wrong receipt authority is never returned as capacity", () -> {
            var model = new Model(); var held = model.hold(); held.beforeTransport();
            try { model.holder.received.getFirst().setStringProperty("quotaGroup", "foreign"); }
            catch (JMSException e) { throw new AssertionError(e); }
            held.responseReceived(200); held.close(); model.count(0,1);
        }));
        checks.add(new Check("selector rejects noncanonical or injected IDs", () -> {
            try {
                ArtemisProviderConcurrencyPermits.receiptSelector("' OR TRUE", UUID.randomUUID().toString());
                throw new AssertionError("selector injection was accepted");
            } catch (IllegalArgumentException expected) { }
        }));
        for (int status : new int[]{200,400,401,403,404,413,415,422,429}) {
            checks.add(new Check("terminal response " + status + " returns one slot", () -> {
                var model = new Model(); var held = model.hold(); held.beforeTransport();
                held.responseReceived(status); held.close(); held.close(); model.count(1,0);
            }));
        }
        for (int status : new int[]{202,206,302,408,500,502,503,504}) {
            checks.add(new Check("uncertain response " + status + " remains unavailable", () -> {
                var model = new Model(); var held = model.hold(); held.beforeTransport();
                held.responseReceived(status); held.close(); model.count(0,1);
            }));
        }
        checks.add(new Check("reconciliation rejects unconfirmed upstream before connecting", () -> {
            rejectsArgument(() -> ArtemisProviderPermitProvisioner.reconcile(null, "p", GROUP, ID,
                    UUID.randomUUID().toString(), false, true));
        }));
        checks.add(new Check("reconciliation rejects live workers before connecting", () -> {
            rejectsArgument(() -> ArtemisProviderPermitProvisioner.reconcile(null, "p", GROUP, ID,
                    UUID.randomUUID().toString(), true, false));
        }));
        checks.add(new Check("reconciliation rejects malformed permit authority before connecting", () -> {
            rejectsArgument(() -> ArtemisProviderPermitProvisioner.reconcile(null, "p", GROUP, "invalid", ID, true, true));
        }));
        checks.add(new Check("reconciliation rejects malformed attempt authority before connecting", () -> {
            rejectsArgument(() -> ArtemisProviderPermitProvisioner.reconcile(null, "p", GROUP, ID, "invalid", true, true));
        }));
        checks.add(new Check("inspection rejects zero and excessive bounds before connecting", () -> {
            rejectsArgument(() -> ArtemisProviderPermitProvisioner.inspect(null, "p", GROUP, 0));
            rejectsArgument(() -> ArtemisProviderPermitProvisioner.inspect(null, "p", GROUP, 1001));
        }));
        checks.add(new Check("inspection results are immutable detached snapshots", () -> {
            var input = new ArrayList<ArtemisProviderPermitProvisioner.QuarantinedPermit>();
            input.add(new ArtemisProviderPermitProvisioner.QuarantinedPermit(ID, ID, 1));
            var result = new ArtemisProviderPermitProvisioner.Inspection(input, true);
            input.clear();
            if (result.receipts().size() != 1 || !result.truncated()) throw new AssertionError("Inspection was not retained");
            try { result.receipts().clear(); throw new AssertionError("Inspection is mutable"); }
            catch (UnsupportedOperationException expected) { }
        }));
        checks.add(new Check("receipt acquisition failure keeps committed capacity unavailable", () -> {
            var model = new Model(); model.failReceiptReceive = true; var held = model.hold();
            fails(held::beforeTransport); held.close(); model.count(0,1);
        }));
        checks.add(new Check("missing CLI reconciliation confirmation is rejected before configuration", () -> {
            rejectsArgument(() -> ArtemisProviderPermitProvisioner.main(new String[]{"--reconcile", GROUP, ID, ID,
                    "--confirmed-upstream-stopped"}));
        }));
        checks.add(new Check("production metrics wrapper forwards durable start and complete response", () -> {
            var model = new Model();
            var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            try {
                var wrapped = metered(model, registry).acquire(com.taxonomy.analysis.service.LlmProvider.OPENAI, () -> {});
                wrapped.beforeTransport();
                if (model.commits != 1) throw new AssertionError("Metrics bypassed durable start");
                wrapped.responseReceived(200); wrapped.close(); model.count(1,0);
            } finally { registry.close(); }
        }));
        checks.add(new Check("production metrics wrapper retains uncertain capacity", () -> {
            var model = new Model();
            var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            try {
                var wrapped = metered(model, registry).acquire(com.taxonomy.analysis.service.LlmProvider.OPENAI, () -> {});
                wrapped.beforeTransport(); wrapped.close(); model.count(0,1);
            } finally { registry.close(); }
        }));
        checks.add(new Check("production metrics wrapper propagates failed durable start", () -> {
            var model = new Model(); model.failCommit = 1;
            var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            try {
                var wrapped = metered(model, registry).acquire(com.taxonomy.analysis.service.LlmProvider.OPENAI, () -> {});
                fails(wrapped::beforeTransport); wrapped.close(); model.count(1,0);
            } finally { registry.close(); }
        }));
        checks.add(new Check("metrics close once without claiming quarantined capacity returned", () -> {
            var model = new Model();
            var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            try {
                var wrapped = metered(model, registry).acquire(com.taxonomy.analysis.service.LlmProvider.OPENAI, () -> {});
                wrapped.beforeTransport(); wrapped.close(); wrapped.close();
                if (registry.get("taxonomy.analysis.provider.lease").tag("outcome", "CLOSED").timer().count() != 1
                        || registry.get("taxonomy.analysis.provider.inflight").gauge().value() != 0)
                    throw new AssertionError("Local lease closure was not reported exactly once");
                model.count(0,1);
            } finally { registry.close(); }
        }));
        return List.copyOf(checks);
    }
    private static ProviderConcurrencyPermits metered(Model model, io.micrometer.core.instrument.MeterRegistry registry) {
        var settings = new ArtemisProviderPermitSettings("p", java.util.Map.of(
                com.taxonomy.analysis.service.LlmProvider.OPENAI.id(), GROUP), 1000);
        return new ArtemisAnalysisMetrics(registry).permits((provider, checkpoint) -> model.hold(), settings);
    }
    static void rejectsArgument(Runnable run) {
        try { run.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected argument rejection before broker access");
    }
    static void fails(Runnable run) {
        try { run.run(); } catch (ProviderConcurrencyPermits.UnavailableException expected) { return; }
        throw new AssertionError("Expected failed-closed provider capacity");
    }

    static final class Model {
        final List<Message> queue = new ArrayList<>();
        final Destination destination = new ActiveMQQueue("fixture-permits");
        int commits;
        int failCommit = -1;
        boolean failAfterApply;
        boolean failReceiptReceive;
        Tx holder;
        Model() {
            try { queue.add(ArtemisProviderConcurrencyPermits.token(session().proxy, GROUP, ID)); }
            catch (JMSException e) { throw new AssertionError(e); }
        }
        Tx session() { return new Tx(this); }
        void borrow() { holder = session(); holder.received.add(queue.removeFirst()); }
        ProviderConcurrencyPermits.Permit hold() {
            borrow();
            try {
                return new ArtemisProviderConcurrencyPermits.TransactionalPermit(holder.proxy, destination, GROUP, ID,
                        () -> session().proxy, () -> {}, 1, holder.proxy.createConsumer(destination));
            } catch (JMSException e) { throw new AssertionError(e); }
        }
        void loseHolder() { try { holder.proxy.close(); } catch (JMSException e) { throw new AssertionError(e); } }
        void count(int available, int receipts) {
            int a=0,r=0;
            try {
                for (var item : queue) {
                    if (item.getStringProperty("permitState") == null) a++; else r++;
                    if (item.getJMSDeliveryMode() != DeliveryMode.PERSISTENT || item.getJMSExpiration() != 0)
                        throw new AssertionError("capacity must stay durable and have no expiry");
                    if (((ActiveMQMessage)item).getCoreMessage().getBodySize() != 0)
                        throw new AssertionError("capacity must contain no payload");
                }
            } catch (JMSException e) { throw new AssertionError(e); }
            if (a!=available || r!=receipts) throw new AssertionError("Expected "+available+" available / "+receipts+" receipts, got "+a+" / "+r);
        }
    }
    static final class Tx {
        final Model model;
        final List<Message> received = new ArrayList<>();
        final List<Message> sent = new ArrayList<>();
        boolean closed;
        final Session proxy;
        Tx(Model model) {
            this.model=model;
            proxy = proxy(Session.class, (object, method, args) -> {
                if (method.getName().equals("close")) { rollback(); closed=true; return null; }
                if (closed) throw new JMSException("lost session");
                return switch (method.getName()) {
                    case "createMessage" -> { var core = new org.apache.activemq.artemis.core.client.impl.ClientMessageImpl(
                            ActiveMQMessage.TYPE, true, 0, 0, (byte)4, 512);
                        core.getBodyBuffer();
                        var message = new ActiveMQMessage(core, null); message.clearProperties(); yield message; }
                    case "createProducer" -> producer();
                    case "createConsumer" -> consumer(args.length > 1 ? (String)args[1] : null);
                    case "commit" -> { commit(); yield null; }
                    case "rollback" -> { rollback(); yield null; }
                    default -> throw new AssertionError("unexpected Session method " + method.getName());
                };
            });
        }
        void rollback() { model.queue.addAll(received); received.clear(); sent.clear(); }
        void commit() throws JMSException {
            boolean fail = ++model.commits == model.failCommit;
            if (fail && !model.failAfterApply) throw new JMSException("commit rejected");
            model.queue.addAll(sent); sent.clear(); received.clear();
            if (fail) throw new JMSException("commit acknowledgement lost");
        }
        MessageProducer producer() {
            return proxy(MessageProducer.class, (object, method, args) -> {
                if (closed) throw new JMSException("lost session");
                if (method.getName().equals("close")) return null;
                if (!method.getName().equals("send")) throw new AssertionError("unexpected producer call");
                var message = (Message)args[0];
                message.setJMSDeliveryMode((Integer)args[1]); message.setJMSExpiration((Long)args[3]);
                sent.add(message); return null;
            });
        }
        MessageConsumer consumer(String selector) {
            return proxy(MessageConsumer.class, (object, method, args) -> {
                if (method.getName().equals("close")) return null;
                if (closed) throw new JMSException("lost session");
                if (!method.getName().equals("receive")) throw new AssertionError("unexpected consumer call");
                if (model.failReceiptReceive && selector != null && selector.startsWith("permitState ="))
                    throw new JMSException("receipt transport interrupted");
                for (int i=0; i<model.queue.size(); i++) {
                    Message item=model.queue.get(i);
                    boolean matches = selector == null || selector.equals(ArtemisProviderConcurrencyPermits.AVAILABLE_SELECTOR)
                            && item.getStringProperty("permitState") == null;
                    if (selector != null && selector.startsWith("permitState =")) {
                        var values=java.util.regex.Pattern.compile("'([^']*)'").matcher(selector);
                        var expected=new ArrayList<String>(); while(values.find())expected.add(values.group(1));
                        matches=Objects.equals(item.getStringProperty("permitState"),expected.get(0))
                                && Objects.equals(item.getStringProperty("permitId"),expected.get(1))
                                && Objects.equals(item.getStringProperty("attemptId"),expected.get(2));
                    }
                    if(matches) { model.queue.remove(i); received.add(item); return item; }
                }
                return null;
            });
        }
    }
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}

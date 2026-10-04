package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.dag.AnalysisMessage;
import com.taxonomy.analysis.dag.AnalysisTaskMessage;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.dag.json.AnalysisMessageFormatException;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;

/** JMS mapping of the versioned JSON analysis contract. */
final class ArtemisAnalysisMessages {

    /** Artemis duplicate-detection header; an optimization, never the only idempotency guarantee. */
    static final String DUPLICATE_ID = "_AMQ_DUPL_ID";
    static final String SCHEMA_VERSION = "taxonomySchemaVersion";
    static final String REJECTION = "taxonomyRejection";
    static final String ORIGIN = "taxonomyOrigin";
    static final String DELIVERY_COUNT = "JMSXDeliveryCount";

    private ArtemisAnalysisMessages() { }

    static BytesMessage encode(Session session, AnalysisMessageCodec codec, AnalysisMessage message)
            throws JMSException {
        byte[] body = codec.encode(message);
        BytesMessage jms = session.createBytesMessage();
        jms.writeBytes(body);
        jms.setJMSType(message.envelope().messageType().name());
        jms.setIntProperty(SCHEMA_VERSION, message.envelope().schemaVersion());
        if (message instanceof AnalysisTaskMessage task) {
            jms.setStringProperty(DUPLICATE_ID, task.taskId().value());
        }
        return jms;
    }

    /** Read a bounded body without trusting the declared size. */
    static byte[] body(Message message) throws JMSException {
        if (!(message instanceof BytesMessage bytes)) {
            throw new AnalysisMessageFormatException(AnalysisMessageFormatException.Kind.MALFORMED,
                    "analysis messages must be bytes messages", null);
        }
        long length = bytes.getBodyLength();
        if (length > AnalysisMessageCodec.MAX_MESSAGE_BYTES) {
            throw new AnalysisMessageFormatException(AnalysisMessageFormatException.Kind.TOO_LARGE,
                    "message exceeds " + AnalysisMessageCodec.MAX_MESSAGE_BYTES + " bytes", null);
        }
        byte[] body = new byte[(int) length];
        bytes.reset();
        bytes.readBytes(body);
        return body;
    }

    static int deliveryAttempt(Message message) {
        try {
            return message.propertyExists(DELIVERY_COUNT) ? Math.max(1, message.getIntProperty(DELIVERY_COUNT)) : 1;
        } catch (JMSException | NumberFormatException unreadable) {
            return 1;
        }
    }
}

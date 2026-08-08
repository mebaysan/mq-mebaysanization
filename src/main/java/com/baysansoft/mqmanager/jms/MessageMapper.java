package com.baysansoft.mqmanager.jms;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.baysansoft.mqmanager.messaging.model.QueueMessageView;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.ObjectMessage;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;

/** Converts a JMS message into the view model, without ever deserializing a payload. */
@Component
public class MessageMapper {

    public QueueMessageView toView(Message message, int maxBodyBytes) throws JMSException {
        BodyRendering body = renderBody(message, maxBodyBytes);

        return new QueueMessageView(
                message.getJMSMessageID(),
                message.getJMSCorrelationID(),
                message.getJMSTimestamp() > 0 ? Instant.ofEpochMilli(message.getJMSTimestamp()) : null,
                message.getJMSPriority(),
                message.getJMSRedelivered(),
                message.getJMSType() != null ? message.getJMSType() : simpleTypeOf(message),
                body.text(),
                body.truncated(),
                readHeaders(message),
                readProperties(message),
                body.note());
    }

    private static Map<String, String> readHeaders(Message message) throws JMSException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("JMSMessageID", message.getJMSMessageID());
        put(headers, "JMSCorrelationID", message.getJMSCorrelationID());
        put(headers, "JMSType", message.getJMSType());
        headers.put("JMSTimestamp", String.valueOf(message.getJMSTimestamp()));
        headers.put("JMSPriority", String.valueOf(message.getJMSPriority()));
        headers.put("JMSDeliveryMode", message.getJMSDeliveryMode() == 2 ? "PERSISTENT" : "NON_PERSISTENT");
        headers.put("JMSRedelivered", String.valueOf(message.getJMSRedelivered()));
        headers.put("JMSExpiration", String.valueOf(message.getJMSExpiration()));
        if (message.getJMSDestination() != null) {
            headers.put("JMSDestination", String.valueOf(message.getJMSDestination()));
        }
        if (message.getJMSReplyTo() != null) {
            headers.put("JMSReplyTo", String.valueOf(message.getJMSReplyTo()));
        }
        return headers;
    }

    private static Map<String, String> readProperties(Message message) throws JMSException {
        Map<String, String> properties = new LinkedHashMap<>();
        Enumeration<?> names = message.getPropertyNames();
        while (names.hasMoreElements()) {
            String name = String.valueOf(names.nextElement());
            Object value = message.getObjectProperty(name);
            properties.put(name, value == null ? null : String.valueOf(value));
        }
        return properties;
    }

    private static BodyRendering renderBody(Message message, int maxBodyBytes) throws JMSException {
        if (message instanceof TextMessage text) {
            return truncate(text.getText(), maxBodyBytes, null);
        }
        if (message instanceof BytesMessage bytes) {
            long length = bytes.getBodyLength();
            int readable = (int) Math.min(length, maxBodyBytes);
            byte[] buffer = new byte[readable];
            bytes.reset();
            bytes.readBytes(buffer, readable);
            return new BodyRendering(new String(buffer, StandardCharsets.UTF_8),
                    length > readable,
                    "Bytes message (" + length + " bytes), shown decoded as UTF-8.");
        }
        if (message instanceof MapMessage map) {
            StringBuilder rendered = new StringBuilder();
            Enumeration<?> names = map.getMapNames();
            while (names.hasMoreElements()) {
                String name = String.valueOf(names.nextElement());
                rendered.append(name).append('=').append(map.getObject(name)).append('\n');
            }
            return truncate(rendered.toString(), maxBodyBytes,
                    "Map message, rendered as key=value lines.");
        }
        if (message instanceof ObjectMessage) {
            // Never getObject(). Deserializing an arbitrary payload merely to display it is remote code
            // execution waiting to happen, and this tool has no authentication in front of it.
            return new BodyRendering(null, false,
                    "Object message — the body is deliberately not deserialized, so only headers and "
                            + "properties are shown.");
        }
        if (message instanceof StreamMessage) {
            return new BodyRendering(null, false,
                    "Stream message — the body is not shown; headers and properties are available.");
        }
        return new BodyRendering(null, false,
                "Body type " + simpleTypeOf(message) + " is not rendered; headers and properties are shown.");
    }

    private static BodyRendering truncate(String text, int maxBodyBytes, String note) {
        if (text == null || text.length() <= maxBodyBytes) {
            return new BodyRendering(text, false, note);
        }
        return new BodyRendering(text.substring(0, maxBodyBytes), true, note);
    }

    private static String simpleTypeOf(Message message) {
        String name = message.getClass().getSimpleName();
        return name.isEmpty() ? message.getClass().getName() : name;
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private record BodyRendering(String text, boolean truncated, String note) {
    }
}

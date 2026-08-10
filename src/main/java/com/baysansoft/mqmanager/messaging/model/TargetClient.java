package com.baysansoft.mqmanager.messaging.model;

import java.util.Locale;

/**
 * Whether IBM MQ writes an MQRFH2 header ahead of the message body.
 *
 * <p>IBM MQ classes for JMS default every destination to {@code TARGCLIENT(JMS)}, which prefixes the
 * body with an MQRFH2 carrying the {@code mcd}, {@code jms} and {@code usr} folders. A JMS reader
 * consumes that header and never sees it. An application doing a native {@code MQGET} does not: the
 * header arrives as the first bytes of its payload, so a parser reading from byte zero fails on the
 * header rather than on the body. What lands in front of an XML document looks like this —
 *
 * <pre>
 * ....RFH .....&lt;mcd&gt;&lt;Msd&gt;jms_text&lt;/Msd&gt;&lt;/mcd&gt;
 * &lt;jms&gt;&lt;Dst&gt;queue:///DEV.QUEUE.1&lt;/Dst&gt;&lt;Tms&gt;1786349326018&lt;/Tms&gt;&lt;Dlv&gt;2&lt;/Dlv&gt;&lt;/jms&gt;
 * &lt;?xml version="1.0" encoding="UTF-8"?&gt;&lt;GetFlightRequest&gt;...
 * </pre>
 *
 * <p>and a Python 2.7 {@code xml.dom.minidom.parseString} raises
 * {@code ExpatError: not well-formed (invalid token): line 1, column 0}.
 *
 * <p><strong>{@link MessageType} does not fix this, and trying it first is the natural mistake.</strong>
 * Sending the same body as BYTES changes {@code <Msd>jms_text</Msd>} to {@code <Msd>jms_bytes</Msd>}
 * and leaves the header exactly where it was. The two are independent choices that compose into
 * different MQMD {@code Format} values:
 *
 * <table border="1">
 * <caption>What a native MQGET reader receives</caption>
 * <tr><th>Body type</th><th>Target client</th><th>MQMD Format</th><th>Payload</th></tr>
 * <tr><td>TEXT</td><td>JMS</td><td>{@code MQHRF2}</td><td>header, then body</td></tr>
 * <tr><td>BYTES</td><td>JMS</td><td>{@code MQHRF2}</td><td>header, then body</td></tr>
 * <tr><td>TEXT</td><td>MQ</td><td>{@code MQSTR}</td><td>body only, in the destination CCSID (1208),
 *     transcoded if the getter asks for {@code MQGMO_CONVERT}</td></tr>
 * <tr><td>BYTES</td><td>MQ</td><td>{@code MQFMT_NONE}</td><td>body only, byte-exact — conversion never
 *     applies to an unnamed format</td></tr>
 * </table>
 *
 * <p>Meaningful only on IBM MQ, and named after IBM's own vocabulary on purpose: this is
 * {@code TARGCLIENT} in MQSC, {@code targetClient} as a JMS destination property, and
 * {@code WMQ_TARGET_CLIENT} in the client jar. A friendlier invented name would not survive a search
 * against IBM's documentation, which is the only documentation there is for it.
 */
public enum TargetClient {

    /**
     * {@code WMQ_CLIENT_JMS_COMPLIANT}. An MQRFH2 is written ahead of the body — the client's own
     * default, and what every send did before this choice existed.
     */
    JMS,

    /**
     * {@code WMQ_CLIENT_NONJMS_MQ}. No MQRFH2: the queue holds the body and nothing else.
     *
     * <p>There is then no {@code usr} folder, which is the only place custom properties travel, so a
     * send carrying both is refused rather than quietly dropping them.
     */
    MQ;

    /** Lenient parse for a request field, so a bad value is a 400 rather than a 500. */
    public static TargetClient parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "Unknown target client '" + value + "'. Use JMS or MQ.");
        }
    }

    /**
     * Null for an absent value, so "the caller did not ask" stays distinguishable from "the caller
     * asked for JMS". That distinction is what lets the other three providers reject an explicit
     * choice without breaking every send that was written before the choice existed.
     */
    public static TargetClient parseOptional(String value) {
        return value == null || value.isBlank() ? null : parse(value);
    }
}

package com.baysansoft.mqmanager.jms.provider;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.baysansoft.mqmanager.config.MqManagerProperties;
import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.DestinationKind;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.jms.DestinationListRequest;
import com.baysansoft.mqmanager.jms.DestinationLister;
import com.baysansoft.mqmanager.messaging.model.DestinationEntry;
import com.baysansoft.mqmanager.messaging.model.DestinationListing;
import com.baysansoft.mqmanager.messaging.model.DestinationListings;
import com.ibm.mq.MQException;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;

/**
 * Lists IBM MQ objects over PCF, through {@code SYSTEM.ADMIN.COMMAND.QUEUE}.
 *
 * <p>The only lister that speaks no JMS at all: PCF is the base Java API and shares no type with
 * {@code jakarta.jms}. That is the reason {@code DestinationLister} lets each implementation open its
 * own connection instead of being handed a {@code Session}.
 *
 * <p>Two things have to be true on the queue manager for this to work: the command server must be
 * running ({@code START CMDSERV}) and the user must hold {@code +dsp} on it. Neither is a fault in this
 * application, so neither is an error — both come back as an unavailable listing with a reason.
 *
 * <p>Like {@link IbmMqConnectionFactoryBuilder}, this cannot be validated against a real queue manager
 * in this build, so correctness rests on the constant names being verified against the shipped jar and
 * on {@code IbmMqDestinationListerTest} reading the built connection properties back key by key.
 */
@Component
public class IbmMqDestinationLister implements DestinationLister {

    private static final String SOURCE = "the PCF command server";

    /** Objects the queue manager owns. Shown only when the user asks for internal destinations. */
    private static final String SYSTEM_PREFIX = "SYSTEM.";

    private static final String MODEL_NOTE =
            "Topics here are administrative topic objects, not topic strings. SYSTEM.* objects belong "
                    + "to the queue manager and are marked internal.";

    private final MqManagerProperties properties;

    public IbmMqDestinationLister(MqManagerProperties properties) {
        this.properties = properties;
    }

    @Override
    public Provider provider() {
        return Provider.IBM_MQ;
    }

    @Override
    public DestinationListing list(DestinationListRequest request) throws MQException {
        int limit = request.limit();
        int waitMs = (int) Math.min(Integer.MAX_VALUE,
                Math.max(request.timeout().toMillis(),
                        properties.getDestinations().getCommandWait().toMillis()));

        // com.ibm.mq.MQQueueManager is written out in full and never imported: the Jakarta client also
        // ships com.ibm.msg.client.jakarta.wmq.compat.base.internal.MQQueueManager, and ImportGuardTest
        // fails the build on either. Same discipline as the two ActiveMQConnectionFactory classes.
        //
        // Failing to CONNECT throws MQException, which the caller translates into the standard error
        // contract. Everything after this line degrades instead.
        com.ibm.mq.MQQueueManager queueManager =
                new com.ibm.mq.MQQueueManager(queueManagerName(request.profile()),
                        connectionProperties(request.profile(), request.plainPassword()));
        try {
            PCFMessageAgent agent;
            try {
                agent = new PCFMessageAgent(queueManager);
                agent.setWaitInterval(waitMs);
            } catch (MQDataException e) {
                // Connected to the queue manager, but SYSTEM.ADMIN.COMMAND.QUEUE could not be opened.
                // The connection is fine, so this degrades rather than throwing.
                return DestinationListing.unavailable(limit, SOURCE, classify(e.getReason()),
                        describe(e.getReason()));
            }
            try {
                List<DestinationEntry> found = new ArrayList<>();
                toEntries(inquireQueues(agent, request.prefix()), DestinationKind.QUEUE, found);

                // The second call is where graceful degradation earns its keep: queues are already in
                // hand, so a refusal here narrows the answer rather than destroying it.
                try {
                    toEntries(inquireTopics(agent, request.prefix()), DestinationKind.TOPIC, found);
                } catch (PcfRefusedException e) {
                    return DestinationListings
                            .finish(found, request.kind(), request.prefix(), limit, SOURCE, MODEL_NOTE)
                            .toPartial(e.reason(),
                                    "Queues were listed, but the queue manager would not return its "
                                            + "topic objects, so topics are missing from this list. "
                                            + e.getMessage() + " " + MODEL_NOTE);
                }

                return DestinationListings.finish(found, request.kind(), request.prefix(), limit,
                        SOURCE, MODEL_NOTE);

            } catch (PcfRefusedException e) {
                return DestinationListing.unavailable(limit, SOURCE, e.reason(), e.getMessage());
            } finally {
                disconnectQuietly(agent);
            }
        } finally {
            disconnectQueueManagerQuietly(queueManager);
        }
    }

    /**
     * {@code prefix*} is PCF's generic name and is genuinely a prefix match, which is the one place a
     * {@code DestinationQuery} prefix is pushed down to the broker rather than applied here. The
     * meaning is identical either way, so nothing drifts.
     */
    private static String[] inquireQueues(PCFMessageAgent agent, String prefix)
            throws PcfRefusedException {
        PCFMessage request = new PCFMessage(MQConstants.MQCMD_INQUIRE_Q_NAMES);
        request.addParameter(MQConstants.MQCA_Q_NAME, genericName(prefix));
        request.addParameter(MQConstants.MQIA_Q_TYPE, MQConstants.MQQT_ALL);
        return send(agent, request, MQConstants.MQCACF_Q_NAMES);
    }

    private static String[] inquireTopics(PCFMessageAgent agent, String prefix)
            throws PcfRefusedException {
        PCFMessage request = new PCFMessage(MQConstants.MQCMD_INQUIRE_TOPIC_NAMES);
        request.addParameter(MQConstants.MQCA_TOPIC_NAME, genericName(prefix));
        return send(agent, request, MQConstants.MQCACF_TOPIC_NAMES);
    }

    private static String[] send(PCFMessageAgent agent, PCFMessage request, int namesParameter)
            throws PcfRefusedException {
        try {
            PCFMessage[] responses = agent.send(request);
            if (responses.length == 0) {
                return new String[0];
            }
            String[] names = responses[0].getStringListParameterValue(namesParameter);
            return names == null ? new String[0] : names;
        } catch (MQDataException e) {
            // PCFException extends MQDataException rather than MQException, so IbmMqDiagnostics does
            // not see it — the reason code is read straight off the field it publishes.
            throw new PcfRefusedException(e.getReason(), describe(e.getReason()));
        } catch (Exception e) {
            // agent.send also declares IOException. A garbled reply is the command server declining
            // in an unhelpful way, not a broken connection.
            throw new PcfRefusedException(0, "The queue manager's reply could not be read. "
                    + e.getMessage());
        }
    }

    private static void toEntries(String[] names, DestinationKind kind, List<DestinationEntry> found) {
        for (String raw : names) {
            if (raw == null) {
                continue;
            }
            // PCF pads names to the fixed MQ field width with spaces.
            String name = raw.trim();
            if (!name.isEmpty()) {
                found.add(new DestinationEntry(name, kind, isInternal(name)));
            }
        }
    }

    /**
     * Builds the client-mode connection properties, mirroring {@link IbmMqConnectionFactoryBuilder}
     * key for key. Package-private so a test can assert them without a queue manager — which is the
     * only thing that catches a wrong {@code CMQC.*} constant in a build with no IBM MQ to test against.
     */
    static Hashtable<String, Object> connectionProperties(ConnectionProfile profile,
            String plainPassword) {
        Hashtable<String, Object> connection = new Hashtable<>();

        // Client (TCP) mode, always. Bindings mode routes into code that calls System.loadLibrary and
        // would break the "a Java 21 JRE and nothing else" promise.
        connection.put(CMQC.TRANSPORT_PROPERTY, CMQC.TRANSPORT_MQSERIES_CLIENT);
        connection.put(CMQC.HOST_NAME_PROPERTY, profile.getHost());
        connection.put(CMQC.PORT_PROPERTY, profile.getPort() == null ? 1414 : profile.getPort());
        connection.put(CMQC.CHANNEL_PROPERTY, StringUtils.hasText(profile.getChannel())
                ? profile.getChannel()
                : IbmMqConnectionFactoryBuilder.DEFAULT_CHANNEL);
        connection.put(CMQC.APPNAME_PROPERTY, IbmMqConnectionFactoryBuilder.APPLICATION_NAME);

        if (StringUtils.hasText(profile.getUsername())) {
            // Explicit for the same reason the JMS builder is explicit: a JVM-wide switch or an
            // mqclient.ini stanza must not be able to drop this into compatibility mode, which
            // truncates user IDs at 12 characters and surfaces as a baffling 2035.
            connection.put(CMQC.USE_MQCSP_AUTHENTICATION_PROPERTY, true);
            connection.put(CMQC.USER_ID_PROPERTY, profile.getUsername());
            connection.put(CMQC.PASSWORD_PROPERTY, plainPassword == null ? "" : plainPassword);
        }
        return connection;
    }

    /** An empty queue manager name is legal and means "the default queue manager". */
    static String queueManagerName(ConnectionProfile profile) {
        return profile.getQueueManagerName() == null ? "" : profile.getQueueManagerName();
    }

    static String genericName(String prefix) {
        return StringUtils.hasText(prefix) ? prefix.trim() + "*" : "*";
    }

    static boolean isInternal(String name) {
        return name != null && name.startsWith(SYSTEM_PREFIX);
    }

    /**
     * Maps an MQ reason code onto a listing reason. Package-private and static so every branch is
     * unit-testable without a queue manager.
     */
    static String classify(int reasonCode) {
        return switch (reasonCode) {
            // No +dsp on the queue manager, or no put authority on the command queue.
            case MQConstants.MQRC_NOT_AUTHORIZED -> DestinationListing.NOT_PERMITTED;
            // SYSTEM.ADMIN.COMMAND.QUEUE is not defined on this queue manager.
            case MQConstants.MQRC_UNKNOWN_OBJECT_NAME -> DestinationListing.NOT_AVAILABLE;
            // No reply inside the wait interval, which almost always means the command server is stopped.
            case MQConstants.MQRC_NO_MSG_AVAILABLE -> DestinationListing.TIMED_OUT;
            // The queue manager understood the command and refused it, or does not support it.
            case MQConstants.MQRCCF_COMMAND_FAILED, MQConstants.MQRCCF_CFH_COMMAND_ERROR ->
                    DestinationListing.NOT_AVAILABLE;
            default -> DestinationListing.NOT_AVAILABLE;
        };
    }

    /** The sentence shown to the user for a reason code, naming the fix where there is one. */
    static String describe(int reasonCode) {
        String symbolic = symbolicName(reasonCode);
        return switch (reasonCode) {
            case MQConstants.MQRC_NOT_AUTHORIZED -> symbolic
                    + ": this user is not authorised to inquire objects. It needs +dsp on the queue "
                    + "manager and put authority on SYSTEM.ADMIN.COMMAND.QUEUE.";
            case MQConstants.MQRC_UNKNOWN_OBJECT_NAME -> symbolic
                    + ": SYSTEM.ADMIN.COMMAND.QUEUE is not defined on this queue manager, so PCF "
                    + "commands cannot be sent to it.";
            case MQConstants.MQRC_NO_MSG_AVAILABLE -> symbolic
                    + ": the queue manager did not reply in time. The command server is probably not "
                    + "running — start it with START CMDSERV.";
            case MQConstants.MQRCCF_COMMAND_FAILED, MQConstants.MQRCCF_CFH_COMMAND_ERROR -> symbolic
                    + ": the queue manager refused the inquire command.";
            default -> symbolic + ": the queue manager would not list its objects.";
        };
    }

    private static String symbolicName(int reasonCode) {
        try {
            return MQConstants.lookupReasonCode(reasonCode);
        } catch (RuntimeException e) {
            return "MQRC_" + reasonCode;
        }
    }

    private static void disconnectQuietly(PCFMessageAgent agent) {
        try {
            agent.disconnect();
        } catch (MQDataException e) {
            // The queue manager handle is closed immediately after this, which reaps the agent's queues.
        }
    }

    /** A failed disconnect must not mask the listing, or the failure, we already have in hand. */
    private static void disconnectQueueManagerQuietly(com.ibm.mq.MQQueueManager queueManager) {
        try {
            queueManager.disconnect();
        } catch (MQException e) {
            // The socket goes with the process's connection either way.
        }
    }

    /** A command the queue manager would not run. Never leaves this class. */
    private static final class PcfRefusedException extends Exception {

        private final transient int reasonCode;

        PcfRefusedException(int reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }

        String reason() {
            return classify(reasonCode);
        }
    }
}

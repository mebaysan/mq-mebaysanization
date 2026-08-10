import type { Provider } from '../../api/types'

/**
 * The single source of truth for provider-specific UI copy.
 *
 * <p>These are not cosmetic. The brokers genuinely differ in what a browse can see, whether a single
 * message can be deleted at any depth, and whether typing a name creates a destination — and a tool
 * that hides those differences will mislead its user. Kafka differs furthest of all: its destination
 * is a topic, its depth counts retained records rather than a backlog, and a single record cannot be
 * deleted at any price.
 *
 * <p>Because this is a `Record<Provider, …>`, adding a provider to the union makes this file a compile
 * error until every field is filled in. That is deliberate: silence would mean a new broker inherits
 * another broker's caveats.
 */
export interface ProviderCaveat {
  /** Lower-case noun for the destination, used mid-sentence. */
  noun: string
  /** Capitalised noun, used in headings, labels and buttons. */
  Noun: string
  /** Placeholder for the destination-name input, with a realistic example name. */
  namePlaceholder: string
  /** Body copy for the "nothing opened yet" empty state. */
  chooseNote: string
  /** Shown when a destination is opened and turns out to be empty. */
  emptyNote: string
  /** Label above the depth figure. Kafka's number is not a backlog, so it is not called "Depth". */
  depthLabel: string
  /** False hides the per-row Delete button entirely — see `supportsSingleMessageDelete` on the enum. */
  canDeleteOneMessage: boolean
  /** Extra warning inside the delete confirmation, or null when there is nothing to add. */
  deleteNote: string | null
  /** True shows the optional record-key field on the send form. */
  hasMessageKey: boolean
  /**
   * True shows the Text/Bytes choice on the send form. False on Kafka, whose record values are already
   * bytes — the server rejects an explicit choice there rather than ignoring one.
   */
  hasMessageType: boolean
  /**
   * True shows the JMS/MQ header choice on the send form. IBM MQ only — it is the only provider that
   * writes a header of its own (the MQRFH2) ahead of the body, and the only one where a native reader
   * has to be told not to expect one.
   */
  hasTargetClient: boolean
  /** What the send form calls its key/value rows. */
  propertiesLabel: string
  /** Label for the button and the modal that browses the broker, e.g. "Browse queues". */
  listLabel: string
  /** How this broker is asked for its destinations, and what can stop it answering. */
  listNote: string
  /** False when this provider only ever has one kind of destination, which hides the kind filter. */
  listsTopics: boolean
}

export const PROVIDER_CAVEATS: Record<Provider, ProviderCaveat> = {
  ACTIVE_MQ: {
    noun: 'queue',
    Noun: 'Queue',
    namePlaceholder: 'Queue name, e.g. DEV.QUEUE.1',
    chooseNote:
      'Type a queue name to browse it. This broker creates a queue on first use, so a typo will ' +
      'show up as a new empty queue.',
    emptyNote:
      'ActiveMQ Classic creates a queue on first use, so a name typed by mistake will simply appear ' +
      'here as an empty queue rather than an error.',
    depthLabel: 'Depth',
    canDeleteOneMessage: true,
    deleteNote:
      'On very deep queues ActiveMQ Classic will not hand a specific message to a selector, so an ' +
      'individual delete can fail even though the message is visible above.',
    hasMessageKey: false,
    hasMessageType: true,
    hasTargetClient: false,
    propertiesLabel: 'Custom properties',
    listLabel: 'Browse queues and topics',
    listNote:
      'ActiveMQ Classic publishes its destinations on advisory topics, which ride the same ' +
      'connection. A broker running with advisorySupport=false sends nothing, and from a client ' +
      'that is indistinguishable from a broker with no destinations — so an empty answer is ' +
      'reported as "could not list", never as "there is nothing here".',
    listsTopics: true,
  },
  ARTEMIS: {
    noun: 'queue',
    Noun: 'Queue',
    namePlaceholder: 'Queue name, e.g. DEV.QUEUE.1',
    chooseNote:
      'Type a queue name to browse it. This broker creates a queue on first use, so a typo will ' +
      'show up as a new empty queue.',
    emptyNote:
      'Artemis creates queues on demand and deletes them again once they are idle and empty, so an ' +
      'empty queue here may disappear on its own.',
    depthLabel: 'Depth',
    canDeleteOneMessage: true,
    deleteNote: null,
    hasMessageKey: false,
    hasMessageType: true,
    hasTargetClient: false,
    propertiesLabel: 'Custom properties',
    listLabel: 'Browse queues and topics',
    listNote:
      'Artemis answers a management request sent to activemq.management. A broker that disables ' +
      'that address, or a user without permission to send to it, cannot be listed. Artemis has ' +
      'addresses and queues rather than JMS queues and topics: an address with a queue of the same ' +
      'name is shown as a queue, one without is shown as a topic.',
    listsTopics: true,
  },
  IBM_MQ: {
    noun: 'queue',
    Noun: 'Queue',
    namePlaceholder: 'Queue name, e.g. DEV.QUEUE.1',
    chooseNote:
      'Type the name of a queue that already exists on the queue manager. IBM MQ does not create ' +
      'queues on demand.',
    emptyNote:
      'IBM MQ never creates queues on demand. An empty result means the queue exists but holds no ' +
      'messages; a queue that does not exist reports MQRC 2085.',
    depthLabel: 'Depth',
    canDeleteOneMessage: true,
    deleteNote: null,
    hasMessageKey: false,
    hasMessageType: true,
    hasTargetClient: true,
    propertiesLabel: 'Custom properties',
    listLabel: 'Browse queues and topics',
    listNote:
      'IBM MQ is asked over PCF, through SYSTEM.ADMIN.COMMAND.QUEUE. That needs the command server ' +
      'running (START CMDSERV) and +dsp authority on the queue manager. Topics listed are ' +
      'administrative topic objects, not topic strings, and SYSTEM.* objects are marked internal.',
    listsTopics: true,
  },
  KAFKA: {
    noun: 'topic',
    Noun: 'Topic',
    namePlaceholder: 'Topic name, e.g. orders',
    chooseNote:
      'Type a topic name to read it. Kafka may not create a topic on first use — most clusters run ' +
      'with auto.create.topics.enable turned off, in which case an unknown name is an error rather ' +
      'than a new empty topic.',
    emptyNote:
      'This topic holds no records right now. On Kafka that can mean the topic exists but is empty, ' +
      'that every record has aged out of the retention window, or — if the cluster does not create ' +
      'topics on demand — that the topic does not exist at all.',
    // Not "Depth": the number below counts records retained on the topic, which is not a backlog.
    depthLabel: 'Retained',
    canDeleteOneMessage: false,
    deleteNote: null,
    hasMessageKey: true,
    hasMessageType: false,
    hasTargetClient: false,
    propertiesLabel: 'Record headers',
    listLabel: 'Browse topics',
    listNote:
      'Kafka lists every topic this user is allowed to describe. A cluster that withholds Describe ' +
      'on itself cannot be listed, though reading one named topic still works. Internal topics such ' +
      'as __consumer_offsets are marked as the broker reports them, not guessed at by name.',
    listsTopics: false,
  },
}

/**
 * Copy for a provider that has not loaded yet. Queue wording, because three of the four use it and a
 * blank label reads as a rendering bug.
 */
export const DEFAULT_CAVEAT: ProviderCaveat = PROVIDER_CAVEATS.ACTIVE_MQ

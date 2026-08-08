import type { Provider } from '../../api/types'

/**
 * Which connection fields each provider actually uses.
 *
 * <p>The server rejects a field that does not apply — a Kafka profile carrying a host, or an ActiveMQ
 * profile carrying bootstrap servers, is a 400 — because a row that held two addressing styles would
 * leave no way to tell which one was meant. The form hides those fields rather than letting someone
 * fill in a combination that can only be refused.
 *
 * <p>A `Record<Provider, …>` so `tsc` refuses to build until a new provider declares its own shape.
 */
export interface ProviderFields {
  label: string
  blurb: string
  /** Addressed by host + port, like every JMS broker here. */
  hostAndPort: boolean
  defaultPort: number | null
  /** Addressed by a comma-separated seed-broker list, like Kafka. */
  bootstrapServers: boolean
  queueManagerAndChannel: boolean
  brokerUrlOverride: boolean
  /** Extra warning under the credentials, or null when there is nothing unusual to say. */
  credentialsNote: string | null
}

export const PROVIDER_FIELDS: Record<Provider, ProviderFields> = {
  ACTIVE_MQ: {
    label: 'ActiveMQ Classic',
    blurb: 'Apache ActiveMQ Classic 6.x. Default port 61616.',
    hostAndPort: true,
    defaultPort: 61616,
    bootstrapServers: false,
    queueManagerAndChannel: false,
    brokerUrlOverride: true,
    credentialsNote: null,
  },
  ARTEMIS: {
    label: 'ActiveMQ Artemis',
    blurb: 'Apache ActiveMQ Artemis. Default port 61616.',
    hostAndPort: true,
    defaultPort: 61616,
    bootstrapServers: false,
    queueManagerAndChannel: false,
    brokerUrlOverride: true,
    credentialsNote: null,
  },
  IBM_MQ: {
    label: 'IBM MQ',
    blurb: 'Pure-Java client, client mode over TCP. Default port 1414.',
    hostAndPort: true,
    defaultPort: 1414,
    bootstrapServers: false,
    queueManagerAndChannel: true,
    brokerUrlOverride: false,
    credentialsNote: null,
  },
  KAFKA: {
    label: 'Apache Kafka',
    blurb: 'Seed brokers instead of one host and port. Default port 9092.',
    hostAndPort: false,
    defaultPort: null,
    bootstrapServers: true,
    queueManagerAndChannel: false,
    brokerUrlOverride: false,
    credentialsNote:
      'Credentials are sent to Kafka as SASL/PLAIN over an unencrypted connection, because this ' +
      'version does not support TLS to brokers. Leave both blank for an unauthenticated cluster.',
  },
}

export const PROVIDERS: Provider[] = ['ACTIVE_MQ', 'ARTEMIS', 'IBM_MQ', 'KAFKA']

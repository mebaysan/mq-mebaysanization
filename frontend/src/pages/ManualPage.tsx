import type { ReactNode } from 'react'
import { Link } from 'react-router'

import type { Provider } from '../api/types'
import { ProviderBadge } from '../components/Primitives'
import { PROVIDERS, PROVIDER_FIELDS } from '../features/connections/ProviderFields'
import { PROVIDER_CAVEATS } from '../features/queue/ProviderCaveats'

/**
 * How to use the tool.
 *
 * <p>Every provider-specific fact on this page is READ FROM the same modules the rest of the app
 * reads — `PROVIDER_FIELDS` for which connection fields apply, `PROVIDER_CAVEATS` for what a
 * destination is called and whether a single message can be deleted. Restating them here in prose
 * would mean a manual that quietly goes stale the first time a provider is added, which is the one
 * failure mode a manual cannot afford.
 */

const SECTIONS = [
  { id: 'start', title: 'Before you start' },
  { id: 'connections', title: '1. Create a connection' },
  { id: 'discover', title: '2. Find a queue or topic' },
  { id: 'open', title: '3. Open a queue or topic' },
  { id: 'browse', title: '4. Read messages' },
  { id: 'depth', title: '5. Read the depth' },
  { id: 'send', title: '6. Send a message' },
  { id: 'delete', title: '7. Delete one message' },
  { id: 'purge', title: '8. Purge everything' },
  { id: 'logs', title: 'Watching what it is doing' },
  { id: 'differences', title: 'Provider differences at a glance' },
  { id: 'errors', title: 'When something goes wrong' },
  { id: 'scope', title: 'What this tool will not do' },
] as const

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section id={id} className="scroll-mt-6">
      <h2 className="text-base font-semibold text-slate-900">{title}</h2>
      <div className="mt-3 space-y-3 text-sm leading-6 text-slate-700">{children}</div>
    </section>
  )
}

/** A caveat that matters enough to interrupt reading for. */
function Warn({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900">
      {children}
    </p>
  )
}

function Code({ children }: { children: ReactNode }) {
  return (
    <code className="rounded bg-slate-100 px-1 py-0.5 font-mono text-xs text-slate-800">
      {children}
    </code>
  )
}

function Table({ head, children }: { head: string[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto rounded-xl border border-slate-200">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
          <tr>
            {head.map((cell) => (
              <th key={cell} className="whitespace-nowrap px-3 py-2 font-medium">
                {cell}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 align-top">{children}</tbody>
      </table>
    </div>
  )
}

function Yes() {
  return <span className="font-medium text-slate-800">yes</span>
}

function No() {
  return <span className="text-slate-400">—</span>
}

/** How a provider is addressed, derived rather than described. */
function addressing(provider: Provider): string {
  const fields = PROVIDER_FIELDS[provider]
  if (fields.bootstrapServers) return 'Bootstrap servers'
  if (fields.brokerUrlOverride) return `Host + port (default ${fields.defaultPort}), or a broker URL`
  return `Host + port (default ${fields.defaultPort})`
}

export default function ManualPage() {
  return (
    <div className="max-w-3xl space-y-8">
      <div>
        <h1 className="text-xl font-semibold text-slate-900">Manual</h1>
        <p className="mt-1 text-sm text-slate-600">
          What this tool does, in the order you will need it. Everything here applies to whichever
          broker you point it at; where the brokers genuinely differ, the difference is called out
          rather than smoothed over.
        </p>
      </div>

      <nav aria-label="Contents" className="rounded-xl border border-slate-200 bg-white p-4">
        <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Contents</p>
        <ol className="mt-2 grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
          {SECTIONS.map((section) => (
            <li key={section.id}>
              <a href={`#${section.id}`} className="text-brand-700 hover:underline">
                {section.title}
              </a>
            </li>
          ))}
        </ol>
      </nav>

      <Section id="start" title="Before you start">
        <Warn>
          <strong>There is no login.</strong> Anyone who can reach this page can read, send, delete
          and purge messages on every broker configured here, using the stored credentials. Run it on
          localhost or behind an authenticating proxy — never on a network you do not control.
        </Warn>
        <p>
          Broker passwords are encrypted before being stored and are never sent back to this page. If
          you see a connection flagged as unreadable, the encryption key has changed or been lost:
          edit the connection and re-enter the password.
        </p>
        <p>
          Connections to brokers are plaintext TCP. TLS is not supported in this version, so treat any
          credentials you enter as travelling in the clear.
        </p>
      </Section>

      <Section id="connections" title="1. Create a connection">
        <p>
          A connection is a saved broker endpoint. Go to{' '}
          <Link to="/connections" className="text-brand-700 hover:underline">
            Connections
          </Link>{' '}
          and choose <strong>New connection</strong>. Pick the provider first — the form then shows
          only the fields that provider actually uses, because a saved connection carrying two
          addressing styles would be ambiguous and is rejected.
        </p>
        <Table head={['Provider', 'Addressed by', 'Also required', 'Broker URL override']}>
          {PROVIDERS.map((provider) => {
            const fields = PROVIDER_FIELDS[provider]
            return (
              <tr key={provider}>
                <td className="px-3 py-2">
                  <ProviderBadge provider={provider} label={fields.label} />
                </td>
                <td className="px-3 py-2">{addressing(provider)}</td>
                <td className="px-3 py-2">
                  {fields.queueManagerAndChannel ? 'Queue manager + channel' : <No />}
                </td>
                <td className="px-3 py-2">{fields.brokerUrlOverride ? <Yes /> : <No />}</td>
              </tr>
            )
          })}
        </Table>
        <p>
          Use <strong>Test connection</strong> before saving. It reports success or a specific reason
          for failure, and it never changes anything on the broker. A dead host fails in a few
          seconds rather than hanging.
        </p>
        <p>
          <strong>Editing a password.</strong> The stored password is never sent to this page, so the
          field always looks empty. Leaving it blank keeps the stored password; typing a new one
          replaces it; explicitly clearing it removes it.
        </p>
      </Section>

      <Section id="discover" title="2. Find a queue or topic">
        <p>
          <strong>Browse…</strong> next to the name box asks the broker what it has. It is read-only:
          nothing in that list creates, changes or deletes anything. Filter the loaded list as you
          type, switch between queues and topics, and click a name to open it.
        </p>
        <p>
          Names the broker owns rather than you — its own system and internal destinations — are hidden
          behind <strong>Show internal</strong>. If the list was cut short, use{' '}
          <strong>Name starts with</strong>: that goes back to the broker and asks for a narrower set,
          which is the only way past the cap.
        </p>
        <p>
          Each broker is asked a different way, and each of those ways can be switched off by whoever
          runs it:
        </p>
        <Table head={['Provider', 'Lists', 'How, and what can stop it']}>
          {PROVIDERS.map((provider) => (
            <tr key={provider}>
              <td className="px-3 py-2">
                <ProviderBadge provider={provider} label={PROVIDER_FIELDS[provider].label} />
              </td>
              <td className="px-3 py-2">
                {PROVIDER_CAVEATS[provider].listsTopics ? 'Queues and topics' : 'Topics'}
              </td>
              <td className="px-3 py-2 text-slate-600">{PROVIDER_CAVEATS[provider].listNote}</td>
            </tr>
          ))}
        </Table>
        <Warn>
          <strong>An empty list and “the broker will not say” are different answers</strong>, and this
          tool shows them differently. When it says it could not list, that is broker policy rather
          than a problem with your connection — type the name instead, and everything else works
          normally.
        </Warn>
        <p>
          There is no depth column here on purpose. Two of the four brokers could answer it cheaply and
          two could not — on those it would mean reading every message on every queue to count them.
          Open a destination to see its depth.
        </p>
        <p>
          Destinations you open are <strong>remembered</strong> and appear as chips under the name box,
          pinned ones first and then the most recent. Pin one to keep it there; the rest are trimmed as
          the list fills up. They are stored with the connection rather than in this browser, so they
          are still there from another machine — and they still work on a broker that refuses to be
          browsed. Removing a chip forgets the bookmark and changes nothing on the broker.
        </p>
      </Section>

      <Section id="open" title="3. Open a queue or topic">
        <p>
          Names can be <strong>browsed or typed</strong>. Browsing asks the broker; typing always works,
          including on a broker that will not be browsed. The active destination is kept in the address
          bar, so a link or a refresh keeps working.
        </p>
        <p>
          What counts as a destination differs by provider, and so does what happens when you type a
          name that does not exist:
        </p>
        <Table head={['Provider', 'Destination', 'A name that does not exist']}>
          {PROVIDERS.map((provider) => (
            <tr key={provider}>
              <td className="px-3 py-2">
                <ProviderBadge provider={provider} label={PROVIDER_FIELDS[provider].label} />
              </td>
              <td className="px-3 py-2">{PROVIDER_CAVEATS[provider].Noun}</td>
              <td className="px-3 py-2 text-slate-600">{PROVIDER_CAVEATS[provider].chooseNote}</td>
            </tr>
          ))}
        </Table>
      </Section>

      <Section id="browse" title="4. Read messages">
        <p>
          Browsing is <strong>non-destructive</strong>. Messages are read and left exactly where they
          were — nothing is consumed, and on Kafka no consumer-group offset is committed, so browsing
          a production topic cannot disturb the consumers reading it.
        </p>
        <p>
          The list always says <em>“showing the first N”</em> and never claims a total, because on
          some brokers a browse genuinely cannot see the whole queue. When more may be waiting, the
          list says so. A note under the list explains what that particular broker's browse can and
          cannot see.
        </p>
        <p>
          Click a message id to expand it. Long bodies are shortened in the list and fetched in full
          only when you expand them. The expanded view splits metadata in two:
        </p>
        <ul className="ml-5 list-disc space-y-1">
          <li>
            <strong>Headers</strong> — what the broker set: ids, timestamps, priority, and on Kafka
            the partition, offset and record key.
          </li>
          <li>
            <strong>Properties</strong> — what the sender set. These are the same key/value pairs the
            send form writes, so what you send comes back here.
          </li>
        </ul>
        <p>
          Message bodies of type <Code>ObjectMessage</Code> are deliberately not decoded — only their
          headers and properties are shown. Deserializing an arbitrary payload just to display it
          would be a remote-code-execution risk in a tool with no login.
        </p>
      </Section>

      <Section id="depth" title="5. Read the depth">
        <p>
          The number at the top of the destination is how many messages are there. When it is shown as{' '}
          <Code>N+</Code>, the real figure is <em>at least</em> N — the broker could not promise a
          total, so the tool will not print one. An empty destination is always an exact{' '}
          <Code>0</Code>.
        </p>
        <p>
          On Kafka the figure is exact but means something different, which is why it is labelled
          differently:
        </p>
        <Table head={['Provider', 'Label', 'What the number counts']}>
          {PROVIDERS.map((provider) => {
            const caveat = PROVIDER_CAVEATS[provider]
            return (
              <tr key={provider}>
                <td className="px-3 py-2">
                  <ProviderBadge provider={provider} label={PROVIDER_FIELDS[provider].label} />
                </td>
                <td className="px-3 py-2 font-medium">{caveat.depthLabel}</td>
                <td className="px-3 py-2 text-slate-600">
                  {provider === 'KAFKA'
                    ? 'Records retained on the topic — not a backlog. Records consumers have already read still count until retention removes them.'
                    : 'Messages currently sitting on the queue, waiting to be consumed.'}
                </td>
              </tr>
            )
          })}
        </Table>
        <p>
          Whenever the depth carries a caveat, the tool prints it underneath the number. Read it —
          that text is the difference between a number you can act on and one you cannot.
        </p>
      </Section>

      <Section id="send" title="6. Send a message">
        <p>
          The send panel sits beside the message list. Type a body, optionally add key/value pairs,
          and send. The response gives you the id the broker assigned, which is the same id the
          message will appear under in the list.
        </p>
        <p>
          The key/value rows are named for whatever the provider calls them, and Kafka adds one extra
          field that no JMS broker has:
        </p>
        <Table head={['Provider', 'Key/value rows are', 'Message key', 'Body type', 'Own header']}>
          {PROVIDERS.map((provider) => {
            const caveat = PROVIDER_CAVEATS[provider]
            return (
              <tr key={provider}>
                <td className="px-3 py-2">
                  <ProviderBadge provider={provider} label={PROVIDER_FIELDS[provider].label} />
                </td>
                <td className="px-3 py-2">{caveat.propertiesLabel}</td>
                <td className="px-3 py-2">{caveat.hasMessageKey ? <Yes /> : <No />}</td>
                <td className="px-3 py-2">
                  {caveat.hasMessageType ? 'Text or bytes' : 'Bytes — no choice to make'}
                </td>
                <td className="px-3 py-2">
                  {caveat.hasTargetClient ? 'MQRFH2 — JMS or MQ' : <No />}
                </td>
              </tr>
            )
          })}
        </Table>
        <p>
          On Kafka, a <strong>key</strong> decides which partition the record lands on: records
          sharing a key go to the same partition and stay in order relative to each other. Leave it
          blank and Kafka spreads records across partitions.
        </p>
        <p>
          On the JMS providers you also choose a <strong>body type</strong>. <strong>Text</strong> is
          the default and is what you want almost always. Choose <strong>Bytes</strong> when the
          reader is an AMQP 1.0 client that will not accept a string body: a JMS text message crosses
          to AMQP as a string, a bytes message as binary, and some clients — Python 2 Qpid among them
          — reject the first outright. Bytes sends the UTF-8 encoding of the body you typed, so it is
          your text as bytes rather than a way to send arbitrary binary. Kafka has no such choice: its
          record values are bytes already, and it refuses an explicit body type rather than accepting
          one it would not act on.
        </p>
        <p>
          On IBM MQ there is a second, separate choice: the <strong>header</strong>. IBM MQ classes for
          JMS put every message behind an <Code>MQRFH2</Code> header carrying the JMS metadata. A JMS
          reader consumes it and never sees it. An application doing a native <Code>MQGET</Code> does
          not, and gets it as the first bytes of its payload — which is why a body that looks perfectly
          well formed in the list above can make a downstream XML parser fail at line 1, column 0.
          Choose <strong>MQ (no header)</strong> and the queue holds the body and nothing else.
        </p>
        <Warn>
          Choosing <strong>Bytes</strong> does not remove the MQRFH2 header, and reaching for it first
          is the natural mistake: it only changes <Code>&lt;Msd&gt;jms_text&lt;/Msd&gt;</Code> to{' '}
          <Code>&lt;Msd&gt;jms_bytes&lt;/Msd&gt;</Code> inside the header that is still there. The two
          choices are independent and compose: with the MQ header, <strong>Text</strong> puts the
          message as <Code>MQSTR</Code>, which a reader can have converted to its own CCSID, and{' '}
          <strong>Bytes</strong> puts it as <Code>MQFMT_NONE</Code>, which is byte-exact and never
          converted.
        </Warn>
        <p>
          The header choice is per send rather than per connection, because one queue manager usually
          serves both kinds of reader. With the MQ header, custom properties are{' '}
          <strong>refused rather than dropped</strong>: they travel in the header's <Code>usr</Code>{' '}
          folder, and suppressing the header is exactly the instruction not to write one. The message
          id, persistence, priority, expiry and correlation id all survive — they live in the MQMD, not
          in the header.
        </p>
      </Section>

      <Section id="delete" title="7. Delete one message">
        <p>
          Where it is supported, each row has a <strong>Delete</strong> button that removes that one
          message. The answer you get back is precise, and worth reading rather than skimming:
        </p>
        <ul className="ml-5 list-disc space-y-1">
          <li>
            <strong>Deleted</strong> — the message is gone.
          </li>
          <li>
            <strong>Not on the queue</strong> — it is provably no longer there. It was already
            consumed, it expired, or the id was wrong.
          </li>
          <li>
            <strong>An error</strong> — the message could not be singled out. This is <em>not</em> the
            same as “already gone”, and the tool refuses to say it is. The message is probably still
            on the queue.
          </li>
        </ul>
        <p>Not every broker can do this at all:</p>
        <Table head={['Provider', 'Delete one message']}>
          {PROVIDERS.map((provider) => (
            <tr key={provider}>
              <td className="px-3 py-2">
                <ProviderBadge provider={provider} label={PROVIDER_FIELDS[provider].label} />
              </td>
              <td className="px-3 py-2">
                {PROVIDER_CAVEATS[provider].canDeleteOneMessage ? (
                  <>
                    <Yes />
                    {PROVIDER_CAVEATS[provider].deleteNote && (
                      <span className="mt-1 block text-xs text-slate-500">
                        {PROVIDER_CAVEATS[provider].deleteNote}
                      </span>
                    )}
                  </>
                ) : (
                  <>
                    <span className="font-medium text-slate-800">not possible</span>
                    <span className="mt-1 block text-xs text-slate-500">
                      A Kafka partition is an append-only log, so no single record can be removed from
                      it. The button is not shown at all rather than shown and always failing. Purge
                      is the only way to remove records.
                    </span>
                  </>
                )}
              </td>
            </tr>
          ))}
        </Table>
      </Section>

      <Section id="purge" title="8. Purge everything">
        <p>
          <strong>Purge</strong> removes every message from the destination. It asks for confirmation
          first, and it cannot be undone.
        </p>
        <p>
          A purge is capped, so on a very deep queue it may stop before it finishes. When that
          happens the result says so and reports how many it removed — it will <em>never</em> tell you
          the destination is empty when a cap stopped it early. Run it again to continue.
        </p>
        <Warn>
          On Kafka a purge is not a drain: it moves each partition's start offset to the end, which
          discards everything currently retained. Records produced <em>after</em> the purge began are
          unaffected, and it needs the <Code>Delete</Code> permission on the topic.
        </Warn>
      </Section>

      <Section id="logs" title="Watching what it is doing">
        <p>
          The{' '}
          <Link to="/logs" className="text-brand-700 hover:underline">
            Logs
          </Link>{' '}
          page shows this application's own recent log lines as they happen — useful when an operation
          fails and the error alone does not explain why. Filter by severity, search the text, narrow
          it to a time range, and expand any line that carried an exception to read its stack trace.
        </p>
        <p>
          The <strong>From</strong> and <strong>To</strong> fields are in your own local time, and both
          ends are included. The presets above them cover the common cases. Setting an end time turns{' '}
          <strong>Live</strong> off, because no new line can arrive inside a range that has already
          closed. Click the <strong>Time</strong> heading to read a range in the order things happened
          instead of newest-first.
        </p>
        <p>
          <strong>A page always shows the most recent matching lines.</strong> Sorting oldest-first
          changes the order they are shown in, not which ones you get — and when older lines inside the
          range were left out to fit, the footer says so rather than letting the first row look like the
          beginning of the story.
        </p>
        <p>
          It is a live view, not an audit trail: the lines are held in memory, bounded, and gone on
          restart. Everything is also written to standard output, which is where anything you need to
          keep should be collected from.
        </p>
        <Warn>
          That page has no login either, and it is one log level away from showing message bodies:
          payload logging ships enabled, so running this application at DEBUG puts everything you send
          onto it. Set <Code>MQMANAGER_LOG_PAYLOADS=false</Code> to keep bodies out whatever the level.
        </Warn>
      </Section>

      <Section id="differences" title="Provider differences at a glance">
        <p>
          These are real differences between the brokers, not quirks of this tool. Each screen
          surfaces the one that applies to it; this is the whole set in one place.
        </p>
        <Table head={['Provider', 'Destination', 'Depth means', 'Delete one', 'Creates on first use']}>
          {PROVIDERS.map((provider) => {
            const caveat = PROVIDER_CAVEATS[provider]
            const createsOnUse = provider === 'ACTIVE_MQ' || provider === 'ARTEMIS'
            return (
              <tr key={provider}>
                <td className="px-3 py-2">
                  <ProviderBadge provider={provider} label={PROVIDER_FIELDS[provider].label} />
                </td>
                <td className="px-3 py-2">{caveat.Noun}</td>
                <td className="px-3 py-2">{caveat.depthLabel.toLowerCase()}</td>
                <td className="px-3 py-2">{caveat.canDeleteOneMessage ? <Yes /> : <No />}</td>
                <td className="px-3 py-2">
                  {createsOnUse ? <Yes /> : provider === 'KAFKA' ? 'depends on the cluster' : <No />}
                </td>
              </tr>
            )
          })}
        </Table>
      </Section>

      <Section id="errors" title="When something goes wrong">
        <p>
          Every failure comes back with a short explanation and a stable code shown underneath it. The
          code is the thing to search for or quote in a bug report — the wording may change, the code
          will not.
        </p>
        <Table head={['Code', 'What it means', 'What to do']}>
          {[
            [
              'BROKER_UNREACHABLE',
              'The broker did not answer in time.',
              'Check the host and port, and that the listener does not require TLS.',
            ],
            [
              'BROKER_CONNECTION_REFUSED',
              'Nothing is listening on that address.',
              'Usually a wrong port, or the broker is not running.',
            ],
            [
              'BROKER_AUTH_FAILED',
              'The username or password was rejected.',
              'Re-enter the credentials on the connection.',
            ],
            [
              'BROKER_NOT_AUTHORIZED',
              'You are connected, but not permitted to do this.',
              'Ask for the missing permission — a purge needs more than a read.',
            ],
            [
              'QUEUE_NOT_FOUND',
              'The queue or topic does not exist.',
              'Check the spelling. Not every broker creates one on first use.',
            ],
            [
              'MESSAGE_UNREACHABLE',
              'The message is still there but cannot be singled out.',
              'Try again when the queue is shallower, or purge instead.',
            ],
            [
              'OPERATION_NOT_SUPPORTED',
              'This broker genuinely cannot do that.',
              'Not a fault to retry — see the provider differences above.',
            ],
            [
              'MQ_CREDENTIALS_UNREADABLE',
              'The stored password can no longer be decrypted.',
              'The encryption key changed or was lost. Edit the connection and re-enter it.',
            ],
          ].map(([code, meaning, action]) => (
            <tr key={code}>
              <td className="whitespace-nowrap px-3 py-2 font-mono text-xs text-slate-800">{code}</td>
              <td className="px-3 py-2">{meaning}</td>
              <td className="px-3 py-2 text-slate-600">{action}</td>
            </tr>
          ))}
        </Table>
      </Section>

      <Section id="scope" title="What this tool will not do">
        <p>
          Knowing the edges saves time looking for a button that is not there. This version has no
          login and no user accounts; it will not create or delete queue and topic definitions; the
          browse list carries no depth, so a destination has to be opened to see how much is on it; and
          it does not browse dead-letter queues, edit or replay messages, or connect over TLS.
        </p>
        <p>
          A browse is a point-in-time snapshot, not a live view — use <strong>Refresh</strong> to take
          another one.
        </p>
      </Section>
    </div>
  )
}

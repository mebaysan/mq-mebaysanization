# MQ Manager

One web UI for operating message queues on **Apache ActiveMQ Classic**, **Apache ActiveMQ Artemis**,
**IBM MQ** and **Apache Kafka**. Pick a provider, save the connection details, then send messages,
browse a queue, inspect or delete a single message, purge, and read the depth — the same screens and the
same REST API whichever broker is behind them.

Three of the four speak JMS. Kafka does not, and this tool does not pretend it does: where its model
genuinely differs — a topic instead of a queue, a depth that counts retained records rather than a
backlog, a log that cannot have one record removed from it — the UI and the API say so rather than
papering over it. See [Kafka is not a queue](#kafka-is-not-a-queue).

Everything ships as **one executable JAR**. The compiled React UI is embedded inside it, so the only
thing a machine needs to run this is a **Java 21 JRE**: no Node, no database server, no native IBM MQ
client installation.

```bash
mvn clean package
java -jar target/mq-manager.jar
# then open http://localhost:8080
```

---

## ⚠️ This version has no authentication

There is no login. **Anyone who can reach the port can read, send, delete and purge messages on every
broker you have configured, using the credentials you stored.** Run it on localhost, or put it behind an
authenticating reverse proxy. Do not expose it to a network you do not control.

This is also why the H2 console is disabled and why the database opens no network listener.

---

## Requirements

**To run:** a Java 21 or newer JRE. Nothing else.

**To build:** JDK 21+, Maven 3.9+, and network access to Maven Central, `nodejs.org` and
`registry.npmjs.org`. Maven downloads its own Node (v24.19.0) into `frontend/node/` — you do not need
Node installed, and nothing Node-related ends up in the JAR.

### Client versions

ActiveMQ Classic and Artemis are pinned in `pom.xml` for reasons documented there. `kafka-clients` is
deliberately **not** pinned: it stays on Spring Boot 3.5.16's managed `kafka.version`, **3.9.2**.
4.3.1 is available, but nothing here needs it — `deleteRecords`, record headers and the
`Duration`-taking overloads all long predate 3.9 — and Kafka 4 clients drop support for brokers older
than 2.1, which would narrow what this tool can connect to for no gain. Overriding a Boot-managed
version also means testing a combination Boot itself does not.

`spring-kafka` is not used at all. Its `KafkaTemplate` and listener containers solve the long-lived
consumer problem this tool does not have — it opens a client per operation and closes it again — and
`spring-kafka-test` would break the portability gate (see [Testing](#testing)).

---

## Configuration

All settings are environment variables with sensible defaults.

| Variable | Default | Purpose |
|---|---|---|
| `MQMANAGER_PORT` | `8080` | HTTP port for both the UI and the API |
| `MQMANAGER_DATA_DIR` | `./data` | Holds the H2 database and the encryption key |
| `MQMANAGER_ENCRYPTION_KEY` | *(generated)* | Base64-encoded 32-byte AES key. When unset, one is generated into the data directory on first run |
| `MQMANAGER_LOG_PAYLOADS` | `false` | Opt-in DEBUG logging of message bodies. Off by default, and never logged at INFO |

The data directory ends up holding:

```
data/mqmanager.mv.db    the H2 database with your saved connection profiles
data/encryption.key     the AES key protecting stored broker passwords (mode 0600 where supported)
```

### Encryption and key management

Broker passwords are encrypted with AES-256-GCM before being written to the database, using a fresh
random IV per value. A password is **never** returned by any endpoint — the response type has no field
for one at all — and never appears in a log line.

**Back up `data/encryption.key`.** Without it the stored passwords cannot be decrypted. If the key is
lost or rotated, the application still starts and still lists every connection; only the operations that
actually need a password fail, and the affected connections are flagged in the UI so you can re-enter
them. Supply your own key with `MQMANAGER_ENCRYPTION_KEY` if you would rather manage it yourself.

On Windows the key file falls back to `File.setReadable`/`setWritable`, which is weaker than POSIX
`0600`; its real protection there comes from the directory's inherited ACLs. A warning is logged if even
that fails.

---

## Connecting to each provider

| Field | ActiveMQ Classic | Artemis | IBM MQ | Kafka |
|---|---|---|---|---|
| Host / port | yes (default 61616) | yes (default 61616) | yes (default 1414) | **not supported** |
| Bootstrap servers | — | — | — | **required** (e.g. `broker1:9092,broker2:9092`) |
| Username / password | optional | optional | optional | optional (SASL/PLAIN) |
| Broker URL override | optional | optional | **not supported** | **not supported** |
| Queue manager | — | — | **required** | — |
| Channel | — | — | **required** (e.g. `DEV.APP.SVRCONN`) | — |

The three addressing styles are mutually exclusive, and a field that does not apply to the chosen
provider is **rejected** rather than ignored — a saved row that carried both a host and a bootstrap
list would leave no way to tell which was meant to be authoritative. The form hides the fields that do
not apply, so this only bites someone calling the API directly.

**Broker URL override** is used verbatim in place of `tcp://host:port`, with nothing appended. That is
the point of it: it is how you express something the plain fields cannot, such as
`failover://(tcp://a:61616,tcp://b:61616)?maxReconnectAttempts=1`.

IBM MQ connects in **client mode over TCP** only. Bindings mode is never used and is not exposed —
it requires a native MQ server installation and would break the "just a JRE" promise.

**Kafka** addresses a cluster, not one endpoint, so it takes a comma-separated list of seed brokers
instead of a host and a port. One is enough — Kafka discovers the rest from it — but listing several
means a single broker being down does not stop you connecting.

TLS to brokers is **not supported in v1**; connections are plaintext TCP. For Kafka that means a
username and password are sent as **`SASL_PLAINTEXT` with the `PLAIN` mechanism**: they cross the wire
unencrypted, and the connection form says so. Leave both blank for an unauthenticated cluster, which
selects plain `PLAINTEXT`. `SASL_SSL`, SCRAM, OAUTHBEARER and mTLS are out of scope in v1.

---

## Provider behaviour differences

These are real differences between the brokers, not quirks of this tool. The UI surfaces each one rather
than pretending the three are identical.

### Browsing is not always complete

| Provider | What a browse sees |
|---|---|
| ActiveMQ Classic | Capped at the destination's `maxBrowsePageSize` (**400** by default). The enumeration simply ends — no error, no flag. The queue may hold more than is listed |
| Artemis | The whole queue, including paged messages. Complete as of the moment of the scan |
| IBM MQ | Everything committed. **Uncommitted messages are never visible**, and on a priority-ordered queue anything higher-priority arriving mid-scan will not appear |
| Kafka | Every record still inside the **retention window**, read from the start of every partition. Nothing is consumed and no offset is committed, so the same records are there next time |

Because of this the UI always says "showing the first N", never a total, and a depth that cannot be
promised is rendered as **`N+`** rather than `N`. An empty queue is always reported as an exact `0`.

Depth is a browse-and-count: `jakarta.jms` has no depth API and none of the three providers adds one.
It is capped at 10,000. Provider-native depth (JMX for Classic, the management API for Artemis, PCF for
IBM MQ) would be the way to lift that, and is out of scope here.

### Deleting one message

Deleting by ID opens a consumer with the selector `JMSMessageID='ID:…'`. The `ID:` prefix is always
included — for all three brokers.

| Provider | Reach |
|---|---|
| IBM MQ | Any depth. The selector becomes an `MQGET` with `MQMO_MATCH_MSG_ID`, a native queue-manager match |
| Artemis | Any depth. The selector is evaluated across the paging cursor |
| ActiveMQ Classic | Usually fine, but on a deep queue under memory pressure the broker will not hand a specific message to a selector |
| Kafka | **Impossible.** A partition is an append-only log; there is no operation that removes one record and leaves its neighbours. The per-row Delete button is not rendered at all, and the API answers **501 `OPERATION_NOT_SUPPORTED`** |

When a message cannot be delivered to the selector, the tool does **not** report "already deleted". It
re-checks with a non-destructive browse and answers precisely:

- found on the queue → **409 `MESSAGE_UNREACHABLE`** — it is still there, it just cannot be singled out
- provably absent → **200 `{"deleted": false}`** — already consumed, expired, or a wrong ID
- the re-check itself was truncated → **409 `MESSAGE_NOT_LOCATABLE`** — we refuse to guess

An all-zeros message ID (`ID:000…0`) is rejected outright. On IBM MQ that value is a documented
**wildcard that matches any message on the queue**, so accepting it would delete something arbitrary.

### Creating queues

ActiveMQ Classic and Artemis create a queue the first time you use one, so a typo shows up as a new
empty queue rather than an error. Artemis then deletes it again once it is idle and empty, which can look
like the queue vanished. IBM MQ never creates queues on demand — an unknown name reports MQRC 2085.

Kafka is the only "it depends": a broker with `auto.create.topics.enable=true` creates the topic, and
one without it does not. Most production clusters turn it off. Rather than guess, the UI says a topic
**may** not exist and the error names the setting.

### Purging

Purge consumes messages in a loop with `AUTO_ACKNOWLEDGE`, capped at **50,000 messages or 30 seconds**.
The response always says why it stopped, so a purge halted by a cap is never reported as "the queue is
now empty".

Kafka does not consume anything: purge calls `AdminClient.deleteRecords()` to move each partition's
**log start offset** to its end offset. There is no message or time cap because there is no loop. The
count reported is the real one — the sum of how far each partition's low watermark actually moved,
taken from what the broker reports back, not the offset that was requested. If some partitions are
truncated and others are refused (a partial `Delete` ACL), the result is **409 `PURGE_PARTIAL`** stating
how many records were removed and how many partitions failed, never a clean success.

### Kafka is not a queue

Everything above is a difference between brokers that all speak the same API. Kafka does not speak that
API at all, and the differences are bigger. `KafkaMessagingOperations` drives a producer, a consumer and
an admin client directly; there is no JMS bridge, because a bridge would have to invent answers for the
things below.

| Operation | On a JMS queue | On Kafka |
|---|---|---|
| Destination | A queue | A **topic** with N partitions. The UI says "topic" and the same `queueName` query parameter carries it |
| Message id | `JMSMessageID`, assigned by the broker | Records have **no id**. Identity is `(topic, partition, offset)`, so the id is the synthetic **`topic-partition-offset`** — e.g. `orders-3-4711`. Partition, offset, key and timestamp also appear under Headers |
| Browse | Opens a `QueueBrowser` | Assigns every partition, seeks to the beginning, polls. **Never commits an offset, and the consumer has no `group.id` at all** — so a browse cannot create, join or move a consumer group |
| Depth | Browse-and-count, capped at 10,000, sometimes `N+` | `Σ(end offset − start offset)` across partitions. **Exact**, and never `N+` — but it counts records **retained**, not records unconsumed. Records a consumer group has already read still count until retention removes them. The UI labels it "Retained", not "Depth" |
| Purge | Consumes in a loop | `deleteRecords()` truncates each partition to its end offset. Needs the **`Delete`** ACL on the topic. Records produced after the truncation point survive, and the disk space comes back asynchronously |
| Delete one | A selector on `JMSMessageID` | **Impossible** — 501 `OPERATION_NOT_SUPPORTED`. The button is not rendered |
| Send | `TextMessage` + string properties | `ProducerRecord` with an optional **key** (it chooses the partition) and headers. Properties become record headers, UTF-8 encoded, and come back as properties. Returns `topic-partition-offset` |
| Body | Text, bytes and map messages | Bytes, decoded as UTF-8. A **null value is a tombstone** and is labelled as one rather than shown as an empty body — on a compacted topic it marks the key for deletion |

Two more things worth knowing:

- **Compacted topics.** Depth counts only the surviving version of each key, and `deleteRecords()` is
  refused by the broker — reported as 409 with the reason named.
- **Timeouts.** Kafka's own defaults run to minutes; `max.block.ms` alone is 60 seconds, and it is what
  a producer waits on for a topic that does not exist. Every call here is bounded at ~5 seconds
  (`mqmanager.kafka.*`), which is why a dead broker reports an error instead of freezing the page.

### Message bodies

`ObjectMessage` bodies are **deliberately not deserialized** — only the headers and properties are
shown. Deserializing an arbitrary payload merely to display it is a remote-code-execution risk, and this
tool has no authentication in front of it. Text, bytes and map messages render normally.

---

## Development

```bash
# Terminal 1 — backend on :8080 (does not build the frontend)
mvn spring-boot:run

# Terminal 2 — Vite dev server on :5173, proxying /api to :8080
cd frontend && npm run dev
```

Local Node should be **24.x**. React Router 8 declares `engines.node >= 22.22.0`; npm only warns, so an
older Node will probably work, but it is not what the build uses.

`mvn package -Pskip-frontend` skips the frontend build entirely for fast backend-only iteration.

---

## Testing

```bash
mvn test
```

Runs with **no Docker, no brokers running, and no network** — the frontend build is bound to
`prepare-package`, which comes after `test`, so a plain `mvn test` never touches Node.

- **ActiveMQ Classic and Artemis** are covered by real end-to-end integration tests against embedded
  in-process brokers, including send, browse, depth, delete, purge and error translation.
- **IBM MQ** is mocks-only in v1 (see the checklist below). Its connection factory is verified by reading
  every property back off the built factory, which catches a wrong constant name at build time — the
  failure mode that matters most given there is no queue manager to test against.
- **Kafka** is mocks-only for the same reason, using Kafka's own `MockProducer` and `MockConsumer` (both
  ship inside `kafka-clients`) plus a Mockito `Admin`. Send, browse, browse-one, depth, purge, delete-one
  and error translation are all covered, including that a browse **never calls commit**, that purge
  reports the real low-watermark delta rather than the offset it asked for, and that delete-one can never
  return a success. `KafkaClientConfigTest` reads every setting back off the built configuration, the
  same technique as the IBM MQ test and for the same reason: Kafka *silently ignores* a config key it
  does not recognise, so a typo'd constant leaves the default in place and no mock would notice.

  `spring-kafka-test`'s `EmbeddedKafkaKraftBroker` was considered and rejected. It declares ZooKeeper
  3.8.6 at compile scope, which depends on `netty-transport-native-epoll` **with a platform classifier**
  — and Gate 2a below scans the whole dependency tree regardless of scope, so it would fail. It also
  drags Scala, `kafka-streams` → `rocksdbjni`, log4j-core and JUnit 4 onto the test classpath for one
  test. See the Kafka checklist below for what is covered manually instead.
- `ImportGuardTest` fails the build on three mistakes that otherwise compile cleanly: importing either
  `ActiveMQConnectionFactory` (Classic and Artemis ship classes with the same simple name), importing
  `react-router-dom` (removed in React Router 8), and letting `org.apache.kafka` leak outside the
  `kafka` package or `jakarta.jms` leak into it.

### Portability gate

```bash
./scripts/verify-portability.sh
```

Proves the two things the "runs anywhere with a JRE" promise depends on: that `jakarta.jms-api` resolves
to 3.1.0 and never 2.0.3 (which would mean the wrong, `javax`-namespace Artemis artifact had crept in),
and that **no jar bundled in the fat JAR needs a native binary from the host**.

#### The Kafka compression codecs

`kafka-clients` pulls in `zstd-jni`, `snappy-java` and `lz4-java` at runtime scope, non-optional, and
between them they carry **48 native files**. Gate 2b used to ban native binaries outright, which is a
stricter rule than the promise needs, so it now checks the promise itself.

These three are kept, for a reason: they ship a native for **every** platform — Linux, macOS, Windows,
FreeBSD, on x86_64 and aarch64 — and extract the right one to a temp directory at load time. Nothing has
to be installed on the machine running the JAR. That is materially different from the artifacts this
gate was written to catch: `activemq-artemis-native` (Linux only) and Netty's epoll/kqueue transports
(linux-x86_64 / osx-x86_64 only), both excluded in `pom.xml` precisely because they are single-platform.

So Gate 2b allowlists those three **by name** and then positively verifies that each one really does
cover Linux, macOS and Windows. A single-platform library slipping onto the allowlist would break the
promise silently, and that check is the entire point of the exception. (It earns its keep: `lz4-java`
names its Windows library `win32/amd64/liblz4-java.so` — a DLL with a `.so` extension — so the check
matches on the path, not the file extension.)

Excluding them was the alternative, and was rejected: the app would then throw `NoClassDefFoundError`
on any topic whose batches use snappy, zstd or lz4, which is the norm in production Kafka. Only gzip
(JDK-native) and uncompressed would work. The cost of keeping them is about **9.5 MB** of JAR.

### A local broker for manual testing

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=target/test-cp.txt -Dmdep.includeScope=test
java -cp "target/classes:target/test-classes:$(cat target/test-cp.txt)" \
     com.baysansoft.mqmanager.support.LocalBrokerLauncher
```

Starts a throwaway in-memory ActiveMQ Classic broker on `tcp://127.0.0.1:61616`.

### IBM MQ manual-test checklist

There is no automated IBM MQ integration test in v1, so this is the checklist. Run a developer queue
manager:

```bash
docker run -d -e LICENSE=accept -e MQ_QMGR_NAME=QM1 -e MQ_APP_PASSWORD=passw0rd \
  -p 1414:1414 icr.io/ibm-messaging/mq:latest
```

Create a connection with host `localhost`, port `1414`, queue manager `QM1`, channel `DEV.APP.SVRCONN`,
user `app`, password `passw0rd`, and use queue `DEV.QUEUE.1`.

1. Test connection succeeds, and `DISPLAY CONN(*) APPLTAG` on the queue manager shows **`MQ Manager`**.
2. Browsing an empty queue returns zero messages and no error.
3. Put 500 messages; the browse shows bodies, IDs and timestamps, and the depth matches
   `DISPLAY QLOCAL(DEV.QUEUE.1) CURDEPTH`.
4. **Delete message #400 by its ID — this must succeed.** It proves the selector reaches past any
   window. `CURDEPTH` drops to 499.
5. Delete that same ID again → `{"deleted": false}` with HTTP 200.
6. **Most important:** `messageId=ID:000000000000000000000000000000000000000000000000` is rejected with
   `MESSAGE_ID_INVALID` and **`CURDEPTH` is unchanged**.
7. Purge 50,000 non-persistent messages on a queue with `DEFREADA(YES)`; the reported count must equal
   the `CURDEPTH` delta exactly. This proves read-ahead is being disabled — without it IBM MQ discards
   buffered messages on consumer close and the count silently under-reports.
8. Wrong password → `BROKER_AUTH_FAILED` (2035). Wrong port → 2538. Wrong channel → 2540. Wrong queue
   manager → 2058. Nonexistent queue → `QUEUE_NOT_FOUND` (2085).
9. Revoke `+browse` only → MQRC 2035, which `JmsErrorTranslator` maps to `BROKER_AUTH_FAILED` (401).
   Note that IBM MQ reports "wrong password" and "authenticated but not permitted" with the *same*
   reason code, so this tool cannot tell them apart the way it can on Kafka, where they are 401 and 403
   respectively. If that distinction matters to you, the queue manager's own AMQERR logs have it.

### Kafka manual-test checklist

Kafka has no automated integration test in v1 either, so this is its checklist. Run a throwaway broker:

```bash
docker run -d --name kafka -p 9092:9092 apache/kafka:3.9.1
```

Create a connection with bootstrap servers `localhost:9092`, no username, and use topic
`mq-manager-demo` (create it first with `kafka-topics.sh --create --partitions 3` unless the broker
auto-creates).

1. Test connection succeeds and reports how many topics are visible.
2. Send a message with a key and two headers. The returned id is `mq-manager-demo-<partition>-<offset>`.
3. Browse: the body is intact, both headers appear under **Properties**, and `KafkaKey`,
   `KafkaPartition`, `KafkaOffset` and `KafkaTimestampType=CreateTime` appear under **Headers**.
4. **Browse twice, then run `kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list`. It must
   be empty.** This is the whole "browsing is non-destructive" claim, and it is the one thing to check if
   you check nothing else.
5. Depth equals `kafka-run-class.sh kafka.tools.GetOffsetShell --time -1` minus `--time -2`, summed over
   partitions. Send 1,000 more and it goes up by exactly 1,000 — rendered with **no `+`**.
6. **The per-row Delete button is absent.** Call it directly anyway:
   `curl -i -X DELETE 'localhost:8080/api/connections/1/queue/messages?queueName=mq-manager-demo&messageId=mq-manager-demo-0-1'`
   → **501** with `"code":"OPERATION_NOT_SUPPORTED"`, and the depth is unchanged.
7. Purge. The reported count equals the previous depth exactly, depth then reads 0, and
   `kafka-console-consumer.sh --from-beginning` returns nothing.
8. Purge a topic created with `--config cleanup.policy=compact` → **409**, naming compaction, topic
   untouched.
9. A topic name that does not exist, on a broker with `auto.create.topics.enable=false` → **404
   `QUEUE_NOT_FOUND`**, and the message names the setting.
10. Restart the broker with a `SASL_PLAINTEXT` listener. Correct credentials → success. Wrong password →
    **401 `BROKER_AUTH_FAILED`**. Correct password but no `Describe` on the topic → **403
    `BROKER_NOT_AUTHORIZED`**. These are three different fixes, so they must not collapse into one code.
11. **Point it at a black-holed address (`10.255.255.1:9092`). It must fail in roughly 5–10 seconds, not
    60.** This is the only check that proves the timeouts are actually wired; without them
    `max.block.ms` alone would hold the request for a minute.
12. Send to a topic whose records are snappy- or zstd-compressed and browse them back. This exercises the
    bundled native codecs on your platform — see the portability note above.

### A manual check worth doing for the Apache brokers too

The embedded test brokers run with security disabled, so they cannot catch a credentials-plumbing bug.
Do one manual pass against an ActiveMQ or Artemis broker with authentication **on**.

---

## API

Queue names are always **query parameters**, never path segments — `DEV.QUEUE.1` is an ordinary MQ queue
name and a dotted path segment would be treated as a static file request.

| Method | Path |
|---|---|
| `GET` | `/api/connections` |
| `POST` | `/api/connections` |
| `GET` | `/api/connections/{id}` |
| `PUT` | `/api/connections/{id}` |
| `DELETE` | `/api/connections/{id}` |
| `POST` | `/api/connections/{id}/test` |
| `POST` | `/api/connections/test` *(unsaved draft)* |
| `GET` | `/api/connections/{id}/queue/messages?queueName=&limit=` |
| `GET` | `/api/connections/{id}/queue/messages/one?queueName=&messageId=` |
| `POST` | `/api/connections/{id}/queue/messages?queueName=` |
| `DELETE` | `/api/connections/{id}/queue/messages?queueName=&messageId=` |
| `DELETE` | `/api/connections/{id}/queue/purge?queueName=` |
| `GET` | `/api/connections/{id}/queue/depth?queueName=` |

On `PUT`, omitting `password` keeps the stored one and sending `""` clears it — the current value is
never sent to the client, so an unchanged edit form has nothing to resubmit.

`POST .../messages` takes `{"payload", "properties", "key"}`. `key` is **Kafka only** — it selects the
partition — and the JMS providers reject a non-null one rather than dropping it silently.

Errors always come back as `{"status", "error", "message"}`, plus a stable machine-readable `code`
(`BROKER_AUTH_FAILED`, `QUEUE_NOT_FOUND`, `MESSAGE_UNREACHABLE`, …). Stack traces are never returned.

Kafka adds a few codes of its own, all in the same shape:

| Code | Status | Meaning |
|---|---|---|
| `OPERATION_NOT_SUPPORTED` | 501 | Deleting one record. The log is immutable — this can never work, so it is refused rather than attempted |
| `OPERATION_NOT_SUPPORTED` | 409 | Purging a compacted topic. The broker refuses `deleteRecords` on it |
| `PURGE_PARTIAL` | 409 | Some partitions truncated, others refused. The message says how many records went and how many partitions failed |
| `BROKER_NOT_AUTHORIZED` | 403 | Connected and authenticated, but the ACL for this operation is missing. Distinct from `BROKER_AUTH_FAILED` (401), which is a bad password |
| `QUEUE_NAME_INVALID` | 400 | Kafka rejected the topic name itself |
| `COMPRESSION_CODEC_UNAVAILABLE` | 502 | A compressed batch whose native codec could not load on this platform |

---

## Licensing note

The IBM MQ client is distributed under the **IBM International Program License Agreement**, not an
open-source licence. Redistributing this fat JAR outside your own organisation carries obligations that
the Apache-licensed ActiveMQ and Artemis clients do not. The IBM MQ stack is also about 19 MB of the
JAR's size.

---

## Not in this version

Login/authentication · TLS to brokers · discovering queue names from the broker (JMX/PCF) · creating or
deleting queue definitions · connection pooling · dead-letter queue browsing · message replay or editing
(JMS messages are immutable once enqueued; editing would have to be delete-then-resend) · a Docker image
· multi-user access control or an audit trail.

Kafka specifically: `SASL_SSL`, SCRAM, OAUTHBEARER and mTLS · consumer-group inspection, lag or offset
reset · choosing a partition explicitly when sending · reading from a given offset or from the tail
rather than the beginning · Schema Registry, Avro or Protobuf decoding · creating, configuring or
deleting topics.

## Assumptions

- One instance per data directory. H2 opens its file exclusively, so a second instance pointed at the
  same `./data` fails at startup with *"Database may be already in use"*. Point it elsewhere with
  `MQMANAGER_DATA_DIR`.
- Queue names are typed, not discovered.
- Messages are treated as text.
- A browse is a point-in-time snapshot, not a live view.

## Troubleshooting

**"Database may be already in use"** — another MQ Manager is running against the same data directory.

**"The stored password cannot be decrypted"** — `encryption.key` was lost, replaced, or
`MQMANAGER_ENCRYPTION_KEY` changed. Edit the affected connection and re-enter the password.

**`npm ci` fails during the build** — `frontend/package-lock.json` must be committed and in step with
`package.json`.

**Build fails with `TypeTag :: UNKNOWN`** — an old `maven-compiler-plugin` on a JDK 25 toolchain. This
project pins 3.15.0 for exactly that reason; if you see it, something has overridden the pin.

**Artemis logs "KQueue is not available"** — expected and harmless. The platform-specific Netty
transports are excluded on purpose so the JAR stays portable; Artemis falls back to NIO.

**A Kafka topic reads as empty when you know it is not** — either every record has aged out of the
retention window, or the cluster does not create topics on demand and the name is wrong. Both look the
same from outside; `kafka-topics.sh --list` tells them apart.

**Kafka reports `BROKER_UNREACHABLE` against a listener that works elsewhere** — most likely the
listener requires TLS. This build is plaintext only.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

One Spring Boot application that operates message queues on **ActiveMQ Classic, ActiveMQ Artemis, IBM MQ
and Apache Kafka**. The React UI is compiled into the same JAR, so the only runtime dependency is a Java
21 JRE — no Node, no database server, no native IBM MQ client. `README.md` is the user-facing manual and
is unusually detailed; read the relevant section before changing behaviour it documents.

**There is no authentication anywhere in this build.** That is a stated constraint, not an oversight, and
several decisions follow from it (no H2 console, no JMX, `ObjectMessage` is never deserialized).

## Commands

```bash
mvn clean package                  # tests, then npm build, then the fat JAR
mvn test                           # backend tests only; needs no broker, no Docker, no network
mvn -Pskip-frontend package        # skip the frontend entirely — use this for backend iteration
./scripts/verify-portability.sh    # the project's own gate; run after any dependency change
java -jar target/mq-mebaysanization-*.jar
```

A single test, or one method:

```bash
mvn -Pskip-frontend test -Dtest=KafkaBrowseTest
mvn -Pskip-frontend test -Dtest=LogBufferTest#oldestFirstStillReturnsTheNewestPage
```

Two-terminal development:

```bash
mvn spring-boot:run                # :8080, does NOT build the frontend
cd frontend && npm run dev         # :5173, proxies /api to :8080
cd frontend && npx tsc --noEmit    # the only automated gate the UI has — see "Frontend" below
```

A throwaway broker to drive the UI by hand (advisories on, so **Browse…** works):

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=target/test-cp.txt -Dmdep.includeScope=test
java -cp "target/classes:target/test-classes:$(cat target/test-cp.txt)" \
     com.baysansoft.mqmanager.support.LocalBrokerLauncher
```

## The rule that shapes everything: results must not lie

The brokers genuinely differ, and this codebase refuses to paper over it. A result never reads like
success when it is not, and a number is never presented as exact when it is a floor. That is why
`BrowseResult.truncated`, `DepthOutcome.exact`, `PurgeOutcome.stopReason` and
`DestinationListing.availability` exist. When adding an operation, decide what it *cannot* know and put
that in the return type — do not simplify it away.

The sharpest instance is the **unreachable-vs-declined split** in destination listing:

- The broker could not be reached → **throw**; the error translators turn it into the standard
  `ApiError` (502/401/…).
- The broker answered but would not answer *this* (advisories off, ACL denies, command server stopped) →
  **200** with `availability=UNAVAILABLE` and a reason code. Broker policy is an answer, not a fault.

Consequently an empty destination list means "genuinely nothing" **only** when `availability == COMPLETE`.
Clients branch on availability first. The same doctrine already governs `ConnectionTestResult`: a failed
connection test is a successful HTTP call.

## Architecture

### Two provider seams, both fail-fast

Everything above them is written once and never branches on `Provider`:

| Seam | Interface | Registry | Implementations |
|---|---|---|---|
| Opening a connection | `jms/ConnectionFactoryBuilder` | `ConnectionFactoryRegistry` | `jms/provider/*ConnectionFactoryBuilder` |
| Listing destinations | `jms/DestinationLister` | `DestinationListerRegistry` | `jms/provider/*DestinationLister` |

Both registries **refuse to start** if a JMS provider has no implementation, so a provider added to the
enum but never wired fails at boot rather than at click time. `MessagingOperationsRouter` (`@Primary`)
does the same for `MessagingOperations` across all four providers. Controllers inject the interfaces only.

**Kafka is not JMS and bypasses both.** It has no `jakarta.jms` API, no connection factory and no
lister; `KafkaMessagingOperations` implements `MessagingOperations` directly over `kafka-clients` and
gets its clients from `KafkaClientFactory` (which returns *interfaces*, so tests hand back `MockConsumer`
/ `MockProducer` / a Mockito `Admin`).

`Provider` carries an `EnumSet<Capability>` with named accessors rather than positional booleans. Add a
capability there rather than branching on the enum constant.

`TargetClient` (`messaging/model`) is the pattern for a provider-specific send option: it controls the
IBM MQ MQRFH2 header (JMS vs MQ target), is threaded through `OutboundMessage` and applied in
`IbmMqConnectionFactoryBuilder`, and is **rejected for Kafka** in `KafkaMessagingOperations` rather than
silently ignored — a per-provider difference surfaced in the type system, not papered over. There is a
matching `SUPPORTS_TARGET_CLIENT` capability on `Provider` (in `domain/`) — check the capability, don't
name the constant.

**A `default` method on `MessagingOperations` is how an operation only some providers can honour is
added — and it either refuses or degrades honestly, never lies.** Two examples both added after the seams:

- `createTopic(CreateTopicCommand) → CreateTopicOutcome` is a `default` that **throws**
  `OPERATION_NOT_SUPPORTED` (501). Only Kafka overrides it; the JMS brokers create a queue on first send
  (ActiveMQ, Artemis) or not at all (IBM MQ), and neither is an explicit create — so a caller gets an
  honest refusal, not a create that quietly did nothing. `CreateTopicOutcome` echoes what the broker
  *actually* accepted (partitions, replication factor, a `note`), stating the result rather than the
  request.
- `browse(..., MessageQuery)` is a `default` that **degrades**: it ignores the query and returns a plain
  first-N browse, so a provider that cannot scan server-side never claims to have searched. Only Kafka
  overrides it to seek by timestamp (`sinceEpochMs`) and scan for a substring (`contains`,
  `caseSensitive`), reporting via `BrowseResult.truncated` how far it got. `MessageQuery.NONE` is the
  no-search sentinel; `isSearch()` distinguishes a real query from a plain browse.

### Constraints that will bite you

- **Queue names are always query parameters, never path segments.** `DEV.QUEUE.1` is an ordinary name,
  and `config/SpaResourceConfig` treats a dotted last segment as a static-file request. Any new endpoint
  taking a destination name must follow this.
- **Ambiguous simple names must be written fully qualified, inline, never imported.** ActiveMQ Classic
  and Artemis both ship `ActiveMQConnectionFactory`, `ActiveMQConnection`, `ActiveMQQueue`,
  `ActiveMQTopic`, `ActiveMQDestination`; `MQQueueManager` exists twice inside the IBM Jakarta client.
  Importing either compiles cleanly and fails at runtime. `ImportGuardTest` fails the build on it.
- **`ImportGuardTest` guards three more things**: `org.apache.kafka` may not leak outside the `kafka`
  package, `jakarta.jms` may not leak into it, `com.ibm.mq.headers` (PCF) may not escape `jms/provider`,
  and the frontend may not import `react-router-dom` (gone in React Router 8).
- **Flyway owns the schema; `ddl-auto=validate`.** Migrations must be generated from Hibernate's own
  export, not hand-written — `Instant` maps to `TIMESTAMP(6) WITH TIME ZONE`, and a plausible-looking
  `TIMESTAMP(6)` fails validation at startup. `V1__connection_profile.sql` explains the procedure;
  `MqManagerApplicationTests` catches a mistake on every build. Never edit an applied migration.
- **`LogBuffer` may not log.** It runs inside the logging pipeline and a single log statement recurses
  until the stack runs out. It is created by `LogBufferInstaller` *before* the Spring context (which is
  why Flyway output is captured) and handed to the context by `MqManagerApplication#logBuffer()`.
- **`BrowserLauncher` pops the default browser only when `mqmanager.open-browser` is on** — off by
  default, set only by the desktop launchers, so a server or `spring-boot:run` never opens a window. It
  reads the port from `WebServerInitializedEvent` (not `server.port`, which may be `0` = pick-any) and
  opens on `ApplicationReadyEvent`; a browser that won't launch is logged and swallowed, never fatal —
  the server being up is the thing that matters.
- **Never add a dependency without re-running `verify-portability.sh`.** It proves the JAR needs nothing
  from the host: `jakarta.jms-api` must be 3.x, no platform-classifier artifacts, and no bundled native
  binaries outside a by-name allowlist that is then proved multi-platform.

### Frontend (`frontend/`, React 19 + Vite + Tailwind v4 + React Query v5 + React Router 8)

- Pages import **hooks**, never `api` directly. One file per resource under `src/api/`, each owning its
  own `invalidateQueries`. `src/api/keys.ts` holds hierarchical keys so prefix invalidation cascades.
- `PROVIDER_CAVEATS` (`features/queue/`) and `PROVIDER_FIELDS` (`features/connections/`) are exhaustive
  `Record<Provider, …>` objects. That is deliberate: adding a provider is a **compile error** until every
  per-provider string is filled in. `ManualPage.tsx` renders provider facts *from* these rather than
  restating them, so the in-app manual cannot go stale.
- **There is no frontend test runner** (no vitest, no jsdom). `tsc --noEmit` inside `npm run build` is the
  only automated gate, which is why the exhaustive `Record`s matter. Do not add a runner casually.
- **No `localStorage` anywhere.** State worth keeping is either in the URL (`?queue=`, via
  `useSearchParams`) or server-side in H2. Introducing browser storage would fork the state model.
- `refetchOnWindowFocus` is off globally: focus refetch would fire real broker connections.
- `CommandPalette` (`components/`, built on `cmdk`) is mounted once globally; it opens on ⌘K/Ctrl-K or a
  `command-palette:open` window event (dispatched by the header button) and navigates via `react-router`.
  Cross-component "open this overlay" is a window `CustomEvent`, not a store — consistent with the
  no-`localStorage`, no-global-state rule above.

### Testing doctrine

No test needs a broker, Docker or the network. JMS providers run against in-process embedded brokers
(`support/EmbeddedActiveMqBroker`, `EmbeddedArtemisBroker`); Kafka uses `MockConsumer`/`MockProducer` and
a Mockito `Admin`; controllers use `@WebMvcTest`; `support/MessagingTestFixture` assembles the *real*
production stack with no Spring context. IBM MQ cannot be integration-tested at all — its correctness
rests on asserting the built connection properties key-by-key plus the manual checklist in `README.md`.

## Versioning

**No `-SNAPSHOT` versions.** `main` always holds a real version, so a locally built JAR is named exactly
like a released one. Every push to `main` releases whatever the pom says, tags that commit, then bumps
the pom by one patch. To cut a minor or major, edit `pom.xml` and push. `.github/workflows/release.yml`
rejects a `-SNAPSHOT`, a non-`x.y.z` version, or a version whose tag already exists.

`pom.xml` has **no `<finalName>`** — Maven's default gives `mq-mebaysanization-<version>.jar`, and
`verify-portability.sh` resolves it by glob.

## Windows packaging for the VDI

The deliverable for the VDI is a portable **`MQ-mebaysanization-<version>-win.zip`**: a launch4j `.exe` +
a bundled Windows JRE (`jre/`) + `app/` (the fat JAR extracted to a thin launcher jar + `lib/`) +
`Baslat-yedek.bat` + `OKU.txt`. It writes its H2 store to a `data\` folder **next to the `.exe`**, which
is always writable — deliberately not `C:\ProgramData`, whose permission wall made an earlier jpackage
build's `.exe` exit on launch with nothing on screen.

**New versions are built the same way, and NOT with jpackage on macOS.** `jpackage` only targets the OS
it runs on, so a Windows package cannot be produced here. Instead reuse a known-good prior `-win.zip` as a
template — its `.exe` and `jre/` are version-independent; only `app/` changes:

```bash
mvn clean package            # builds the fat JAR (tests + frontend + JAR)
./scripts/repackage-win.sh   # -> ~/Desktop/MQ-mebaysanization-<version>-win.zip
```

`scripts/repackage-win.sh` unpacks the template (`~/Desktop/MQ-mebaysanization-1.1.5-win.zip` by
default), runs `java -Djarmode=tools -jar <fat> extract` to get the thin jar + `lib/`, swaps `app/`,
repoints `Baslat-yedek.bat`, and **binary-patches the launch4j `.exe`'s embedded jar path** to the new
name. That patch is safe only when the new jar filename is the **same length** as the template's
(`1.1.5` → `1.1.8`, `1.2.0`: fine; a two-digit segment like `1.1.10` is not) — on a length mismatch the
script leaves the `.exe` alone, and `Baslat-yedek.bat` remains the working launcher (regenerate the
`.exe` with launch4j for a clean fix). `scripts/package-windows.ps1` (jpackage) stays as the alternative
**run on an actual Windows box**, not here.

The `-win.zip` bundles the IBM MQ client inside `lib/`: internal VDI use only, never redistribute, and it
lives on the Desktop **outside the repo** — never commit it (same reason the fat JAR is never attached to
a release; see Licensing).

## Licensing

This repository is **public and Apache-2.0** (`LICENSE`, `NOTICE`, `<licenses>` in `pom.xml`). Two
consequences that are easy to get wrong:

- **Never attach the JAR to a release, and never upload it as an Actions artifact.** The fat JAR nests
  `com.ibm.mq.jakarta.client` byte-for-byte — ~18.4 MiB of IBM restricted materials under
  `BOOT-INF/lib/` — under the IBM International Program License Agreement, which is not an
  open-source licence. On a public repo both release assets and Actions artifacts are world-readable,
  so either one redistributes IBM's client to the world. `release.yml` used to gate this behind a
  `PUBLISH_JAR` variable; that switch was deliberately deleted rather than left set to false. Do not
  reintroduce it. Users build their own JAR — that is what `mvn clean package` in `README.md` is for.
- **The project's own licence and IBM's are separate questions.** Apache-2.0 covers this source and
  grants nothing in any bundled dependency. `README.md` keeps them in two sections (`## License`,
  `## Third-party notices`) for exactly that reason; do not merge them back together.

`maven-resources-plugin` copies `LICENSE` and `NOTICE` into `META-INF/` at `prepare-package` so they
travel with the JAR (Apache-2.0 §4(a) and §4(d)). A new bundled dependency means a new entry in
`NOTICE`.

## Comments

Comments here explain *why*, especially where a decision looks wrong or a simpler alternative was
rejected — several are marked LOAD-BEARING and deleting them loses information the code cannot express.
Match that density when editing; a change that invalidates a comment must update it.

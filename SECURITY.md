# Security policy

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Use GitHub's private vulnerability reporting:
[**Report a vulnerability**](https://github.com/mebaysan/mq-mebaysanization/security/advisories/new).
That opens a private thread visible only to the maintainer.

Expect an acknowledgement within a week. This is a personal project with one maintainer and no
on-call rotation — there is no paid support and no guaranteed patch window, so please size your
expectations to that rather than to a vendor's SLA.

Useful things to include: the version or commit, which of the four providers it involves, whether it
needs an existing saved connection, and the smallest request sequence that shows the problem.

## Supported versions

The latest release only. There are no maintenance branches; fixes land on `main` and go out in the
next release.

## Known and accepted by design

Report these only if you have found a way to make them **worse than described**. Each is a documented
consequence of what this tool is, not an oversight.

### There is no authentication, anywhere

No login, no session, no API key. Anyone who can reach the port can list, browse, send, delete and
purge messages on every broker you have configured, using the credentials you stored — and read the
application's own recent log lines on the Logs page.

The intended deployment is a single operator on `localhost`, or behind an authenticating reverse
proxy. Note the current default: **the server binds to all interfaces**, so on a shared network it is
reachable by others until you set `SERVER_ADDRESS=127.0.0.1`. See
[the warning at the top of README.md](README.md#-this-version-has-no-authentication).

### The connection test is an outbound-reachability primitive

`POST /api/connections/test` opens a TCP connection to any host and port the caller supplies, and the
error code distinguishes "refused" from "something answered". That makes it a port scanner for
whatever network the process sits on. It is inherent to a tool whose job is connecting to brokers you
name; it can be documented, not removed.

### Broker connections are plaintext

TLS to brokers is not implemented — see *Not in this version* in `README.md`. Kafka credentials go
over `SASL_PLAINTEXT`; `SASL_SSL`, SCRAM, OAUTHBEARER and mTLS are out of scope in this version.
Broker passwords travel in the clear on the wire.

### The broker URL override is stored unencrypted

Only the password field is encrypted at rest. The override round-trips through the edit form, so it is
stored and returned as typed. Credentials pasted into it are readable via `GET /api/connections`.
Documented at the field, and in *Encryption and key management* in `README.md`.

### The encryption key sits next to the database it protects

`data/encryption.key` is created `0600`, but anyone who can read the data directory can decrypt every
stored broker password. Supply your own key out-of-band with `MQMANAGER_ENCRYPTION_KEY` if that
matters to you.

### Message bodies can reach the unauthenticated Logs page

`MQMANAGER_LOG_PAYLOADS` ships enabled, so raising `logging.level.com.baysansoft.mqmanager` to `DEBUG`
puts message bodies into the log buffer, which is served without authentication. Set it to `false` to
keep them out whatever the level.

## What is genuinely in scope

Anything that breaks a stated guarantee, for example:

- A credential reaching a log line, an error body, or an API response other than as documented above.
- Reading or writing a broker the caller has not configured, or escaping the configured credentials.
- Path traversal out of the static-resource handler, or reading a file off the host.
- Deserialization of untrusted message content — `ObjectMessage` is deliberately never deserialized,
  so a way to make it happen is a real finding.
- Remote code execution by any route.

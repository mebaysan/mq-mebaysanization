# Contributing

Thanks for looking. This is a small project with one maintainer, so the most useful thing you can do
before writing code is open an issue and check the change is wanted.

**Security problems do not go here** — see [SECURITY.md](SECURITY.md).

## Before you start

Read [`CLAUDE.md`](CLAUDE.md). It is written for AI coding agents but it is the honest architecture
guide: the two provider seams, the constraints that will bite you, and the doctrine the whole codebase
is organised around. `README.md` is the user-facing manual and is unusually detailed — if you are
changing behaviour it documents, update it in the same commit.

The one rule worth repeating here: **results must not lie.** The brokers genuinely differ, and this
codebase refuses to paper over it. A result never reads like success when it is not, and a number is
never presented as exact when it is a floor. When you add an operation, decide what it *cannot* know
and put that in the return type rather than simplifying it away.

## Setting up

You need JDK 21+ and Maven 3.9+. You do **not** need Node — Maven downloads its own. You do not need a
broker, Docker, or a network connection to run the tests.

```bash
mvn clean package          # tests, then the frontend build, then the fat JAR
mvn -Pskip-frontend test   # backend only, and much faster
```

Two-terminal development:

```bash
mvn spring-boot:run              # :8080, does NOT build the frontend
cd frontend && npm run dev       # :5173, proxies /api to :8080
```

There is a throwaway broker launcher for driving the UI by hand — see *A local broker for manual
testing* in `README.md`.

## Before you open a PR

```bash
mvn clean package                  # the whole gate
cd frontend && npx tsc --noEmit    # the only automated check the UI has
./scripts/verify-portability.sh    # required after ANY dependency change
```

CI runs the same things on every pull request.

Notes on the checks:

- **`ImportGuardTest` is not being difficult.** ActiveMQ Classic and Artemis both ship classes with
  identical simple names, and importing either compiles cleanly then fails at runtime. Write those
  fully qualified, inline. The same test keeps Kafka out of the JMS packages and `react-router-dom`
  out of the frontend.
- **There is no frontend test runner**, deliberately. `tsc --noEmit` is the gate, which is why
  `PROVIDER_CAVEATS` and `PROVIDER_FIELDS` are exhaustive `Record<Provider, …>` objects — adding a
  provider is meant to be a compile error until every per-provider string is filled in. Please do not
  add a runner as a drive-by.
- **Never edit an applied Flyway migration.** Add a new one, generated from Hibernate's own export
  rather than hand-written. `V1__connection_profile.sql` explains the procedure.
- **Do not add a dependency without re-running `verify-portability.sh`.** It proves the JAR needs
  nothing from the host.

## Style

Match the surrounding code. Comments here explain *why*, especially where a decision looks wrong or a
simpler alternative was rejected — several are marked LOAD-BEARING and deleting them loses information
the code cannot express. A change that invalidates a comment must update it.

Commits follow Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`) because the release notes are
generated from them.

## Versioning

Don't touch `pom.xml`'s `<version>` in a PR. Every push to `main` releases whatever the pom says and
then bumps the patch automatically; a version change in a PR will collide with that.

## Licensing of contributions

By submitting a pull request you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE), the same terms as the rest of the project. There is no CLA.

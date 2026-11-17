# Template publication fan-out worker

A background worker that schedules engagement verification after a template is published. It saves progress, limits concurrent work and recovers from failures.

**Stack:** Java 21 · Spring Boot · PostgreSQL · Spring Data JDBC · Liquibase

## Quick start

With Docker Desktop running, start the background worker from the repository root:

```shell
docker compose up -d --wait postgres
docker compose up -d --build fanout-worker
docker compose logs -f fanout-worker
```

The worker processes saved tasks and pending outbox entries, then keeps polling for more work. An empty database leaves it idle. No HTTP server or automatic demo runs. Local external services are simulated; production requires real adapters.

Stop with `docker compose stop fanout-worker`; start it again with `docker compose up -d fanout-worker`. Jobs remain in PostgreSQL. When all workers and tests are finished, stop PostgreSQL with `docker compose stop postgres`.

To run from your terminal or IntelliJ instead, start PostgreSQL and use `mvn spring-boot:run` or run `FanOutApplication`. Stop with Ctrl+C or the IDE stop action. The default `local` profile connects to PostgreSQL on port **15432**.

For the optional finite demo, use `docker compose run --build --rm fanout-demo`. It creates three example tasks, waits for `DONE` and exits. Equivalently, enable `fanout.demo.enabled=true` when running locally. Real publications must enter through the existing `PlanPublication` port; wiring an event source to that port remains outside this component.

## Tests

For Maven commands, install **JDK 21** and **Maven 3.9+**.

| Command | Coverage | PostgreSQL required |
| --- | --- | --- |
| `mvn test` | Unit tests, configuration binding and architecture rules | No |
| `mvn verify` | All of the above, plus integration tests | Yes |

### Integration tests with local PostgreSQL

Start PostgreSQL, then run Maven from the repository root (PowerShell):

```powershell
docker compose up -d --wait postgres
if ($LASTEXITCODE -ne 0) { throw "PostgreSQL did not start." }

mvn verify
```

**Liquibase runs automatically.** Each fixture uses an isolated schema in the local Compose database. Keep PostgreSQL running until the tests finish. Defaults in [test-database.properties](src/test/resources/test-database.properties) match Compose, so Maven `verify` also works from IntelliJ without setting local credentials manually.

The default local port is **15432**. For a custom database, set `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME` and `TEST_DATABASE_PASSWORD` in your shell or IntelliJ run configuration. These override the local defaults; CI uses them for its own database. Maven does not automatically read `.env`.

Reports: `target/surefire-reports` for unit tests and `target/failsafe-reports` for integration tests. See [Bash commands](docs/DEPLOYMENT.md#local-integration-tests-in-bash) or [connection troubleshooting](docs/DEPLOYMENT.md#troubleshooting-integration-startup) if needed.

## How it works

1. **Planner** reads inventory in pages and saves tasks, notifications and progress together.
2. **Dispatcher** publishes pending notifications to the PostgreSQL queue.
3. **Worker** claims a task, checks capacity, reconciles remote status and saves the result before acknowledging it.

The application uses hexagonal architecture in one Maven module:

| Package | Responsibility |
| --- | --- |
| `domain` | Models, validation and business policies |
| `application` | Use cases and input/output ports |
| `adapter` | Persistence, polling, heartbeat and demo integrations |
| `bootstrap` | Spring configuration and dependency wiring |

PostgreSQL coordinates replicas. Retries and circuit-breaker state are persisted; recovery reuses the operation ID when the remote outcome is uncertain. The real downstream service must support durable deduplication and status lookup.

## Environments and CI

| Profile | Credentials | Startup migrations | External services |
| --- | --- | --- | --- |
| `local` | Local defaults | Automatic | Simulated |
| `qa` / `prod` | Injected secrets | Separate deployment step required | Real adapters required |

Activate a profile with `SPRING_PROFILES_ACTIVE`. See [configuration and secrets](docs/CONFIGURATION.md) for the available settings.

[GitHub Actions](.github/workflows/ci.yml) runs unit tests without a database, then integration tests with disposable PostgreSQL, followed by container checks. Manual runs can validate QA/PROD configuration and optionally publish the tested image to GHCR. **The pipeline does not deploy or migrate QA/PROD.** See [environment setup](docs/DEPLOYMENT.md#github-actions-and-validation).

This exercise implements the fan-out worker. Production use still requires real inventory/gateway adapters and a trigger to resume planning. Template application and the query/LLM API are outside its scope.

**Exercise scope:** Part 2 requests a narrow Java fan-out worker demonstrating concurrency, failure handling, idempotency and downstream capacity. Continuous execution supports that component; Docker is a convenience, not an explicit requirement. The assignment explicitly excludes a full application build.

## Further reading

| Document | Use it for |
| --- | --- |
| [Design document](DESIGN_DOCUMENT.md) | Requirements, architecture and diagrams |
| [Implementation reference](docs/IMPLEMENTATION.md) | Concurrency, idempotency, migrations, exceptions and test coverage |
| [Configuration](docs/CONFIGURATION.md) | Profiles, properties and secret injection |
| [Deployment guide](docs/DEPLOYMENT.md) | Local setup, troubleshooting, containers and CI |

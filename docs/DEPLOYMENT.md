# PostgreSQL deployment and containers

The application uses PostgreSQL 17+ through Spring Data JDBC and Liquibase. Multiple worker replicas share one regional database/schema. The image is stateless: it needs no application data volume. Real `Inventory` and `EngagementGateway` adapters and a trigger that resumes planning are still required for a production worker. The `local` profile supplies in-memory fakes and runs continuously by default; the finite demo is opt-in.

See [environment properties and secret injection](CONFIGURATION.md) for the local/QA/PROD profiles, all runtime settings, environment-variable names and mounted secret files. QA/PROD credentials are not stored in the repository or image.

## Continuous local worker

```shell
docker compose up -d --wait postgres
docker compose up -d --build fanout-worker
docker compose logs -f fanout-worker
```

The detached worker consumes stored tasks and unpublished outbox entries, then stays idle until more work arrives. It does not open an HTTP port or create sample tasks. `restart: unless-stopped` restarts an unexpectedly exited container. Stop gracefully with `docker compose stop fanout-worker`; resume with `docker compose up -d fanout-worker`.

Work must already have been persisted through `PlanPublication` or supplied by an integrated publisher. This runtime does not resume incomplete inventory planning automatically. Local gateway outcomes are in memory, so local mode is a runnable example rather than a durable implementation of the external service contract.

## Optional finite demonstration

```shell
docker compose up -d --wait postgres
docker compose run --build --rm fanout-demo
```

This service explicitly enables `FANOUT_DEMO_ENABLED=true` and has no restart policy. Its Compose `demo` profile keeps it out of an ordinary `docker compose up`. For a host JVM, add `--fanout.demo.enabled=true` to run the finite demo; without that flag, the application keeps polling until shutdown.

Compose starts PostgreSQL on loopback port 15432 with database/user `fanout`. Its default password `fanout-local-only` is for local use; override `POSTGRES_PASSWORD` when needed. The named `postgres-data` volume belongs to PostgreSQL. `docker compose down` retains it; repeated demos preserve completed jobs and Liquibase history. The old H2 `fanout-data` volume is not read or deleted.

For integration tests, follow the [PowerShell commands in the README](../README.md#integration-tests-with-local-postgresql) or the [Bash commands below](#local-integration-tests-in-bash). They start PostgreSQL and wait for readiness; test connection defaults already match Compose. No manual Liquibase command is required. Keep PostgreSQL running for tests and the host JVM; stop it with `docker compose stop postgres` only when finished. If you previously ran `docker compose down`, start it again first.

For a host JVM, set these environment variables in your shell:

| Variable | Local Compose value |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:15432/fanout` |
| `SPRING_DATASOURCE_USERNAME` | `fanout` |
| `SPRING_DATASOURCE_PASSWORD` | `fanout-local-only` |
| `TEST_DATABASE_URL` | `jdbc:postgresql://localhost:15432/fanout` |
| `TEST_DATABASE_USERNAME` | `fanout` |
| `TEST_DATABASE_PASSWORD` | `fanout-local-only` |

Then run `mvn verify` and `java -jar target/template-fanout-worker-1.0.0.jar`. For unit tests only, run `mvn test`; no PostgreSQL service or database credentials are needed. Integration tests (`*IT`, Maven Failsafe) run during `mvn verify` and use unique schemas; use a dedicated database/account in CI and never point them at a customer database. Without an available PostgreSQL server the integration suite fails, rather than skipping or falling back to another engine.

## Local integration tests in Bash

Run from the repository root with Docker Desktop running.

```bash
docker compose up -d --wait postgres && mvn verify
```

The test-only `src/test/resources/test-database.properties` matches the local Compose defaults. To use another database, override `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME` and `TEST_DATABASE_PASSWORD`. CI does this independently. Keep PostgreSQL running until Maven finishes.

## Troubleshooting integration startup

Read the deepest `Caused by` in `target/failsafe-reports/*.txt`:

| Error | Action |
| --- | --- |
| Docker reports `ports are not available` / `forbidden by its access permissions` | Choose an available `POSTGRES_PORT` and update the connection URLs. On Windows, inspect reserved ranges with `netsh interface ipv4 show excludedportrange protocol=tcp`; do not remove system reservations. |
| `Connection ... refused` | Start PostgreSQL with `docker compose up -d --wait postgres`; confirm `docker compose ps` shows it healthy on port 15432. Liquibase has not run yet. |
| `database "fanout_test" does not exist` | Remove an outdated `TEST_DATABASE_URL` override to use local defaults, or point it to an existing test database. |
| `password authentication failed` | Match `TEST_DATABASE_PASSWORD` to the database's actual password. Changing `.env` does not change credentials already stored in an existing PostgreSQL volume. |
| `permission denied for database` | The test account must be allowed to create schemas in its test database. |

Do not delete the database volume to resolve a connection problem. `docker compose logs postgres` can help diagnose startup failures. For IntelliJ or another IDE, the standard local setup needs only Maven `verify` and a running PostgreSQL container. For custom settings, configure `TEST_DATABASE_*` in the Maven/JUnit run configuration; shell assignments only affect processes launched from that shell. Remove stale overrides such as the former password `test-only` when returning to local defaults.

## Replicas in the cloud

1. Provision a PostgreSQL database in the required region, with TLS, backup/restore and failover settings chosen for the service. All replicas of a regional deployment must share the same schema and admission policy. Use separate databases for different regional boundaries.
2. Apply `src/main/resources/db/changelog/postgresql/db.changelog-master.xml` once using Liquibase in a deployment job with migration privileges. The master references `.sql` files. On an empty database, bootstrap migrations before simultaneous application startup; avoid relying on concurrent creation of Liquibase's own history tables.
3. Inject the database URL and credentials from the platform. For a managed service use its certificate configuration and a JDBC URL such as `jdbc:postgresql://host:5432/fanout?sslmode=verify-full`. Do not place production credentials in the image or Compose file.
4. Deploy the worker image with the real adapters, `SPRING_PROFILES_ACTIVE=qa` or `prod`, and `SPRING_LIQUIBASE_ENABLED=false`. Give the runtime role DML permissions on application tables and usage on the message identity and dispatch sequences. Migrations can use a separate privileged role.
5. Scale replicas against that database. The fixed thread pool is local; global/per-firm remote reservations, start spacing and circuit state are coordinated in PostgreSQL. The downstream service must durably deduplicate operation IDs across replicas and restarts.

The PostgreSQL queue uses `FOR UPDATE ... SKIP LOCKED`. Task ownership is fenced by row locks, lease expiration and increasing tokens. PostgreSQL time is used for scheduling/ownership. Metadata updates lock only their firm/engagement; pages lock their publication. The admission row still serializes short capacity/rate/circuit decisions, including completion accounting. It no longer serializes ordinary reads, queue consumption, claims or heartbeat renewals. Measure contention at expected traffic before claiming a production throughput target; this is not an unbounded-scaling design.

Hikari defaults to 8/12/24 connections per replica for local/QA/PROD, so budget the total against the database connection limit. A configurable session `lock_timeout` (five seconds by default) bounds lock waits. Monitor waiting transactions, oldest queued work, pending outbox, unknown remote outcomes and retained reservations. The SQL queue and global admission counter are deliberately kept in the existing application rather than adding a broker or a new service.

## Container contract

Build with `docker build --tag template-fanout-worker:local .`. The multi-stage image contains a Java 21 JRE and the executable JAR, running as UID/GID `10001:10001`. The filesystem may be read-only with writable `/tmp`; no `/app/data` volume is required. Database credentials are supplied at runtime.

| Setting | Default / purpose |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | Image default `prod`; requires injected credentials and real adapter beans. Compose explicitly selects `local`. |
| `SPRING_DATASOURCE_URL` | Local default `jdbc:postgresql://localhost:15432/fanout`; required injection in QA/PROD. |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Runtime credentials; password has no production default. |
| `SPRING_LIQUIBASE_ENABLED` | Local: true. QA/PROD: false; migrate once before deployment. |
| `FANOUT_REGION` | Local: EU. QA/PROD: required injection; must match stored configuration. |
| `FANOUT_LOCALCONCURRENCY` | Local: two threads. QA/PROD: four; configurable. |
| `FANOUT_LIMITS_MAXACTIVE`, `FANOUT_LIMITS_MAXPERFIRM` | Shared reservation limits, defaults two and one. |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=65.0 -XX:+ExitOnOutOfMemoryError`. |

There is no HTTP listener or readiness endpoint. Use a job platform for the local demo; the integrated worker is a long-running background process. Java is PID 1 and receives SIGTERM directly. Allow a stop grace period of at least 30 seconds. Shutdown stops polling, then heartbeat scheduling, then closes the datasource. It does not imply cancellation of unresolved remote work; those reservations remain in PostgreSQL.

## Existing H2 data

The old changelog remains unchanged for reference; PostgreSQL has a separate changelog and driver. Existing H2 files are not automatically converted. For a real cutover, stop writers and inventory pending operations; back up/export all application tables; create the PostgreSQL schema; import and validate keys, counts, cursors, operation IDs, tokens and receipts; advance message identity and dispatch sequences past imported values; then start consumers with the matching policy. Reconcile unknown remote operations using the original IDs. Do not import H2 Liquibase history as PostgreSQL migration history. This repository does not automate the data transfer or overwrite old files.

## GitHub Actions and validation

[ci.yml](../.github/workflows/ci.yml) first runs `mvn test` in an independent job without a database service or credentials. After that succeeds, a separate job starts a PostgreSQL 17 service and runs the complete `mvn verify` lifecycle, including Checkstyle, unit tests, real database migrations, multi-instance contention, rollback, interruption and child-process crash recovery. It retains Surefire and Failsafe reports even on failure. It then builds a Linux image, validates Compose and invokes:

```shell
bash scripts/smoke-container.sh template-fanout-worker:ci
```

The smoke script creates its own temporary PostgreSQL container and network. It initializes the schema with one finite demo, clears only application rows in that disposable database, runs two demo replicas concurrently, and checks a subsequent restart. All three runs must report three completed tasks with one attempt each and zero active reservations. It then seeds a pending outbox in that same disposable database, launches the continuous worker, verifies that it completes the backlog and remains running, and stops it with SIGTERM without a forced kill. Cleanup removes only the containers/network created by the script. This demonstrates runtime coordination, not the remote fake's durability across failures.

The workflow retains the JAR and tested image archive. Manual dispatch accepts `target_environment` (`none`, `QA` or `PROD`) plus `publish_image`. QA and PROD have separate preparation jobs tied to GitHub Environments of the same names and lowercase Spring profiles. They use `deployment: false`, inject `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` from that environment's secrets and `FANOUT_REGION` from its variables, and validate inputs offline. Neither job starts the application, connects to the selected database, runs migrations or deploys.

Create those GitHub Environments and their values before running preparation. Keep secrets at environment scope; do not define repository-wide fallback credentials with the same names. Restrict environment branches to the repository's default branch and configure PROD reviewers/protection rules as appropriate. Environment protection is configured in GitHub, not automatically by workflow YAML. Configure the following values in **Settings > Environments** for both QA and PROD:

| Name | GitHub entry |
| --- | --- |
| `DB_URL` | Environment secret |
| `DB_USERNAME` | Environment secret |
| `DB_PASSWORD` | Environment secret |
| `FANOUT_REGION` | Environment variable |

Choose **Actions > Verify and prepare environments > Run workflow**, the default branch, and a target environment. Preparing QA/PROD from another branch fails before secrets are requested. Publishing without selecting an environment also fails. On successful preparation, optional publication loads the exact tested archive and pushes `ghcr.io/<owner>/<repository>:sha-<commit>-run-<run-id>-<attempt>`; the summary reports its digest. Only the publishing job has package-write permission, and it receives no database secrets. There is no automatic QA-to-PROD image promotion in this preparation-only workflow.

Pushes and pull requests only perform CI with disposable PostgreSQL credentials. No cloud resources, GitHub secrets or deployment targets are created by these repository files. Runtime secret injection into a future deployment remains a separate platform-specific step.

References: [PostgreSQL locking](https://www.postgresql.org/docs/17/explicit-locking.html), [queue consumers with SKIP LOCKED](https://www.postgresql.org/docs/17/sql-select.html), [PostgreSQL clock functions](https://www.postgresql.org/docs/17/functions-datetime.html), [GitHub environment secrets without a deployment](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/deploy-to-environment).

# Environment configuration: local, QA and PROD

Activate exactly one environment with `SPRING_PROFILES_ACTIVE=local`, `qa` or `prod` (lowercase Spring profile names). Running the JAR without a profile selects `local`. The Docker image defaults to `prod`; Compose explicitly selects `local`. Mixing environment profiles fails startup, so a local credentials file cannot silently supply missing PROD settings.

| Setting | local | qa | prod |
| --- | --- | --- | --- |
| Configuration file | `application-local.yml` | `application-qa.yml` | `application-prod.yml` |
| Database endpoint/credentials | Disposable local defaults, overridable | Required injection | Required injection |
| Region | `EU`, overridable | Required injection | Required injection |
| Startup migrations | Enabled | Disabled | Disabled |
| Local processing threads | 2 | 4 | 4 |
| Maximum JDBC connections per replica | 8 | 12 | 24 |
| Idle polling delay | 100 ms | 50 ms | 20 ms |
| Demo inventory/gateway | Simulated | Excluded | Excluded |
| Automatic demo runner | Disabled; opt in with `fanout.demo.enabled=true` | Excluded | Excluded |

QA and PROD have no real inventory/gateway implementation in this exercise. They fail startup until those adapters are supplied; selecting a higher environment never silently activates the fakes. The profile/configuration and secret injection paths are tested independently of those missing integrations. No QA/PROD infrastructure or secret-manager resources are created by this change.

## Configuration ownership

`application.yml` holds shared operational defaults; the three environment files override them. No numeric `@DefaultValue` or duplicate JDBC URL fallback remains in Java. `FanOutProperties` and local-only `DemoProperties` bind and validate these values. Adapters receive plain Java values through their constructors and remain independent of Spring configuration.

Environment variables override packaged YAML. Use canonical Spring environment names: replace dots with underscores, remove hyphens, and uppercase. For example, `fanout.runtime.dispatch-batch-size` becomes `FANOUT_RUNTIME_DISPATCHBATCHSIZE`.

Local mode normally keeps the worker running. Set `fanout.demo.enabled=true` (`FANOUT_DEMO_ENABLED=true`) only to create sample work, wait for completion and exit. This switch controls the demo runner, while `fanout.worker-enabled` controls the background polling threads. With an empty queue, an enabled worker remains alive and waits for persisted work. QA/PROD never enable demo adapters or the demo runner.

For local Docker Compose, `POSTGRES_PORT` controls the host port (default `15432`, previously `55432`, which may be reserved by Windows). The local Spring datasource URL uses the same variable unless `DB_URL` or `SPRING_DATASOURCE_URL` overrides the entire URL. Set `TEST_DATABASE_URL` explicitly for integration tests when changing ports. Compose reads `.env`; host Maven/Java processes require shell environment variables. Container-to-container PostgreSQL connections still use port `5432`, and the CI service retains its explicit host port `55432`.

| Property | Environment variable | Shared default / purpose |
| --- | --- | --- |
| `fanout.local-concurrency` | `FANOUT_LOCALCONCURRENCY` | Overridden per profile above |
| `fanout.page-size` | `FANOUT_PAGESIZE` | 100 inventory candidates per page |
| `fanout.worker-enabled` | `FANOUT_WORKERENABLED` | true |
| `fanout.runtime.dispatch-batch-size` | `FANOUT_RUNTIME_DISPATCHBATCHSIZE` | 100 notifications per dispatch |
| `fanout.runtime.idle-delay` | `FANOUT_RUNTIME_IDLEDELAY` | Overridden per profile above |
| `fanout.runtime.failure-delay` | `FANOUT_RUNTIME_FAILUREDELAY` | 100 ms after a failed iteration |
| `fanout.runtime.shutdown-timeout` | `FANOUT_RUNTIME_SHUTDOWNTIMEOUT` | 5 s for each of graceful and interrupted waits |
| `fanout.heartbeat.threads` | `FANOUT_HEARTBEAT_THREADS` | 1 scheduler thread |
| `fanout.heartbeat.interval` | `FANOUT_HEARTBEAT_INTERVAL` | 3 s; must not exceed half the lease |
| `fanout.heartbeat.shutdown-timeout` | `FANOUT_HEARTBEAT_SHUTDOWNTIMEOUT` | 5 s |
| `fanout.database.lock-timeout` | `FANOUT_DATABASE_LOCKTIMEOUT` | 5 s PostgreSQL session lock timeout |
| `fanout.limits.*` | e.g. `FANOUT_LIMITS_MAXACTIVE` | Shared admission, rate, retry and circuit policy |
| `spring.datasource.hikari.*` | e.g. `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE` | Connection pool sizing/timeouts |
| `fanout.demo.*` | e.g. `FANOUT_DEMO_DELAY` | Local fake delay, completion timeout, poll interval and inspection bound |

Fan-out durations accept values such as `75ms`, `5s` or `1m`. Hikari timeout properties bind to millisecond numbers (for example `SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT=30000`). Its minimum-idle, connection/validation/idle timeouts and max lifetime are configured in shared YAML. Budget connection counts across all replicas and keep the container stop grace period above both worker shutdown waits plus the heartbeat shutdown wait.

Global capacity defaults remain two reservations and one per firm in every environment until the service owner grants a different budget. All replicas sharing one regional database must use the same `fanout.limits` policy; changing these values requires coordinating the stored policy and active reservations. Runtime polling/pool settings can differ by replica without modifying the persisted shared policy.

Fixed schema/domain bounds (identifier length, maximum accepted page size), unit conversions, jitter arithmetic and the four-file demo fixture remain code contracts, not environment settings. Making these arbitrary deployment values would require changing their persistence/business contracts as well.

## Secrets in QA and PROD

Inject these at deployment time from the cloud secret store or the platform's secret references:

- `DB_URL`: PostgreSQL JDBC endpoint; use the provider's TLS settings, normally `sslmode=verify-full`.
- `DB_USERNAME` and `DB_PASSWORD`: environment-specific runtime credentials.
- `FANOUT_REGION`: non-secret regional configuration.
- `SPRING_PROFILES_ACTIVE`: `qa` or `prod`.

The standard `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` names also work and override the YAML aliases. Missing/blank credentials in QA or PROD cause an `InvalidConfigurationException` before opening connections. Validation messages name the missing property without echoing its value. Do not put credentials into `FanOutProperties`, command-line arguments, Docker build arguments, repository YAML, GitHub build artifacts or committed `.env` files.

Example using variables already supplied to the host by the deployment system:

```shell
docker run --rm \
  --env SPRING_PROFILES_ACTIVE=qa \
  --env DB_URL --env DB_USERNAME --env DB_PASSWORD --env FANOUT_REGION \
  template-fanout-worker:local
```

For PROD change the profile to `prod`. This example demonstrates injection syntax; running it requires the real external adapters and an already migrated database.

Alternatively mount a read-only directory at `/run/secrets/` with files named:

```text
spring.datasource.url
spring.datasource.username
spring.datasource.password
```

Each file contains only its property's value. QA/PROD import this location with Spring `configtree:`. The directory is optional to allow environment-variable injection, but the required values are still validated. For a different location set `SPRING_CONFIG_IMPORT=configtree:/custom/secrets/`. Values are loaded at startup; rotate/restart replicas according to the platform's secret rotation procedure. A file update does not automatically recreate the connection pool.

GitHub Actions uses disposable PostgreSQL credentials for CI and separate manual `prepare_qa`/`prepare_prod` jobs for environment configuration. Configure `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` as secrets and `FANOUT_REGION` as a variable in each GitHub Environment (`QA` and `PROD`). The selected job injects those values only into an offline validation step, with Spring profile `qa` or `prod`. It does not connect to the environment, deploy, or persist secrets. Optional GHCR publication uses the tested image without embedding those values. See [pipeline setup](DEPLOYMENT.md#github-actions-and-validation). A future deployment must inject runtime secrets again through its platform; preparation does not transfer them to a running application.

## Local execution and compatibility

`docker compose up -d postgres` and `docker compose run --build --rm fanout-demo` use the `local` profile. `.env.example` documents the disposable Compose password override. `.env` is ignored by Git; host Java does not load it automatically.

The previous `demo`/`worker` profile names are replaced by `local`/`qa`/`prod`. The old `fanout.jdbc-url` option is removed; use `spring.datasource.url` or `DB_URL`. Demo settings now live under `fanout.demo`, for example `--fanout.demo.delay=60s` and `--fanout.demo.timeout=5m`.

Migrations remain versioned SQL files referenced by Liquibase. Apply them with a privileged deployment job before starting QA/PROD replicas; startup migration is disabled in those profiles. Local migration runs automatically. Tests continue to use `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME` and `TEST_DATABASE_PASSWORD` independently of application credentials.

Reference: [Spring Boot 3.5 external configuration, environment binding and configuration trees](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html).

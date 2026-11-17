#!/usr/bin/env bash
set -euo pipefail

image="${1:-template-fanout-worker:local}"
name="fanout-smoke-$$-${RANDOM}"
network="${name}-network"
database="${name}-postgres"
logs="$(mktemp -d)"
cleanup() {
  docker rm -f "${name}-seed" "${name}-a" "${name}-b" "${name}-restart" "${name}-worker" "$database" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -f -- "$logs/seed.log" "$logs/a.log" "$logs/b.log" "$logs/restart.log"
  rmdir -- "$logs"
}
trap cleanup EXIT

test "$(docker image inspect --format '{{.Config.User}}' "$image")" = '10001:10001'
docker network create "$network" >/dev/null
docker run -d --name "$database" --network "$network" \
  -e POSTGRES_DB=fanout -e POSTGRES_USER=fanout -e POSTGRES_PASSWORD=smoke-only \
  postgres:17 >/dev/null
ready=false
for attempt in $(seq 1 60); do
  if docker exec "$database" pg_isready -U fanout -d fanout >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
test "$ready" = true

run_app() {
  local suffix="$1"
  shift
  timeout --signal=TERM --kill-after=10s 90s docker run --rm --name "${name}-${suffix}" \
    --network "$network" --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
    --cap-drop ALL --security-opt no-new-privileges \
    --memory 512m --cpus 1 --stop-timeout 30 \
    -e SPRING_PROFILES_ACTIVE=local -e FANOUT_DEMO_ENABLED=true -e FANOUT_DEMO_DELAY=250ms \
    -e "SPRING_DATASOURCE_URL=jdbc:postgresql://${database}:5432/fanout" \
    -e SPRING_DATASOURCE_USERNAME=fanout -e SPRING_DATASOURCE_PASSWORD=smoke-only \
    -e LOGGING_LEVEL_ROOT=WARN "$image" "$@" >"$logs/$suffix.log" 2>&1
}

# Run one finite demo to initialize migration history before scaling consumers.
if ! run_app seed --fanout.demo.delay=0ms; then
  cat "$logs/seed.log"
  exit 1
fi
# Reset only application rows in the disposable PostgreSQL container created by this script.
docker exec "$database" psql -U fanout -d fanout -v ON_ERROR_STOP=1 \
  -c 'TRUNCATE metadata, messages, outbox, tasks, firms, runs; UPDATE control SET next_start=0,failures=0,open_until=0,probe_task=NULL;' >/dev/null

run_app a --spring.liquibase.enabled=false &
first=$!
run_app b --spring.liquibase.enabled=false &
second=$!
result=0
wait "$first" || result=1
wait "$second" || result=1
cat "$logs/a.log" "$logs/b.log"
test "$result" -eq 0
for replica in a b; do
  test "$(grep -c ': DONE, attempts=1' "$logs/$replica.log")" -eq 3
  grep -q 'Active remote reservations: 0' "$logs/$replica.log"
done
if ! run_app restart; then
  cat "$logs/restart.log"
  exit 1
fi
cat "$logs/restart.log"
test "$(grep -c ': DONE, attempts=1' "$logs/restart.log")" -eq 3
grep -q 'Active remote reservations: 0' "$logs/restart.log"
printf 'Two replicas and persistent restart passed on PostgreSQL.\n'

# Leave a pending outbox backlog in this script's disposable database, then launch the ordinary worker.
docker exec "$database" psql -U fanout -d fanout -v ON_ERROR_STOP=1 \
  -c "BEGIN; TRUNCATE messages; UPDATE tasks SET state='READY', attempts=0, operation_id=NULL, due_at=0, token=0, lease_until=0, error_code=NULL; UPDATE outbox SET published=false; COMMIT;" >/dev/null
docker run -d --name "${name}-worker" --network "$network" \
  --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --cap-drop ALL --security-opt no-new-privileges --memory 512m --cpus 1 --stop-timeout 30 \
  -e SPRING_PROFILES_ACTIVE=local -e FANOUT_DEMO_ENABLED=false \
  -e "SPRING_DATASOURCE_URL=jdbc:postgresql://${database}:5432/fanout" \
  -e SPRING_DATASOURCE_USERNAME=fanout -e SPRING_DATASOURCE_PASSWORD=smoke-only \
  "$image" >/dev/null
completed=false
for attempt in $(seq 1 60); do
  done_count="$(docker exec "$database" psql -U fanout -d fanout -Atc "SELECT COUNT(*) FROM tasks WHERE state='DONE' AND attempts=1")"
  if [[ "$done_count" == 3 ]]; then
    completed=true
    break
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "${name}-worker")" != true ]]; then
    break
  fi
  sleep 1
done
docker logs "${name}-worker"
test "$completed" = true
test "$(docker inspect --format '{{.State.Running}}' "${name}-worker")" = true
docker stop --time 30 "${name}-worker" >/dev/null
exit_code="$(docker inspect --format '{{.State.ExitCode}}' "${name}-worker")"
[[ "$exit_code" == 0 || "$exit_code" == 143 ]]
printf 'Continuous worker drained persisted work, stayed running and stopped gracefully.\n'

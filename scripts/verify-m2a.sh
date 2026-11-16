#!/usr/bin/env bash
# M2a 걷는 뼈대 확인: compose 스택을 띄우고, 실제 에이전트가 보낸 표본이 토큰의 노드 이름으로 DB 에 쌓이는지 본다.
#
#   scripts/verify-m2a.sh          스택을 띄우고 확인한다. 스택은 그대로 둔다. 여러 번 돌려도 된다.
#   scripts/verify-m2a.sh --down   스택과 DB 볼륨을 지운다.
#
# deploy/.env 가 없으면 무작위 DB 비밀번호로 만든다(git 제외 파일).
# TLS 를 가로채는 프록시 뒤라면 BCML_BUILD_EXTRA_CA=<프록시 CA PEM 묶음의 절대 경로> 를 주고 돌린다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/deploy/.env"
CONTRACT="$ROOT/contract/agent-report/v1/minimal-heartbeat.json"

# buildx bake 는 프로젝트 폴더 밖의 파일을 읽으려면 따로 허락을 받는다. CA 경로를 직접 준 경우에만 그 검사를 끈다.
[[ -z "${BCML_BUILD_EXTRA_CA:-}" ]] || export BUILDX_BAKE_ENTITLEMENTS_FS=0

compose() {
  docker compose -f "$ROOT/deploy/docker-compose.yml" "$@"
}

if [[ "${1:-}" == "--down" ]]; then
  [[ -f "$ENV_FILE" ]] || export BCML_DB_PASSWORD=unused-for-down
  compose down --volumes
  exit 0
fi
if [[ $# -gt 0 ]]; then
  echo "usage: $0 [--down]" >&2
  exit 2
fi

if [[ ! -f "$ENV_FILE" ]]; then
  (umask 077 && printf 'BCML_DB_PASSWORD=%s\n' "$(python3 -c 'import secrets; print(secrets.token_hex(24))')" > "$ENV_FILE")
  echo "created $ENV_FILE with a random database password"
fi

PORT="$(sed -n 's/^BCML_HTTP_PORT=//p' "$ENV_FILE" | tail -n 1)"
PORT="${BCML_HTTP_PORT:-${PORT:-8080}}"
BASE="http://localhost:$PORT"
REPORT_URL="$BASE/api/v1/agent/report"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failures=0
expect() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    echo "ok    $label: $actual"
  else
    echo "FAIL  $label: expected $expected, got $actual"
    failures=$((failures + 1))
  fi
}

psql_db() {
  compose exec -T db psql -U bcml -d bcml -v ON_ERROR_STOP=1 "$@"
}

post_status() {
  curl -sS -o /dev/null -w '%{http_code}' -X POST "$REPORT_URL" \
    -H 'Content-Type: application/json' --data-binary "@$CONTRACT" "$@"
}

echo "== docker compose up -d --build"
compose up -d --build --quiet-pull

echo "== health"
health=""
for _ in $(seq 1 90); do
  health="$(curl -sS --max-time 2 "$BASE/actuator/health" 2>/dev/null || true)"
  [[ "$health" == *'"status":"UP"'* ]] && break
  sleep 2
done
echo "GET $BASE/actuator/health -> $health"
if [[ "$health" != *'"status":"UP"'* ]]; then
  echo "FAIL  app did not become healthy; last app log lines:"
  compose logs --tail 40 app
  exit 1
fi
expect "health without a token" '{"status":"UP"}' "$health"

echo "== requests the server must refuse"
expect "POST report without a token" 401 "$(post_status)"
expect "POST report with a wrong token" 401 "$(post_status -H 'Authorization: Bearer wrong-token')"

NODE="verify-$(date +%s)"
echo "== register node $NODE (the database gets only the SHA-256 of its token)"
python3 "$ROOT/tools/new_node_token.py" "$NODE" --token-file "$WORK/token" > "$WORK/node.sql"
cat "$WORK/node.sql"
psql_db -q < "$WORK/node.sql"

cat > "$WORK/agent.ini" <<INI
[agent]
server_url = $REPORT_URL
token_file = $WORK/token
allow_insecure_http = true
INI

echo "== agent --once, twice (body hostname is $(hostname), node identity comes from the token)"
for run in 1 2; do
  agent_rc=0
  PYTHONPATH="$ROOT/agent" python3 -m bcml_agent --config "$WORK/agent.ini" --once || agent_rc=$?
  expect "agent run $run exit code" 0 "$agent_rc"
  sleep 1
done

echo "== replay one fixed report twice (contract/agent-report/v1/minimal-heartbeat.json, body hostname ws2)"
for attempt in first second; do
  curl -sS -X POST "$REPORT_URL" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $(cat "$WORK/token")" --data-binary "@$CONTRACT" > "$WORK/receipt-$attempt.json"
  psql_db -At -c "SELECT sample.received_at FROM sample JOIN node ON node.id = sample.node_id
                   WHERE node.name = '$NODE' AND sample.observed_at = '2026-11-15T02:10:02.031Z'" \
    > "$WORK/received-at-$attempt"
  echo "$attempt receipt: $(cat "$WORK/receipt-$attempt.json"), received_at of that row: $(cat "$WORK/received-at-$attempt")"
done
expect "first replay receipt" '{"accepted_samples":1,"duplicate_samples":0,"rejected":0}' "$(cat "$WORK/receipt-first.json")"
expect "second replay receipt" '{"accepted_samples":0,"duplicate_samples":1,"rejected":0}' "$(cat "$WORK/receipt-second.json")"
expect "received_at after the duplicate" "$(cat "$WORK/received-at-first")" "$(cat "$WORK/received-at-second")"

echo "== rows stored for $NODE"
psql_db -c "SELECT node.name, sample.observed_at, sample.received_at, sample.boot_id
              FROM sample JOIN node ON node.id = sample.node_id
             WHERE node.name = '$NODE' ORDER BY sample.observed_at"
count() {
  psql_db -At -c "SELECT count(*) FROM sample JOIN node ON node.id = sample.node_id WHERE $1"
}
expect "rows for $NODE (2 agent runs + 1 replayed report sent twice)" 3 "$(count "node.name = '$NODE'")"
expect "rows for $NODE carrying this machine's boot_id" 2 \
  "$(count "node.name = '$NODE' AND sample.boot_id = '$(cat /proc/sys/kernel/random/boot_id)'")"
echo "total rows in sample: $(count true)"

if [[ $failures -gt 0 ]]; then
  echo "FAILED: $failures expectation(s) not met"
  exit 1
fi
echo "PASSED: all expectations met. The stack is still up at $BASE ($0 --down removes it and its volume)."

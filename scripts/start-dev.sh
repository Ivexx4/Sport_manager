#!/usr/bin/env bash
#
# start-dev.sh — Arranca o backend DEV com validação de prontidão e seed opcional.
#
# EQUivalente ao start-dev.ps1 adaptado para Linux/Bash.
#
# REQUISITOS:
#   - JDK 25 (JAVA_HOME apontado para JDK 25 ou java no PATH)
#   - Docker (com docker compose plugin)
#   - mvnw (Maven wrapper incluído na raiz)
#
# UTILIZAÇÃO:
#   ./scripts/start-dev.sh               # apenas arranca o backend
#   ./scripts/start-dev.sh --seed        # arranca e aplica o seed após validação
#   ./scripts/start-dev.sh --port 9090   # porta alternativa
#   ./scripts/start-dev.sh --timeout 300 # timeout maior (segundos)
#
# NOTAS:
#   - Não termina instâncias Docker existentes; não apaga volumes.
#   - Ctrl+C termina apenas o processo Java iniciado por este script; o Docker mantém-se ativo.
#   - Se a porta já estiver ocupada, o lançamento é recusado antes de alterar qualquer serviço.

set -euo pipefail

# ── Defaults & parsing ────────────────────────────────────────────────
SEED=false
PORT=8080
TIMEOUT_SECONDS=180
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --seed)       SEED=true; shift ;;
        --port)       PORT="$2"; shift 2 ;;
        --timeout)    TIMEOUT_SECONDS="$2"; shift 2 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

# ── Guards ────────────────────────────────────────────────────────────
if ! command -v docker &>/dev/null; then
    echo "ERRO: docker não encontrado no PATH." >&2; exit 1
fi
if ! command -v java &>/dev/null; then
    echo "ERRO: java não encontrado no PATH." >&2; exit 1
fi

JAVA_BIN="$(command -v java)"
JAVA_VER_RAW="$("$JAVA_BIN" --version 2>&1 || true)"
if [[ ! "$JAVA_VER_RAW" =~ ^(openjdk|java)\ 25([.\s]|$) ]]; then
    echo "ERRO: Este lançamento requer JDK 25. JAVA_HOME=$(realpath "$JAVA_HOME" 2>/dev/null || echo não definido)." >&2
    echo "Versão encontrada: ${JAVA_VER_RAW}" >&2
    exit 1
fi

# ── Helpers ───────────────────────────────────────────────────────────
assert_port_free() {
    local port=$1
    if lsof -ti:"$port" -sTCP:LISTEN &>/dev/null; then
        local pids
        pids="$(lsof -ti:"$port" -sTCP:LISTEN | tr '\n' ',' | sed 's/,$//')"
        echo "ERRO: A porta $port está ocupada (PID(s): $pids). termine a instância existente antes de repetir." >&2
        exit 1
    fi
}

docker_compose_up() {
    assert_port_free "$PORT"
    docker compose --project-directory "$ROOT_DIR" -f "$ROOT_DIR/docker-compose.yml" up -d db mailpit
}

wait_for_db() {
    local deadline=$(( SECONDS + TIMEOUT_SECONDS ))
    while ! docker compose --project-directory "$ROOT_DIR" -f "$ROOT_DIR/docker-compose.yml" exec -T db pg_isready -U postgres -d volei &>/dev/null; do
        if (( SECONDS >= deadline )); then
            echo "ERRO: A BD DEV não ficou disponível dentro do prazo." >&2
            exit 1
        fi
        sleep 1
    done
}

build_backend() {
    pushd "$ROOT_DIR"
    ./mvnw -B -ntp -DskipTests package
    popd
}

launch_java() {
    local jar_count
    jar_count="$(find "$ROOT_DIR/target" -maxdepth 1 -name '*.jar' -type f | wc -l)"
    if (( jar_count != 1 )); then
        echo "ERRO: Esperado exatamente 1 JAR em target (encontrados: $jar_count). Resolva a ambiguidade." >&2
        exit 1
    fi

    local jar_path
    jar_path="$(find "$ROOT_DIR/target" -maxdepth 1 -name '*.jar' -type f)"
    local stdout_log="$ROOT_DIR/target/dev-${PORT}.stdout.log"
    local stderr_log="$ROOT_DIR/target/dev-${PORT}.stderr.log"

    assert_port_free "$PORT"

    nohup java \
        -XX:MaxRAMPercentage=75 \
        -jar "$jar_path" \
        --spring.profiles.active=dev \
        --server.port="$PORT" \
        --server.address=127.0.0.1 \
        --spring.datasource.url=jdbc:postgresql://localhost:5432/volei \
        --spring.datasource.username=volei_app \
        --spring.flyway.url=jdbc:postgresql://localhost:5432/volei \
        --spring.flyway.user=volei_migrator \
        --spring.flyway.enabled=true \
        --spring.flyway.locations=classpath:db/migration \
        >"$stdout_log" 2>"$stderr_log" &

    echo "$!" > "$ROOT_DIR/target/dev-${PORT}.pid"
    echo "Backend PID: $!"
}

wait_for_ready() {
    local url="http://127.0.0.1:${PORT}/api/v1/ping"
    local pid
    pid="$(cat "$ROOT_DIR/target/dev-${PORT}.pid")"
    local deadline=$(( SECONDS + TIMEOUT_SECONDS ))

    while (( SECONDS < deadline )); do
        # Verifica se o processo ainda existe
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "ERRO: O backend terminou antes de ficar pronto. Veja $ROOT_DIR/target/dev-${PORT}.stderr.log" >&2
            exit 1
        fi
        if curl -sf --max-time 5 "$url" | grep -q '"status":"ok"'; then
            return 0
        fi
        sleep 1
    done
    echo "ERRO: A aplicação ou as migrações não ficaram prontas dentro do prazo." >&2
    echo "Consulte $ROOT_DIR/target/dev-${PORT}.stdout.log e $ROOT_DIR/target/dev-${PORT}.stderr.log" >&2
    exit 1
}

run_seed() {
    local url="http://127.0.0.1:${PORT}/api/v1/ping"
    local pid
    pid="$(cat "$ROOT_DIR/target/dev-${PORT}.pid")"

    # Validação prévia
    if ! curl -sf --max-time 5 "$url" | grep -q '"status":"ok"' || ! kill -0 "$pid" 2>/dev/null; then
        echo "ERRO: Implantação antiga ou backend indisponível; o seed não foi executado." >&2
        exit 1
    fi

    # Copia o seed para o container
    docker compose --project-directory "$ROOT_DIR" -f "$ROOT_DIR/docker-compose.yml" cp \
        "$ROOT_DIR/docker/dev-seed.sql" db:/tmp/dev-seed.sql

    # Revalida após cópia
    if ! curl -sf --max-time 5 "$url" | grep -q '"status":"ok"' || ! kill -0 "$pid" 2>/dev/null; then
        echo "ERRO: backend perdeu disponibilidade durante cópia do seed. Abortando." >&2
        exit 1
    fi

    # Aplica o seed como transação única
    docker compose --project-directory "$ROOT_DIR" -f "$ROOT_DIR/docker-compose.yml" exec -T db \
        psql -U postgres -d volei \
        -v ON_ERROR_STOP=1 --single-transaction \
        -f /tmp/dev-seed.sql

    # Validação final
    if ! curl -sf --max-time 5 "$url" | grep -q '"status":"ok"' || ! kill -0 "$pid" 2>/dev/null; then
        echo "ERRO: backend indisponível após seed. Consulte os logs." >&2
        exit 1
    fi

    echo "Seed DEV aplicado com sucesso."
}

cleanup() {
    local pid_file="$ROOT_DIR/target/dev-${PORT}.pid"
    if [[ -f "$pid_file" ]]; then
        local pid
        pid="$(cat "$pid_file")"
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
        fi
        rm -f "$pid_file"
    fi
}

trap cleanup EXIT INT TERM

# ── Main ──────────────────────────────────────────────────────────────
echo "=== Iniciar backend DEV na porta $PORT ==="
echo "Root: $ROOT_DIR"

docker_compose_up
wait_for_db
build_backend
launch_java
wait_for_ready

if $SEED; then
    run_seed
fi

echo "Backend DEV pronto em http://localhost:${PORT}"
echo "Logs: target/dev-${PORT}.stdout.log / target/dev-${PORT}.stderr.log"
echo "Ctrl+C para terminar (o Docker mantém-se ativo)."

# Aguarda o processo Java até ser terminado pelo utilizador
wait $(cat "$ROOT_DIR/target/dev-${PORT}.pid")
exit_code=$?

if (( exit_code != 0 )); then
    echo "ERRO: O backend terminou inesperadamente com código $exit_code." >&2
    echo "Consulte os logs de arranque." >&2
    exit "$exit_code"
fi

#!/usr/bin/env bash
#
# restart.sh — Reinicia o backend DEV (reconstrói JAR, reinicia processo).
# Útil durante desenvolvimento: preserva a BD Docker mas força rebuild.
#
# OPÇÕES:
#   ./scripts/restart.sh              # reconstrói e reinicia sem seed
#   ./scripts/restart.sh --seed       # reconstrói, reinicia e aplica seed
#   ./scripts/restart.sh --clean      # limpa target/ antes de reconstruir
#
# NÃO toca na base de dados nem nos volumes Docker.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$ROOT_DIR/target/dev-8080.pid"
PORT="${DEV_PORT:-8080}"
PID_FILE_PORTED="$ROOT_DIR/target/dev-${PORT}.pid"

SEED=false
CLEAN=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --seed) SEED=true; shift ;;
        --clean) CLEAN=true; shift ;;
        --port) PORT="$2"; PID_FILE_PORTED="$ROOT_DIR/target/dev-${PORT}.pid"; shift 2 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

echo "=== Restart backend (porta $PORT) ==="

# Para processo existente, se houver
if [[ -f "$PID_FILE_PORTED" ]]; then
    old_pid="$(cat "$PID_FILE_PORTED")"
    if kill -0 "$old_pid" 2>/dev/null; then
        echo "Parando PID $old_pid..."
        kill "$old_pid" 2>/dev/null || true
        wait "$old_pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE_PORTED"
fi

# Limpeza opcional
if $CLEAN; then
    echo "Clean target/ ..."
    pushd "$ROOT_DIR"
    ./mvnw clean -q
    popd
fi

# Reconstruir
echo "Reconstruindo backend..."
pushd "$ROOT_DIR"
./mvnw -B -ntp -DskipTests package
popd

# Reiniciar com o mesmo comando que start-dev.sh
SH_ARGS=()
[[ "$SEED" == "true" ]] && SH_ARGS+=(--seed)
[[ "$PORT" != "8080" ]] && SH_ARGS+=(--port "$PORT")

exec bash "$(dirname "${BASH_SOURCE[0]}")/start-dev.sh" "${SH_ARGS[@]}"

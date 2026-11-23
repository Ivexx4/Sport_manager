#!/usr/bin/env bash
#
# dev.sh — Script de conveniência para iniciar todo o ecossistema local de desenvolvimento.
#
# Utiliza tmux para organizar sessões separadas (backend + frontend + Docker).
#
# UTILIZAÇÃO:
#   ./scripts/dev.sh                    # inicia tudo (requer tmux)
#   ./scripts/dev.sh --no-tmux          # executa tudo no terminal atual (sequencial)
#   ./scripts/dev.sh --no-backend       # apenas frontend
#   ./scripts/dev.sh --no-frontend      # apenas backend
#   ./scripts/dev.sh --seed             # backend com seed
#   ./scripts/dev.sh --help             # esta ajuda
#
# PRÉ-REQUISITOS:
#   - Docker Desktop (motor running)
#   - JDK 25 (JAVA_HOME ou java no PATH)
#   - Node.js 20+
#   - tmux (opcional, necessário para modo default)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

USE_TMUX=true
SEED=false
WITH_BACKEND=true
WITH_FRONTEND=true

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-tmux)    USE_TMUX=false; shift ;;
        --seed)       SEED=true; shift ;;
        --no-backend) WITH_BACKEND=false; shift ;;
        --no-frontend) WITH_FRONTEND=false; shift ;;
        --help|-h)
            bash "$SCRIPT_DIR/dev.sh" --no-tmux --help
            exit 0
            ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

# ── Funções auxiliares ────────────────────────────────────────────────
check_deps() {
    local missing=0
    for cmd in docker java node npm; do
        if ! command -v "$cmd" &>/dev/null; then
            echo "ERRO: comando '$cmd' não encontrado no PATH." >&2
            missing=1
        fi
    done
    exit "$missing"
}

start_docker() {
    echo "Iniciando infra-estrutura Docker..."
    docker compose --project-directory "$ROOT_DIR" -f "$ROOT_DIR/docker-compose.yml" up -d
    docker compose --project-directory "$ROOT_DIR" -f "$ROOT_DIR/docker-compose.yml" ps
}

stop_docker() {
    echo "Parando infra-estrutura Docker..."
    docker compose --project-directory "$ROOT_DIR" -f "$ROOT_DIR/docker-compose.yml" down
}

# ── Main ──────────────────────────────────────────────────────────────
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    cat <<'EOF'
Uso: ./scripts/dev.sh [OPÇÕES]

Opções:
  --no-tmux      executa tudo no terminal atual (sem tmux)
  --seed         aplica dados de demonstração após arranque do backend
  --no-backend   não inicia o backend
  --no-frontend  não inicia o frontend
  --help         mostra esta ajuda

Exemplos:
  ./scripts/dev.sh              # inicia backend + frontend com tmux
  ./scripts/dev.sh --no-tmux    # executa no terminal atual
  ./scripts/dev.sh --seed       # com seed e tmux

Pré-requisitos: Docker, JDK 25, Node.js 20+, tmux (opcional).
EOF
    exit 0
fi

check_deps

# Modo tmux vs. execução direta
if $USE_TMUX; then
    if ! command -v tmux &>/dev/null; then
        echo "ERRO: tmux não encontrado. Use --no-tmux ou instale o tmux." >&2
        exit 1
    fi

    SESSION="gestao-clubes"
    WINDOW="dev"

    # Cria sessão tmux ou reutiliza
    if tmux has-session -t "$SESSION" 2>/dev/null; then
        echo "Sessão tmux '$SESSION' já existe. A reconnectar..."
        tmux attach -t "$SESSION"
        exit 0
    fi

    tmux new-session -d -s "$SESSION" -n "$WINDOW"

    # Painel esquerdo: backend
    tmux split-window -h -t "$SESSION:$WINDOW"
    tmux select-layout -t "$SESSION:$WINDOW" left-right

    # Painel inferior direito: frontend
    tmux split-window -v -t "$SESSION:$WINDOW.1"
    tmux select-layout -t "$SESSION:$WINDOW" main-vertical

    # Configurar comandos por painel
    # 0 = esquerda (backend), 1 = topo-direita, 2 = baixo-direita
    BACKEND_CMD="./scripts/start-dev.sh"
    [[ "$SEED" == "true" ]] && BACKEND_CMD="$BACKEND_CMD --seed"

    FRONTEND_CMD="./scripts/start-frontend.sh"

    tmux send-keys -t "$SESSION:$WINDOW.0" "docker compose -f docker-compose.yml up -d" Enter
    sleep 2
    tmux send-keys -t "$SESSION:$WINDOW.0" "$BACKEND_CMD" Enter

    tmux send-keys -t "$SESSION:$WINDOW.1" "$FRONTEND_CMD" Enter

    tmux attach -t "$SESSION"
else
    # Modo sequencial no terminal atual
    if $WITH_BACKEND; then
        start_docker
        echo ""
        echo "=== Arrancando backend ==="
        SH_ARGS=()
        $SEED && SH_ARGS+=(--seed)
        bash "$SCRIPT_DIR/start-dev.sh" "${SH_ARGS[@]}"
    fi

    if $WITH_FRONTEND; then
        echo ""
        echo "=== Arrancando frontend ==="
        bash "$SCRIPT_DIR/start-frontend.sh"
    fi
fi

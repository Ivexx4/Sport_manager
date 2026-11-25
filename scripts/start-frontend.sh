#!/usr/bin/env bash
#
# start-frontend.sh — Arranca o frontend DEV (Vite) ou faz build de produção.
#
# UTILIZAÇÃO:
#   ./scripts/start-frontend.sh          # arranca Vite em modo desenvolvimento
#   ./scripts/start-frontend.sh --build  # compila para produção (dist/)
#   ./scripts/start-frontend.sh --lint   # executa ESLint
#   ./scripts/start-frontend.sh --format # formata com Prettier
#   ./scripts/start-frontend.sh --test   # executa Jest
#   ./scripts/start-frontend.sh --preview # serve o build de produção localmente
#
# Variáveis de ambiente opcionais:
#   VITE_HOST=0.0.0.0                   # interface onde o Vite escuta (default: 0.0.0.0)
#   VITE_PORT=5174                      # porta do Vite (default: 5174)
#   VITE_API_TARGET=http://localhost:8080  # alvo do proxy /api (default: http://localhost:8080)

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend"

cd "$FRONTEND_DIR"

# Garante node_modules instalado
if [[ ! -d "node_modules" ]]; then
    echo "Instalando dependências do frontend..."
    npm ci
fi

ACTION="dev"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --build)   ACTION="build"; shift ;;
        --lint)    ACTION="lint"; shift ;;
        --format)  ACTION="format"; shift ;;
        --test)    ACTION="test"; shift ;;
        --preview) ACTION="preview"; shift ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

case "$ACTION" in
    dev)
        echo "=== Frontend DEV (Vite) ==="
        export VITE_HOST="${VITE_HOST:-0.0.0.0}"
        export VITE_PORT="${VITE_PORT:-5174}"
        export VITE_API_TARGET="${VITE_API_TARGET:-http://localhost:8080}"
        echo "Disponível em http://${VITE_HOST}:${VITE_PORT}"
        echo "Proxy /api -> ${VITE_API_TARGET}"
        npm run dev
        ;;
    build)
        echo "=== Frontend BUILD ==="
        npm run build
        echo "Build concluído em dist/"
        ;;
    lint)
        echo "=== Frontend LINT ==="
        npm run lint
        ;;
    format)
        echo "=== Frontend FORMAT ==="
        npm run format
        ;;
    test)
        echo "=== Frontend TEST ==="
        npm run test
        ;;
    preview)
        echo "=== Frontend PREVIEW ==="
        npm run preview
        ;;
esac

#!/usr/bin/env bash
#
# install.sh — Instala todas as dependências do projeto (backend, frontend e infra).
#
# Útil na primeira vez que clones o repositório ou depois de um pull que altere
# pom.xml / package-lock.json.
#
# OPÇÕES:
#   ./scripts/install.sh                    # instala tudo (Maven + npm + Docker)
#   ./scripts/install.sh --skip-docker      # não arranca o Docker (só Maven + npm)
#   ./scripts/install.sh --skip-npm         # apenas Maven e Docker
#   ./scripts/install.sh --skip-maven       # apenas npm e Docker
#   ./scripts/install.sh --docker-only      # só inicia os containers
#   ./scripts/install.sh --deps-only        # só Maven + npm (sem Docker)
#
# RECOMENDADO executar este script logo após clonar o repositório.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}")"

SKIP_DOCKER=false
SKIP_NPM=false
SKIP_MAVEN=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-docker) SKIP_DOCKER=true; shift ;;
        --skip-npm)    SKIP_NPM=true; shift ;;
        --skip-maven)  SKIP_MAVEN=true; shift ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

# ── Verificações mínimas ──────────────────────────────────────────────
missing=()
for cmd in java mvn node npm; do
    if ! command -v "$cmd" &>/dev/null; then
        missing+=("$cmd")
    fi
done
if (( ${#missing[@]} > 0 )); then
    echo "ERRO: comandos ausentes no PATH: ${missing[*]}" >&2
    echo "Instala JDK 25, Maven e Node.js 20+ antes de correr $SCRIPT_NAME." >&2
    exit 1
fi

if ! $SKIP_DOCKER && ! command -v docker &>/dev/null; then
    echo "AVISO: docker não encontrado no PATH. A infraestrutura Docker será ignorada." >&2
    SKIP_DOCKER=true
fi

# ── Funções ───────────────────────────────────────────────────────────
install_maven() {
    echo ""
    echo "=== Backend: Maven — download de dependências ==="
    pushd "$ROOT_DIR"
    ./mvnw -B -ntp dependency:resolve dependency:go-offline
    popd
}

install_npm() {
    echo ""
    echo "=== Frontend: npm install ==="
    pushd "$ROOT_DIR/frontend"
    if [[ -f "package-lock.json" ]]; then
        npm ci --prefer-offline
    else
        npm install
    fi
    popd
}

start_docker() {
    echo ""
    echo "=== Infra: Docker compose up -d ==="
    pushd "$ROOT_DIR"
    docker compose -f docker-compose.yml up -d
    docker compose -f docker-compose.yml ps
    popd
}

print_summary() {
    echo ""
    echo "=== Resumo da instalação ==="
    echo "JDK   : $(java --version 2>&1 | head -1)"
    echo "Maven : $(mvn --version 2>&1 | head -1)"
    echo "Node  : $(node --version 2>&1 || echo '?')"
    echo "NPM   : $(npm --version 2>&1 || echo '?')"
    if ! $SKIP_DOCKER; then
        echo "Docker: $(docker --version 2>&1 || echo 'não disponível')"
        if docker info &>/dev/null 2>&1; then
            local running
            running="$(docker compose -f "$ROOT_DIR/docker-compose.yml" -p gestao_clubes ps --format table 2>/dev/null | grep -c Up || true)"
            echo "Containers up: $running"
        fi
    fi
}

# ── Execução ──────────────────────────────────────────────────────────
echo "Projeto: $ROOT_DIR"
echo "Data   : $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

if ! $SKIP_MAVEN; then
    install_maven
fi

if ! $SKIP_NPM; then
    install_npm
fi

if ! $SKIP_DOCKER; then
    start_docker
fi

print_summary

echo ""
echo "Instalação concluída com sucesso."
echo ""
echo "Próximos passos:"
echo "  ./scripts/start-dev.sh           # arranca o backend DEV"
echo "  ./scripts/start-frontend.sh      # arranca o frontend (outro terminal)"
echo "  ./scripts/dev.sh                 # ou usa o dev.sh para lançar ambos em tmux"

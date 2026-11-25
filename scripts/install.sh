#!/usr/bin/env bash
#
# install.sh — Instala dependências do SO, projeto e infraestrutura.
#
# O script deteta automaticamente a distribuição Linux e instala:
#   - Eclipse Temurin JDK 25           (JAVA_HOME configurado)
#   - Maven 3.9+                       (mvnw já existe no repositório)
#   - Node.js 20 LTS                   (via NodeSource)
#   - Docker + docker-compose plugin   (opcional, com --skip-docker)
# Em seguida instala as depêndencias do projeto:
#   - Backend  : mvn dependency:go-offline
#   - Frontend : npm ci
#
# OPÇÕES:
#   ./scripts/install.sh                    # instala tudo (SO + projeto)
#   ./scripts/install.sh --skip-so          # não instala pacotes do sistema
#   ./scripts/install.sh --skip-docker      # não instala nem arranca Docker
#   ./scripts/install.sh --dry-run          # mostra o que seria instalado sem executar
#
# REQUER: sudo ou execução como root para instalar pacotes do sistema.
# Se falhar a auto-instalação de um pacote, exibe instruções manuais.

set -euo pipefail

# ── Cores ─────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
info()    { echo -e "${CYAN}ℹ  $*${NC}"; }
ok()      { echo -e "${GREEN}✔  $*${NC}"; }
warn()    { echo -e "${YELLOW}⚠  $*${NC}" >&2; }
err()     { echo -e "${RED}✘  $*${NC}" >&2; }
section() { echo -e "\n${BOLD}$*${NC}"; }

# ── Flags ─────────────────────────────────────────────────────────────
SKIP_SO=false
SKIP_DOCKER=false
DRY_RUN=false

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}")"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-so)    SKIP_SO=true;    shift ;;
        --skip-docker) SKIP_DOCKER=true; shift ;;
        --dry-run)    DRY_RUN=true;    shift ;;
        *) err "Opção desconhecida: $1"; exit 1 ;;
    esac
done

# ── Detetar distribuição ─────────────────────────────────────────────
detect_os() {
    if [[ -f /etc/os-release ]]; then
        # shellcheck disable=SC1090
        source /etc/os-release
        OS_ID="${ID,,}"
        OS_LIKE="${ID_LIKE,,}"
        OS_VER="${VERSION_ID:-}"
    elif [[ -x /bin/distro-info ]]; then
        OS_ID="ubuntu"
        OS_LIKE="debian"
        OS_VER=$(/bin/distro-info -r)
    else
        OS_ID="unknown"
        OS_LIKE=""
        OS_VER=""
    fi

    case "$OS_ID" in
        ubuntu|debian) PKG_MANAGER="apt";;
        fedora|rhel|centos|rocky|almalinux) PKG_MANAGER="dnf";;
        arch|manjaro|endeavouros) PKG_MANAGER="pacman";;
        opensuse*|sles) PKG_MANAGER="zypper";;
        *) PKG_MANAGER="unknown";;
    esac
}

is_debian_based()  { [[ "$PKG_MANAGER" == "apt" ]]; }
is_rhel_based()    { [[ "$PKG_MANAGER" == "dnf" || "$PKG_MANAGER" == "yum" ]]; }
is_arch_based()    { [[ "$PKG_MANAGER" == "pacman" ]]; }
is_zypper_based()  { [[ "$PKG_MANAGER" == "zypper" ]]; }

# ── Verificar se comando está instalado ───────────────────────────────
cmd_exists() { command -v "$1" &>/dev/null; }

# ── Verificar permissão sudo ─────────────────────────────────────────
may_sudo() {
    if [[ "$(id -u)" -eq 0 ]]; then return 0; fi       # já é root
    if cmd_exists sudo && sudo -n true &>/dev/null 2>&1; then return 0; fi  # sudo sem password
    warn "Sem permissão de root/sudo — as instalações do sistema falharão." >&2
    return 1
}

sudo_cmd() {
    if [[ "$(id -u)" -eq 0 ]]; then "$@"; else sudo "$@"; fi
}

# ── Instalação de JDK 25 (Eclipse Temurin) ───────────────────────────
install_jdk() {
    local current_ver existing_java java_home candidate_java
    if cmd_exists java; then
        current_ver="$(java -version 2>&1 | head -1)"
        if [[ "$current_ver" =~ ^(openjdk|java)\ 25([.\s]|$) ]]; then
            ok "JDK 25 encontrado: $current_ver"
            export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
            return 0
        fi
        section "${YELLOW}Java presente mas não é JDK 25 ($current_ver)${NC} — a instalar Eclipse Temurin 25..."
    else
        section "${YELLOW}JDK 25 ausente — a instalar Eclipse Temurin 25...${NC}"
    fi

    may_sudo || return 1

    case "$PKG_MANAGER" in
        apt)
            if ! cmd_exists gpg; then
                info "A instalar gpg (requerido pelo repositório Adoptium)..."
                sudo_cmd apt-get update -qq
                sudo_cmd apt-get install -y --no-install-recommends gnupg dpkg-dev ca-certificates
            fi
            sudo_cmd mkdir -p /etc/apt/keyrings
            curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
                | sudo_cmd tee /etc/apt/keyrings/adoptium.asc >/dev/null
            echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb \
                $(. /etc/os-release && echo "$VERSION_CODENAME") main" \
                | sudo_cmd tee /etc/apt/sources.list.d/adoptium.list
            sudo_cmd apt-get update -qq
            sudo_cmd apt-get install -y temurin-25-jdk
            ok "Eclipse Temurin JDK 25 instalado."
            export JAVA_HOME="/usr/lib/jvm/temurin-25-jdk-amd64"
            ;;
        dnf|yum)
            sudo_cmd "$PKG_MANAGER" install -y temurin-25-jdk
            ok "Eclipse Temurin JDK 25 instalado."
            export JAVA_HOME="/usr/lib/jvm/temurin-25-jdk-x64"
            ;;
        pacman)
            sudo_cmd "$PKG_MANAGER" -S --noconfirm jdk25
            ok "JDK 25 instalado via pacman."
            export JAVA_HOME="$(readlink -f /usr/bin/java | xargs dirname | xargs dirname)"
            ;;
        zypper)
            sudo_cmd "$PKG_MANAGER" install -y java-25-temurin
            ok "Eclipse Temurin JDK 25 instalado."
            export JAVA_HOME="/usr/lib/jvm/java-25-temurin"
            ;;
        *)
            warn "Distribuição '$OS_ID' não suportada para auto-instalação do JDK." >&2
            _jdk_manual_install
            return 1
            ;;
    esac

    # Adicionar ao .bashrc / .zshrc
    _export_java_home
    return 0
}

_jdk_manual_install() {
    cat >&2 <<'EOF'

Instalação manual do JDK 25 (Eclipse Temurin):
  https://adoptium.net/temurin/releases/?version=25

Depois de descarregar e extrair:
  export JAVA_HOME=$HOME/.sdkman/candidates/java/current
  export PATH=$JAVA_HOME/bin:$PATH

Ou usando SDKMAN (recomendado se disponível):
  sdk install java 25.0.0-tem
  sdk use  java 25.0.0-tem
EOF
}

_export_java_home() {
    local rc_file="${HOME}/.bashrc"
    [[ -f "${HOME}/.zshrc" ]] && rc_file="${HOME}/.zshrc"
    local export_line="export JAVA_HOME=${JAVA_HOME}"
    if grep -qF "$export_line" "$rc_file" 2>/dev/null; then
        return 0
    fi
    echo "" >> "$rc_file"
    echo "# Gerado por install.sh — Gestão Clubes" >> "$rc_file"
    echo "export JAVA_HOME=${JAVA_HOME}" >> "$rc_file"
    echo "export PATH=\$JAVA_HOME/bin:\$PATH" >> "$rc_file"
    ok "JAVA_HOME adicionado a $rc_file (restart do terminal necessário)."
}

# ── Instalação de Maven ──────────────────────────────────────────────
install_maven() {
    if cmd_exists mvn; then
        ok "Maven encontrado: $(mvn --version 2>&1 | head -1)"
        return 0
    fi
    section "${YELLOW}Maven ausente — a instalar...${NC}"
    may_sudo || return 1

    case "$PKG_MANAGER" in
        apt)   sudo_cmd apt-get install -y maven;;
        dnf)   sudo_cmd dnf install -y maven;;
        yum)   sudo_cmd yum install -y maven;;
        pacman)sudo_cmd "$PKG_MANAGER" -S --noconfirm apache-maven;;
        zypper)sudo_cmd zypper install -y maven;;
        *)
            warn "Distribuição '$OS_ID' não suportada para auto-instalação do Maven." >&2
            _mvn_manual_install
            return 1
            ;;
    esac
    ok "Maven instalado: $(mvn --version 2>&1 | head -1)"
    return 0
}

_mvn_manual_install() {
    cat >&2 <<'EOF'

Instalação manual do Maven:
  https://maven.apache.org/download.cgi

Exemplo com wget:
  MVN_VER="3.9.9"
  wget -qO- "https://archive.apache.org/dist/maven/maven-3/${MVN_VER}/binaries/apache-maven-${MVN_VER}-bin.tar.gz" \
    | sudo tar -xz -C /opt
  export PATH=/opt/apache-maven-${MVN_VER}/bin:$PATH
EOF
}

# ── Instalação de Node.js 20 LTS ─────────────────────────────────────
install_node() {
    if cmd_exists node; then
        local ver
        ver="$(node --version 2>/dev/null | sed 's/v//')"
        if [[ "$ver" =~ ^2[0-9]\. ]]; then
            ok "Node.js encontrado: v$ver"
            return 0
        fi
        warn "Node.js v$ver encontrado (precisa de v20+). Tentando atualizar…"
    fi

    section "${YELLOW}Node.js 20+ ausente ou desatualizado — a instalar via NodeSource…${NC}"
    may_sudo || return 1

    case "$PKG_MANAGER" in
        apt)
            curl -fsSL https://deb.nodesource.com/setup_20.x \
                | sudo_cmd bash -
            sudo_cmd apt-get install -y nodejs
            ;;
        dnf|yum)
            sudo_cmd "$PKG_MANAGER" install -y nodejs
            # No Fedora/RHEL recentes o repo padrão já traz Node 20+
            ;;
        pacman)
            sudo_cmd "$PKG_MANAGER" -S --noconfirm nodejs
            ;;
        zypper)
            sudo_cmd zypper install -y nodejs20
            ;;
        *)
            warn "Distribuição '$OS_ID' não suportada para auto-instalação do Node.js." >&2
            _node_manual_install
            return 1
            ;;
    esac
    ok "Node.js instalado: $(node --version 2>/dev/null || echo '?')"
    return 0
}

_node_manual_install() {
    cat >&2 <<'EOF'

Instalação manual do Node.js 20 LTS:
  https://github.com/nodesource/distributions#installation-instructions

Exemplo (Linux x64):
  curl -fsSL https://deb.nodesource.com/setup_20.x | sudo bash -
  sudo apt-get install -y nodejs
EOF
}

# ── Instalação de Docker ─────────────────────────────────────────────
install_docker() {
    if cmd_exists docker && docker info &>/dev/null 2>&1; then
        ok "Docker encontrado: $(docker --version 2>&1 || echo '?')"
        if cmd_exists docker && docker compose version &>/dev/null 2>&1; then
            ok "Docker Compose plugin encontrado: $(docker compose version 2>&1 || echo '?')"
        else
            warn "Docker compose plugin em falta." >&2
        fi
        return 0
    fi

    section "${YELLOW}Docker ausente — a instalar...${NC}"
    may_sudo || return 1

    case "$PKG_MANAGER" in
        apt)
            # Remove versões antigas
            for pkg in docker.io docker-doc docker-compose podman-docker containerd runc; do
                sudo_cmd apt-get remove -y "$pkg" 2>/dev/null || true
            done
            sudo_cmd apt-get install -y ca-certificates curl gnupg
            sudo_cmd install -m 0755 -d /etc/apt/keyrings
            curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
                | sudo_cmd gpg --dearmor -o /etc/apt/keyrings/docker.gpg
            chmod a+r /etc/apt/keyrings/docker.gpg
            echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
                https://download.docker.com/linux/$OS_ID \
                $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
                | sudo_cmd tee /etc/apt/sources.list.d/docker.list >/dev/null
            sudo_cmd apt-get update -qq
            sudo_cmd apt-get install -y docker-ce docker-ce-cli containerd.io \
                docker-buildx-plugin docker-compose-plugin
            # Adicionar utilizador ao grupo docker (evita sudo em cada comando)
            if id -nG "$USER" 2>/dev/null | grep -qw docker; then
                :
            else
                sudo_cmd usermod -aG docker "$USER"
                warn "Utilizador '$USER' adicionado ao grupo 'docker'. Restart do terminal recomendado."
            fi
            sudo_cmd systemctl enable --now docker 2>/dev/null || true
            ;;
        dnf|yum)
            sudo_cmd "$PKG_MANAGER" install -y docker docker-compose-plugin
            sudo_cmd systemctl enable --now docker 2>/dev/null || true
            # Remover conflito com podman
            sudo_cmd "$PKG_MANAGER" remove -y podman 2>/dev/null || true
            ;;
        pacman)
            sudo_cmd "$PKG_MANAGER" -S --noconfirm docker docker-compose
            sudo_cmd systemctl enable --now docker
            if id -nG "$USER" 2>/dev/null | grep -qw docker; then
                :
            else
                sudo_cmd usermod -aG docker "$USER"
            fi
            ;;
        zypper)
            sudo_cmd zypper install -y docker docker-compose
            sudo_cmd systemctl enable --now docker
            ;;
        *)
            warn "Distribuição '$OS_ID' não suportada para auto-instalação do Docker." >&2
            _docker_manual_install
            return 1
            ;;
    esac
    ok "Docker instalado: $(docker --version 2>&1 || echo '?')"
    return 0
}

_docker_manual_install() {
    cat >&2 <<'EOF'

Instalação manual do Docker:
  https://docs.docker.com/engine/install/

Exemplo Ubuntu:
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker $USER
EOF
}

# ── Dependências do projeto ─────────────────────────────────────────
install_backend_deps() {
    section "Backend: resolver dependências Maven..."
    pushd "$ROOT_DIR"
    if ! cmd_exists ./mvnw; then
        err "mvnw não encontrado em $ROOT_DIR." >&2
        return 1
    fi
    ./mvnw -B -ntp dependency:resolve dependency:go-offline
    ok "Dependências Maven resolvidas."
    popd
}

install_frontend_deps() {
    section "Frontend: instalar dependências npm..."
    if [[ ! -d "$ROOT_DIR/frontend" ]]; then
        err "Diretório frontend/ não encontrado em $ROOT_DIR." >&2
        return 1
    fi
    pushd "$ROOT_DIR/frontend"
    if [[ -f "package-lock.json" ]]; then
        npm ci --prefer-offline
    else
        npm install
    fi
    ok "Dependências frontend instaladas."
    popd
}

start_docker_services() {
    section "Infraestrutura Docker: arranque dos serviços..."
    pushd "$ROOT_DIR"
    if ! cmd_exists docker || ! docker info &>/dev/null 2>&1; then
        warn "Docker não disponível; infra ignorada." >&2
        popd
        return 0
    fi
    docker compose -f docker-compose.yml up -d
    docker compose -f docker-compose.yml ps
    popd
}

# ── Resumo final ─────────────────────────────────────────────────────
print_summary() {
    section "=== Resumo da instalação ==="
    echo -e "  ${BOLD}Java${NC}:   $(java -version 2>&1 | head -1)   (JAVA_HOME=$JAVA_HOME)"
    echo -e "  ${BOLD}Maven${NC}:  $(mvn --version 2>&1 | head -1)"
    echo -e "  ${BOLD}Node${NC}:   $(node --version 2>/dev/null || echo '?')"
    echo -e "  ${BOLD}npm${NC}:    $(npm --version 2>/dev/null || echo '?')"

    if ! $SKIP_DOCKER && cmd_exists docker && docker info &>/dev/null 2>&1; then
        echo -e "  ${BOLD}Docker${NC}: $(docker --version 2>&1 || echo '?')"
        local running=0
        running="$(docker compose -f "$ROOT_DIR/docker-compose.yml" -p gestao_clubes ps --format json 2>/dev/null \
            | grep -c '"Status":"running"' || true)"
        echo -e "  ${BOLD}Containers${NC}: $running up"
    else
        echo -e "  ${BOLD}Docker${NC}: não disponível"
    fi
}

# ── Execução principal ────────────────────────────────────────────────
main() {
    echo -e "${BOLD}Gestão Clubes — Install Script${NC}"
    echo "Projeto: $ROOT_DIR"
    echo "Data   : $(date '+%Y-%m-%d %H:%M:%S')"
    echo "SO     : ${OS_ID:-detetado}  (${PKG_MANAGER:-?})  release $(lsb_release -ds 2>/dev/null || echo '?')"

    if $DRY_RUN; then
        info "Dry-run: apenas deteção, sem alterações no sistema."
        detect_os
        for item in jdk maven node docker; do
            case "$item" in
                jdk)     [[ ! "$(java -version 2>&1 | head -1)" =~ ^.*(openjdk|java)\ 25 ]] && info "  → JDK 25 seria instalado" || ok "  ✓ JDK 25 presente" ;;
                maven)   cmd_exists mvn && ok "  ✓ Maven presente" || info "  → Maven seria instalado" ;;
                node)    cmd_exists node && ok "  ✓ Node.js presente" || info "  → Node.js seria instalado" ;;
                docker)  cmd_exists docker && docker info &>/dev/null 2>&1 && ok "  ✓ Docker presente" || info "  → Docker seria instalado" ;;
            esac
        done
        return 0
    fi

    detect_os

    # ── SO deps ──────────────────────────────────────────────────────
    if ! $SKIP_SO; then
        install_jdk || { err "Falhou a instalação do JDK."; exit 1; }
        install_maven || { err "Falhou a instalação do Maven."; exit 1; }
        install_node  || { err "Falhou a instalação do Node.js."; exit 1; }
        if ! $SKIP_DOCKER; then
            install_docker || warn "Falhou a instalação do Docker; continuar sem Docker."
        fi
    fi

    # ── Project deps ─────────────────────────────────────────────────
    install_backend_deps
    install_frontend_deps

    # ── Docker services ──────────────────────────────────────────────
    if ! $SKIP_DOCKER; then
        start_docker_services
    fi

    print_summary

    echo ""
    echo -e "${GREEN}${BOLD}Instalação concluída com sucesso!${NC}"
    echo ""
    echo "Próximos passos:"
    echo "  ./scripts/start-dev.sh              # arranca backend DEV"
    echo "  ./scripts/start-frontend.sh         # arranca frontend Vite"
    echo "  ./scripts/dev.sh                    # ambos em tmux"
    echo "  source ~/.bashrc                    # garante JAVA_HOME ativo"
}

main "$@"

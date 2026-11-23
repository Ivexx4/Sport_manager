#!/usr/bin/env bash
#
# split-css.sh — Divide styles.css em ficheiros por bucket temático.
#
# Equivalente a split-css.ps1 adaptado para Linux/Bash.
# Divide o CSS global em subficheiros baseados em padrões de selector:
#   base/, layout/, ui/, overview/, athletes/, teams/, matches/,
#   seasons/, billing/, auth/, responsive/
#
# UTILIZAÇÃO:
#   ./scripts/split-css.sh          # gera/substitui os ficheiros por bucket

set -euo pipefail

SRC="${BASH_SOURCE[0]}"
ROOT_DIR="$(cd "$(dirname "$SRC")/.." && pwd)"
CSS_SRC="$ROOT_DIR/frontend/src/styles.css"
OUT_DIR="$ROOT_DIR/frontend/src/styles"

if [[ ! -f "$CSS_SRC" ]]; then
    echo "ERRO: CSS source não encontrado: $CSS_SRC" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

# Cada linha: PATTERN<TAB>BUCKET
RULES=(
    '^@media'              'responsive'
    '^:root'               'base'
    '^\*'                  'base'
    '^body'                'base'
    '^button'              'base'
    '^input'               'base'
    '^select'              'base'
    '^\.muted'             'base'
    '^\.error-text'        'base'
    '^@keyframes'          'ui'
    '^\.login-'            'auth'
    '^\.ball-decoration'   'auth'
    '^\.button-wide'       'auth'
    '^\.mfa-secret'        'auth'
    '^\.recovery-codes'    'auth'
    '^\.app-shell'         'layout'
    '^\.sidebar'           'layout'
    '^\.brand'             'layout'
    '^\.club-'             'layout'
    '^\.nav-'              'layout'
    '^\.user-'             'layout'
    '^\.logout-button'     'layout'
    '^\.icon-button'       'layout'
    '^\.main-content'      'layout'
    '^\.topbar'            'layout'
    '^\.breadcrumb'        'layout'
    '^\.status-dot'        'layout'
    '^\.content-wrap'      'layout'
    '^\.mobile-only'       'layout'
    '^\.welcome-'          'overview'
    '^\.graphic-'          'overview'
    '^\.ring-'             'overview'
    '^\.light-eyebrow'     'overview'
    '^\.section-title'     'overview'
    '^\.quick-'            'overview'
    '^\.check( |\.|$)'     'overview'
    '^\.panel-note'        'overview'
    '^\.toolbar'           'athletes'
    '^\.search-box'        'athletes'
    '^\.person-'           'athletes'
    '^\.upload-box'        'athletes'
    '^\.team-'             'teams'
    '^\.match-'            'matches'
    '^\.season-'           'seasons'
    '^\.group-'            'seasons'
    '^\.billing-stats'     'billing'
    '^\.amount-'           'billing'
    '^\.page-heading'      'ui'
    '^\.heading-actions'   'ui'
    '^\.button'            'ui'
    '^\.stat-'             'ui'
    '^\.two-col'           'ui'
    '^\.panel'             'ui'
    '^\.tag'               'ui'
    '^\.cards-grid'        'ui'
    '^\.full-card'         'ui'
    '^table'               'ui'
    '^th'                  'ui'
    '^td'                  'ui'
    '^\.row-arrow'         'ui'
    '^\.modal'             'ui'
    '^\.stack'             'ui'
    '^\.form-grid'         'ui'
    '^\.field-help'        'ui'
    '^\.empty-'            'ui'
    '^\.loader'            'ui'
    '^\.toast'             'ui'
    '^\.check-option'      'ui'
    '^\.detail-list'       'ui'
    '^\.clickable-row'     'ui'
    '^\.table-panel'       'ui'
    '^\.table-subtext'     'ui'
)

ORDER=("base" "layout" "ui" "overview" "athletes" "teams" "matches" "seasons" "billing" "auth" "responsive")

declare -A BUCKETS
for b in "${ORDER[@]}"; do BUCKETS[$b]="" ; done

UNMATCHED=()
TOTAL=0

# Lê blocos CSS um a um
i=0
lines=()
while IFS= read -r line; do lines+=("$line"); done < "$CSS_SRC"
n=${#lines[@]}

while (( i < n )); do
    line="${lines[$i]}"
    # Detecta início de bloco: linha que começa com caractere não-espaço e tem { ou ,
    if [[ "$line" =~ ^[[:space:]]*[^[:space:]] ]] && { [[ "$line" =~ \{[[:space:]]*$ ]] || [[ "$line" =~ ,[[:space:]]*$ ]]; }; then
        # Recolhe selector completo
        sel_parts=()
        k=$i
        while (( k < n )); do
            trimmed="$(echo "${lines[$k]}" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
            sel_parts+=("$trimmed")
            if [[ "${lines[$k]}" == *"{"* ]]; then break; fi
            (( k++ )) || true
        done
        # Concatena selector e limpa espaços extras
        selector="$(IFS=' '; echo "${sel_parts[*]}")"
        selector="$(echo "$selector" | sed 's/[[:space:]]\+/ /g; s/ *{ *$//' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"

        # Encontra fecha-chaves
        j=$i
        while (( j < n )) && [[ "${lines[$j]}" != "}"* ]]; do (( j++ )) || true; done
        if (( j >= n )); then
            echo "ERRO: bloco sem fecho na linha $((i+1))" >&2
            exit 1
        fi

        # Junta texto do bloco
        block_text=""
        for (( bi=i; bi<=j; bi++ )); do
            if (( bi > i )); then block_text+=$'\n'; fi
            block_text+="${lines[$bi]}"
        done

        # Classeifica por bucket
        bucket=""
        for (( ri=0; ri<${#RULES[@]}; ri+=2 )); do
            pattern="${RULES[$ri]}"
            bucket_name="${RULES[$ri+1]}"
            if [[ "$selector" =~ $pattern ]]; then
                bucket="$bucket_name"
                break
            fi
        done

        if [[ -z "$bucket" ]]; then
            UNMATCHED+=("$selector")
        else
            if [[ -n "${BUCKETS[$bucket]}" ]]; then
                BUCKETS[$bucket]+=$'\n'"$block_text"
            else
                BUCKETS[$bucket]="$block_text"
            fi
        fi
        TOTAL=$((TOTAL + 1))
        i=$((j + 1))
    else
        (( i++ )) || true
    fi
done

if (( ${#UNMATCHED[@]} > 0 )); then
    echo "SEM CLASSIFICACAO:"
    for u in "${UNMATCHED[@]}"; do echo "  $u"; done
    echo "ERRO: há blocos por classificar" >&2
    exit 1
fi

# Escreve cada bucket num ficheiro separado
for name in "${ORDER[@]}"; do
    content="${BUCKETS[$name]}"
    if [[ -n "$content" ]]; then
        outfile="$OUT_DIR/${name}.css"
        printf '%s\n' "$content" > "$outfile"
        count=$(grep -c '.' "$outfile" 2>/dev/null || echo 0)
        echo "${name}: ${count} linhas"
    fi
done

echo "---"
echo "total: $TOTAL blocos"

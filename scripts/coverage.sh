#!/usr/bin/env bash
#
# coverage.sh — Executa testes com JaCoCo e apresenta a cobertura por package.
#
# Equivalente a coverage.ps1 adaptado para Linux/Bash.
#
# OPÇÕES:
#   ./scripts/coverage.sh                  # corre testes e mostra cobertura
#   ./scripts/coverage.sh --no-tests       # lê apenas o relatório existente
#   ./scripts/coverage.sh --open           # abre o relatório HTML no browser
#   ./scripts/coverage.sh --min-line 70    # falha se cobertura de linhas < 70%
#   ./scripts/coverage.sh --online         # usa Maven sem modo offline
#
# O relatório HTML completo fica em target/site/jacoco/index.html.
# Os limiares por defeito estão no pom.xml (linhas ≥90%, ramos ≥70%).

set -euo pipefail

NO_TESTS=false
OPEN_REPORT=false
MIN_LINE=0
ONLINE_MODE=false
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$ROOT_DIR/target/site/jacoco"
CSV_PATH="$REPORT_DIR/jacoco.csv"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-tests)   NO_TESTS=true; shift ;;
        --open)       OPEN_REPORT=true; shift ;;
        --min-line)   MIN_LINE="$2"; shift 2 ;;
        --online)     ONLINE_MODE=true; shift ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

# ── Helpers ───────────────────────────────────────────────────────────
fmt_pct() {
    # fmt_pct <covered> <total>
    local covered=$1 total=$2
    if (( total <= 0 )); then echo "n/d"; return; fi
    awk "BEGIN { printf \"%.1f%%\", 100 * $covered / $total }"
}

# ── Run tests ─────────────────────────────────────────────────────────
if [[ "$NO_TESTS" == "false" ]]; then
    mvn_args=(-B -ntp)
    [[ "$ONLINE_MODE" == "false" ]] && mvn_args+=(-o)
    mvn_args+=(test)
    echo "A correr: mvn ${mvn_args[*]}"
    mvn "${mvn_args[@]}"
    if (( $? != 0 )); then
        echo "ERRO: Os testes falharam. Sem cobertura fiável." >&2
        exit 1
    fi
fi

if [[ ! -f "$CSV_PATH" ]]; then
    echo "ERRO: Arquivo não encontrado: $CSV_PATH. Corre os testes primeiro (sem --no-tests)." >&2
    exit 1
fi

# ── Parse CSV & compute totals ───────────────────────────────────────
# Colunas do jacoco.csv: PACKAGE,CLASS,LINEMISSED,LINENUMBERS,BRANCHMISSED,BRANCHNUMBERS,...
# Forma mais simples usando awk directly:
read -r line_covered line_missed branch_covered branch_missed <<< $(
    awk -F',' 'NR>1 {
        lc += $9+0; lm += $8+0; bc += $7+0; bm += $6+0
    } END {
        printf "%d %d %d %d", lc, lm, bc, bm
    }' "$CSV_PATH"
)

line_total=$(( line_covered + line_missed ))
branch_total=$(( branch_covered + branch_missed ))

echo ""
echo "=== Cobertura global ==="
echo "Linhas : $line_covered / $line_total ($(fmt_pct $line_covered $line_total))"
echo "Ramos  : $branch_covered / $branch_total ($(fmt_pct $branch_covered $branch_total))"

# ── Por package (ordenado por pior primeira) ─────────────────────────
echo ""
echo "=== Por package (pior primeiro) ==="
awk -F',' 'NR>1 {
    pkg=$1
    covered[pkg] += $9+0
    missed[pkg]  += $8+0
    b_cov[pkg]   += $7+0
    b_miss[pkg]  += $6+0
} END {
    for (p in covered) {
        lt = covered[p] + missed[p]
        bt = b_cov[p] + b_miss[p]
        pct = (lt > 0) ? int(100*covered[p]/lt) : 0
        bpct = (bt > 0) ? int(100*b_cov[p]/bt) : 0
        printf "%s\t%d/%d (%d%%)\t%d/%d (%d%%)\t%d\n", p, covered[p], lt, pct, b_cov[p], bt, bpct, missed[p]
    }
}' "$CSV_PATH" | sort -t$'\t' -k4 -rn | column -t -s$'\t'

# ── Top 15 classes com mais linhas por cobrir ─────────────────────────
echo ""
echo "=== Classes com mais linhas por cobrir (top 15) ==="
awk -F',' 'NR>1 && $8+0 > 0 {
    printf "%d\t%s.%s\t%d/%d\n", $8+0, $1, $2, $9+0, $8+$9
}' "$CSV_PATH" | sort -t$'\t' -k1 -rn | head -15 | awk -F'\t' '{printf "%-50s %s\n", $2, $3}'

# ── Open report ───────────────────────────────────────────────────────
index_html="$REPORT_DIR/index.html"
echo "Relatório HTML: $index_html"

if [[ "$OPEN_REPORT" == "true" && -f "$index_html" ]]; then
    xdg-open "$index_html" 2>/dev/null || open "$index_html" 2>/dev/null || echo "(Abre manualmente: $index_html)"
fi

# ── Threshold check ───────────────────────────────────────────────────
if (( MIN_LINE > 0 )); then
    current_pct=$(awk "BEGIN { printf \"%d\", ( $line_total > 0 ) ? 100*$line_covered/$line_total : 0 }")
    if (( current_pct < MIN_LINE )); then
        echo "Cobertura de linhas ${current_pct}% abaixo do mínimo ${MIN_LINE}%." >&2
        exit 1
    fi
    echo "Cobertura de linhas ${current_pct}% >= mínimo ${MIN_LINE}%."
fi

<#
.SYNOPSIS
    Corre os testes com JaCoCo e apresenta a cobertura por package.

.DESCRIPTION
    Executa `mvn test` (que deixa o relatorio JaCoCo em target/site/jacoco) e resume
    a cobertura de linhas e de ramos por package, ordenada pelas linhas por cobrir.
    O relatorio HTML completo fica em target/site/jacoco/index.html.

    Para medir sem voltar a correr os testes (reutiliza o ultimo jacoco.exec):
        ./scripts/coverage.ps1 -NoTests

    Para falhar com codigo de saida 1 abaixo de um limiar de linhas:
        ./scripts/coverage.ps1 -MinLine 70

.PARAMETER NoTests
    Nao corre o Maven; apenas rele o relatorio existente.

.PARAMETER Open
    Abre o relatorio HTML no browser no fim.

.PARAMETER MinLine
    Percentagem minima de cobertura de linhas (0-100). Abaixo deste valor, sai com erro.

.PARAMETER Online
    Nao usa o modo offline do Maven (util para descarregar plugins/dependencias).

.EXAMPLE
    ./scripts/coverage.ps1 -Open

.EXAMPLE
    ./scripts/coverage.ps1 -NoTests -MinLine 70
#>
[CmdletBinding()]
param(
    [switch]$NoTests,
    [switch]$Open,
    [double]$MinLine = 0,
    [switch]$Online
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$reportDir = Join-Path $root 'target/site/jacoco'
$csvPath = Join-Path $reportDir 'jacoco.csv'

function Get-Percent([double]$covered, [double]$total) {
    if ($total -le 0) { return 'n/d' }
    return ('{0,6:N1}%' -f (100 * $covered / $total))
}

Push-Location $root
try {
    if (-not $NoTests) {
        $mvnArgs = @('-B', '-ntp')
        if (-not $Online) { $mvnArgs += '-o' }
        $mvnArgs += 'test'
        Write-Host "A correr: mvn $($mvnArgs -join ' ')" -ForegroundColor Cyan
        & mvn @mvnArgs
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Os testes falharam (codigo $LASTEXITCODE). Sem cobertura fiavel."
            exit $LASTEXITCODE
        }
    }

    if (-not (Test-Path -LiteralPath $csvPath)) {
        Write-Error "Nao encontrei $csvPath. Corre os testes primeiro (sem -NoTests)."
        exit 1
    }

    $rows = Import-Csv -LiteralPath $csvPath
    if (-not $rows) {
        Write-Error "O relatorio $csvPath esta vazio."
        exit 1
    }

    $lineMissed = ($rows | Measure-Object -Property LINE_MISSED -Sum).Sum
    $lineCovered = ($rows | Measure-Object -Property LINE_COVERED -Sum).Sum
    $branchMissed = ($rows | Measure-Object -Property BRANCH_MISSED -Sum).Sum
    $branchCovered = ($rows | Measure-Object -Property BRANCH_COVERED -Sum).Sum
    $lineTotal = $lineMissed + $lineCovered
    $branchTotal = $branchMissed + $branchCovered

    Write-Host ''
    Write-Host '=== Cobertura global ===' -ForegroundColor Cyan
    Write-Host ("Linhas : {0} / {1} ({2})" -f $lineCovered, $lineTotal, (Get-Percent $lineCovered $lineTotal))
    Write-Host ("Ramos  : {0} / {1} ({2})" -f $branchCovered, $branchTotal, (Get-Percent $branchCovered $branchTotal))

    Write-Host ''
    Write-Host '=== Por package (pior primeiro) ===' -ForegroundColor Cyan
    $byPackage = $rows |
        Group-Object -Property PACKAGE |
        ForEach-Object {
            $lm = ($_.Group | Measure-Object -Property LINE_MISSED -Sum).Sum
            $lc = ($_.Group | Measure-Object -Property LINE_COVERED -Sum).Sum
            $bm = ($_.Group | Measure-Object -Property BRANCH_MISSED -Sum).Sum
            $bc = ($_.Group | Measure-Object -Property BRANCH_COVERED -Sum).Sum
            [pscustomobject]@{
                Package       = $_.Name
                Linhas        = "$lc/$($lm + $lc)"
                'Cob. linhas' = (Get-Percent $lc ($lm + $lc))
                'Por cobrir'  = $lm
                Ramos         = "$bc/$($bm + $bc)"
                'Cob. ramos'  = (Get-Percent $bc ($bm + $bc))
            }
        } |
        Sort-Object -Property 'Por cobrir' -Descending

    $byPackage | Format-Table -AutoSize | Out-String -Width 200 | Write-Host

    Write-Host '=== Classes com mais linhas por cobrir (top 15) ===' -ForegroundColor Cyan
    $rows |
        Sort-Object -Property { [int]$_.LINE_MISSED } -Descending |
        Select-Object -First 15 @{Name = 'Classe'; Expression = { "$($_.PACKAGE).$($_.CLASS)" } },
            @{Name = 'Linhas'; Expression = { "$($_.LINE_COVERED)/$([int]$_.LINE_MISSED + [int]$_.LINE_COVERED)" } },
            @{Name = 'Cob. linhas'; Expression = { Get-Percent ([double]$_.LINE_COVERED) ([double]$_.LINE_MISSED + [double]$_.LINE_COVERED) } },
            @{Name = 'Por cobrir'; Expression = { [int]$_.LINE_MISSED } } |
        Format-Table -AutoSize | Out-String -Width 200 | Write-Host

    $indexPath = Join-Path $reportDir 'index.html'
    Write-Host "Relatorio HTML: $indexPath" -ForegroundColor Green

    if ($Open -and (Test-Path -LiteralPath $indexPath)) {
        Start-Process $indexPath
    }

    if ($MinLine -gt 0) {
        $pct = if ($lineTotal -gt 0) { 100 * $lineCovered / $lineTotal } else { 0 }
        if ($pct -lt $MinLine) {
            Write-Host ("Cobertura de linhas {0:N1}% abaixo do minimo {1:N1}%." -f $pct, $MinLine) -ForegroundColor Red
            exit 1
        }
        Write-Host ("Cobertura de linhas {0:N1}% >= minimo {1:N1}%." -f $pct, $MinLine) -ForegroundColor Green
    }
}
finally {
    Pop-Location
}



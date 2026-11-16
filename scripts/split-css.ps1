$ErrorActionPreference = "Stop"
$root = Join-Path $PSScriptRoot "..\frontend\src"
$src = Join-Path $root "styles.css"
$outDir = Join-Path $root "styles"
if (!(Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$utf8 = [System.Text.Encoding]::UTF8
$lines = [System.IO.File]::ReadAllLines($src, $utf8)

$blocks = New-Object System.Collections.ArrayList
$i = 0
while ($i -lt $lines.Count) {
  $line = $lines[$i]
  if ($line -match '^\S' -and ($line -match '\{\s*$' -or $line -match ',\s*$')) {
    $selParts = New-Object System.Collections.ArrayList
    $k = $i
    while ($k -lt $lines.Count) {
      [void]$selParts.Add($lines[$k].Trim())
      if ($lines[$k] -match '\{') { break }
      $k++
    }
    $selector = ($selParts -join ' ') -replace '\s*\{\s*$', ''
    $selector = ($selector -replace '\s+', ' ').Trim()
    $j = $i
    while ($j -lt $lines.Count -and $lines[$j] -notmatch '^\}') { $j++ }
    if ($j -ge $lines.Count) { throw "bloco sem fecho a partir da linha $($i + 1)" }
    $text = ($lines[$i..$j] -join "`n")
    [void]$blocks.Add([pscustomobject]@{ selector = $selector; text = $text })
    $i = $j + 1
  } else {
    $i++
  }
}
Write-Output ("blocos: " + $blocks.Count)

$rules = @(
  @{ bucket = "responsive"; pattern = '^@media' },
  @{ bucket = "base";       pattern = '^:root' },
  @{ bucket = "base";       pattern = '^\*$' },
  @{ bucket = "base";       pattern = '^body' },
  @{ bucket = "base";       pattern = '^button' },
  @{ bucket = "base";       pattern = '^input' },
  @{ bucket = "base";       pattern = '^select' },
  @{ bucket = "base";       pattern = '^\.muted' },
  @{ bucket = "base";       pattern = '^\.error-text' },
  @{ bucket = "ui";         pattern = '^@keyframes' },
  @{ bucket = "auth";       pattern = '^\.login-' },
  @{ bucket = "auth";       pattern = '^\.ball-decoration' },
  @{ bucket = "auth";       pattern = '^\.button-wide' },
  @{ bucket = "auth";       pattern = '^\.mfa-secret' },
  @{ bucket = "auth";       pattern = '^\.recovery-codes' },
  @{ bucket = "layout";     pattern = '^\.app-shell' },
  @{ bucket = "layout";     pattern = '^\.sidebar' },
  @{ bucket = "layout";     pattern = '^\.brand' },
  @{ bucket = "layout";     pattern = '^\.club-' },
  @{ bucket = "layout";     pattern = '^\.nav-' },
  @{ bucket = "layout";     pattern = '^\.user-' },
  @{ bucket = "layout";     pattern = '^\.logout-button' },
  @{ bucket = "layout";     pattern = '^\.icon-button' },
  @{ bucket = "layout";     pattern = '^\.main-content' },
  @{ bucket = "layout";     pattern = '^\.topbar' },
  @{ bucket = "layout";     pattern = '^\.breadcrumb' },
  @{ bucket = "layout";     pattern = '^\.status-dot' },
  @{ bucket = "layout";     pattern = '^\.content-wrap' },
  @{ bucket = "layout";     pattern = '^\.mobile-only' },
  @{ bucket = "overview";   pattern = '^\.welcome-' },
  @{ bucket = "overview";   pattern = '^\.graphic-' },
  @{ bucket = "overview";   pattern = '^\.ring-' },
  @{ bucket = "overview";   pattern = '^\.light-eyebrow' },
  @{ bucket = "overview";   pattern = '^\.section-title' },
  @{ bucket = "overview";   pattern = '^\.quick-' },
  @{ bucket = "overview";   pattern = '^\.check( |\.|$)' },
  @{ bucket = "overview";   pattern = '^\.panel-note' },
  @{ bucket = "athletes";   pattern = '^\.toolbar' },
  @{ bucket = "athletes";   pattern = '^\.search-box' },
  @{ bucket = "athletes";   pattern = '^\.person-' },
  @{ bucket = "athletes";   pattern = '^\.upload-box' },
  @{ bucket = "teams";      pattern = '^\.team-' },
  @{ bucket = "matches";    pattern = '^\.match-' },
  @{ bucket = "seasons";    pattern = '^\.season-' },
  @{ bucket = "seasons";    pattern = '^\.group-' },
  @{ bucket = "billing";    pattern = '^\.billing-stats' },
  @{ bucket = "billing";    pattern = '^\.amount-' },
  @{ bucket = "ui";         pattern = '^\.page-heading' },
  @{ bucket = "ui";         pattern = '^\.heading-actions' },
  @{ bucket = "ui";         pattern = '^\.button' },
  @{ bucket = "ui";         pattern = '^\.stat-' },
  @{ bucket = "ui";         pattern = '^\.two-col' },
  @{ bucket = "ui";         pattern = '^\.panel' },
  @{ bucket = "ui";         pattern = '^\.tag' },
  @{ bucket = "ui";         pattern = '^\.cards-grid' },
  @{ bucket = "ui";         pattern = '^\.full-card' },
  @{ bucket = "ui";         pattern = '^table' },
  @{ bucket = "ui";         pattern = '^th' },
  @{ bucket = "ui";         pattern = '^td' },
  @{ bucket = "ui";         pattern = '^\.row-arrow' },
  @{ bucket = "ui";         pattern = '^\.modal' },
  @{ bucket = "ui";         pattern = '^\.stack' },
  @{ bucket = "ui";         pattern = '^\.form-grid' },
  @{ bucket = "ui";         pattern = '^\.field-help' },
  @{ bucket = "ui";         pattern = '^\.empty-' },
  @{ bucket = "ui";         pattern = '^\.loader' },
  @{ bucket = "ui";         pattern = '^\.toast' },
  @{ bucket = "ui";         pattern = '^\.check-option' },
  @{ bucket = "ui";         pattern = '^\.detail-list' },
  @{ bucket = "ui";         pattern = '^\.clickable-row' },
  @{ bucket = "ui";         pattern = '^\.table-panel' },
  @{ bucket = "ui";         pattern = '^\.table-subtext' }
)

$order = @("base", "layout", "ui", "overview", "athletes", "teams", "matches", "seasons", "billing", "auth", "responsive")
$buckets = @{}
foreach ($name in $order) { $buckets[$name] = New-Object System.Collections.ArrayList }

$unmatched = New-Object System.Collections.ArrayList
foreach ($block in $blocks) {
  $bucket = $null
  foreach ($rule in $rules) {
    if ($block.selector -match $rule.pattern) { $bucket = $rule.bucket; break }
  }
  if ($null -eq $bucket) {
    [void]$unmatched.Add($block.selector)
  } else {
    [void]$buckets[$bucket].Add($block.text)
  }
}

if ($unmatched.Count -gt 0) {
  Write-Output "SEM CLASSIFICACAO:"
  $unmatched | ForEach-Object { Write-Output ("  " + $_) }
  throw "ha blocos por classificar"
}

$encoding = New-Object System.Text.UTF8Encoding($false)
$total = 0
foreach ($name in $order) {
  $content = ($buckets[$name] -join "`n") + "`n"
  [System.IO.File]::WriteAllText((Join-Path $outDir ($name + ".css")), $content, $encoding)
  $total += $buckets[$name].Count
  Write-Output ("{0,-12} {1,3} regras" -f $name, $buckets[$name].Count)
}
Write-Output ("total: " + $total)

<# Testes sem Docker, Java ou Pester: apenas as guardas e a ordem antes do seed. #>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'start-dev.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}
function Assert-Fails {
    param([scriptblock]$Action, [string]$Message)
    $failed = $false
    try { & $Action } catch { $failed = $true }
    Assert-True $failed $Message
}

$script:occupied = $true
function Get-NetTCPConnection {
    param($LocalPort, $State, $ErrorAction)
    if ($script:occupied) { [pscustomobject]@{ OwningProcess = 12345 } }
}

# Recusar uma instância existente antes de tocar em Docker, Maven ou SQL.
Assert-Fails { Invoke-DevLaunch -Seed } 'Uma porta ocupada não bloqueou o lançamento.'
$script:occupied = $false
Assert-DevPortFree -Port 8080

# Não ignorar um código de erro de um comando nativo.
function Test-NativeFailure { $global:LASTEXITCODE = 7 }
Assert-Fails { Invoke-DevCommand -Command Test-NativeFailure } 'O código de erro foi ignorado.'

$script:calls = [System.Collections.Generic.List[string]]::new()
$script:mode = 'ready'
$script:checks = 0
function Invoke-RestMethod {
    param($Uri, $TimeoutSec)
    $script:calls.Add('ping')
    $script:checks++
    switch ($script:mode) {
        'old' { return [pscustomobject]@{ status = 'ok'; tenant = 'nenhum' } }
        'wrongPid' { return [pscustomobject]@{ status = 'ok'; database = 'ready'; processId = 999 } }
        'unavailable' { throw 'HTTP 503' }
        'changed' { if ($script:checks -eq 2) { throw 'HTTP 503 após recriar a BD' } }
    }
    return [pscustomobject]@{ status = 'ok'; database = 'ready'; processId = 42 }
}
function Invoke-DevCommand {
    param([string]$Command, [string[]]$CommandArguments)
    $script:calls.Add(($CommandArguments -join ' '))
    if ($script:mode -eq 'sqlFailure' -and $CommandArguments -contains 'exec') { throw 'SQL inválido' }
}
$backend = [pscustomobject]@{ Id = 42; HasExited = $false }

foreach ($mode in @('old', 'wrongPid', 'unavailable')) {
    $script:mode = $mode
    $script:calls.Clear()
    $script:checks = 0
    Assert-Fails { Invoke-DevSeed -Docker docker -Compose @('compose') -Url 'http://dev/ping' -Backend $backend } "O caso $mode permitiu executar o seed."
    Assert-True ($script:calls.Count -eq 1 -and $script:calls[0] -eq 'ping') 'Houve um comando antes de validar a aplicação.'
}

$script:mode = 'changed'
$script:calls.Clear()
$script:checks = 0
Assert-Fails { Invoke-DevSeed -Docker docker -Compose @('compose') -Url 'http://dev/ping' -Backend $backend } 'A BD mudou e o seed não foi bloqueado.'
Assert-True (-not (($script:calls -join ';') -match ' exec ')) 'Foi executado SQL depois de perder a prontidão.'

$script:mode = 'ready'
$script:calls.Clear()
$script:checks = 0
$backend.HasExited = $true
Assert-Fails { Invoke-DevSeed -Docker docker -Compose @('compose') -Url 'http://dev/ping' -Backend $backend } 'Um processo terminado permitiu o seed.'
Assert-True (-not (($script:calls -join ';') -match ' exec ')) 'Foi executado SQL depois de o backend terminar.'
$backend.HasExited = $false

$script:mode = 'sqlFailure'
$script:calls.Clear()
$script:checks = 0
Assert-Fails { Invoke-DevSeed -Docker docker -Compose @('compose') -Url 'http://dev/ping' -Backend $backend } 'A falha SQL não interrompeu o lançamento.'
Assert-True ($script:checks -eq 2) 'O lançamento continuou depois de o SQL falhar.'

$script:mode = 'ready'
$script:calls.Clear()
$script:checks = 0
Invoke-DevSeed -Docker docker -Compose @('compose') -Url 'http://dev/ping' -Backend $backend
Assert-True ($script:calls.Count -eq 5) 'Ordem inesperada no seed protegido.'
Assert-True ($script:calls[0] -eq 'ping' -and $script:calls[1] -match ' cp ' -and $script:calls[2] -eq 'ping') 'Falta uma validação antes do seed.'
Assert-True ($script:calls[3] -match 'ON_ERROR_STOP=1 --single-transaction' -and $script:calls[4] -eq 'ping') 'O seed não é transacional ou falta a validação final.'
Write-Host '10 cenários de proteção DEV passaram.'

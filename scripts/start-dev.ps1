<#
.SYNOPSIS
    Arranca o backend DEV e só carrega o seed após validar a aplicação e as migrações.
.EXAMPLE
    .\scripts\start-dev.ps1 -Seed
.NOTES
    Não termina instâncias existentes, não apaga volumes e não carrega dados em staging.
    Ctrl+C termina apenas o backend iniciado por este lançamento; o Docker mantém-se ativo.
#>
[CmdletBinding()]
param(
    [switch]$Seed,
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [ValidateRange(5, 600)]
    [int]$TimeoutSeconds = 180
)

function Assert-DevPortFree {
    param([int]$Port)
    $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) {
        $ids = ($listeners.OwningProcess | Sort-Object -Unique) -join ', '
        throw "A porta $Port está ocupada (PID: $ids). Para essa instância no terminal de origem e repete o lançamento. A BD e o seed não serão alterados."
    }
}

function Invoke-DevCommand {
    param([string]$Command, [string[]]$CommandArguments)
    & $Command @CommandArguments
    if ($LASTEXITCODE -ne 0) {
        throw "O comando $Command falhou (código $LASTEXITCODE). O lançamento foi interrompido."
    }
}

function Assert-DevReady {
    param([string]$Url, [int]$ProcessId)
    $response = Invoke-RestMethod -Uri $Url -TimeoutSec 5
    if ($response.status -ne 'ok' -or $response.database -ne 'ready' -or $response.processId -ne $ProcessId) {
        throw 'A resposta não confirma a prontidão do backend iniciado por este lançamento.'
    }
}

function Invoke-DevSeed {
    param([string]$Docker, [string[]]$Compose, [string]$Url, $Backend)
    Assert-DevReady -Url $Url -ProcessId $Backend.Id
    Invoke-DevCommand -Command $Docker -CommandArguments ($Compose + @('cp', './docker/dev-seed.sql', 'db:/tmp/dev-seed.sql'))
    # Revalidar imediatamente antes da transação; nunca aceitar o ping de uma instância antiga.
    Assert-DevReady -Url $Url -ProcessId $Backend.Id
    if ($Backend.HasExited) { throw 'O backend terminou antes do seed.' }
    Invoke-DevCommand -Command $Docker -CommandArguments ($Compose + @('exec', '-T', 'db', 'psql', '-U', 'postgres', '-d', 'volei',
        '-v', 'ON_ERROR_STOP=1', '--single-transaction', '-f', '/tmp/dev-seed.sql'))
    Assert-DevReady -Url $Url -ProcessId $Backend.Id
    Write-Host 'Seed DEV aplicado com sucesso.'
}

function Invoke-DevLaunch {
    param([switch]$Seed, [int]$Port = 8080, [int]$TimeoutSeconds = 180)
    $ErrorActionPreference = 'Stop'
    $root = Split-Path -Parent $PSScriptRoot
    $backend = $null
    Push-Location $root
    try {
        # A verificação antecede qualquer escrita: uma instância antiga exige um reinício explícito.
        Assert-DevPortFree -Port $Port
        $docker = (Get-Command docker -ErrorAction Stop).Source
        $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
        if (-not (Test-Path -LiteralPath $java)) { throw 'Não foi encontrado o Java. Configura JAVA_HOME com um JDK 25.' }
        $version = & $java --version
        if ($LASTEXITCODE -ne 0 -or ($version -join ' ') -notmatch '^(?:openjdk|java) 25(?:\.|\s|$)') {
            throw 'Este lançamento requer JDK 25. Verifica JAVA_HOME.'
        }
        $compose = @('compose', '--project-directory', $root, '-f', (Join-Path $root 'docker-compose.yml'))
        Invoke-DevCommand -Command $docker -CommandArguments @('info', '--format', '{{.ServerVersion}}')
        Invoke-DevCommand -Command $docker -CommandArguments ($compose + @('up', '-d', 'db', 'mailpit'))

        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            & $docker @compose exec -T db pg_isready -U postgres -d volei *> $null
            if ($LASTEXITCODE -eq 0) { break }
            if ([DateTime]::UtcNow -ge $deadline) { throw 'A BD DEV não ficou disponível dentro do prazo. O seed não foi executado.' }
            Start-Sleep -Seconds 1
        } while ($true)

        # O JAR acabado de construir evita reutilizar classes ou uma aplicação antiga.
        Invoke-DevCommand -Command (Join-Path $root 'mvnw.cmd') -CommandArguments @('-B', '-ntp', '-DskipTests', 'package')
        $jars = @(Get-ChildItem -LiteralPath (Join-Path $root 'target') -Filter '*.jar' -File)
        if ($jars.Count -ne 1) { throw 'Esperava exatamente um JAR em target. Resolve os artefactos ambíguos antes de arrancar.' }
        Assert-DevPortFree -Port $Port
        $stdout = Join-Path $root "target/dev-$Port.stdout.log"
        $stderr = Join-Path $root "target/dev-$Port.stderr.log"
        $arguments = @('-jar', ('"{0}"' -f $jars[0].FullName), '--spring.profiles.active=dev',
            "--server.port=$Port", '--server.address=127.0.0.1',
            '--spring.datasource.url=jdbc:postgresql://localhost:5432/volei', '--spring.datasource.username=volei_app',
            '--spring.flyway.url=jdbc:postgresql://localhost:5432/volei', '--spring.flyway.user=volei_migrator',
            '--spring.flyway.enabled=true', '--spring.flyway.locations=classpath:db/migration')
        $backend = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $root -PassThru -NoNewWindow `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $url = "http://127.0.0.1:$Port/api/v1/ping"
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while ($true) {
            if ($backend.HasExited) { throw "O backend terminou antes de ficar pronto. Consulta $stdout e $stderr. O seed não foi executado." }
            try {
                Assert-DevReady -Url $url -ProcessId $backend.Id
                break
            } catch {
                if ([DateTime]::UtcNow -ge $deadline) { throw "A aplicação ou as migrações não ficaram prontas. Consulta $stdout e $stderr. O seed não foi executado." }
                Start-Sleep -Seconds 1
            }
        }
        if ($Seed) {
            Invoke-DevSeed -Docker $docker -Compose $compose -Url $url -Backend $backend
        }
        Write-Host "Backend DEV pronto em http://localhost:$Port. Logs: $stdout. Ctrl+C para terminar."
        while (-not $backend.HasExited) { Start-Sleep -Seconds 1 }
        $backend.WaitForExit()
        if ($backend.ExitCode -ne 0) { throw 'O backend terminou inesperadamente. Consulta os logs de arranque.' }
    } finally {
        # Só o processo Java que este script criou; nunca procurar e terminar outros PIDs.
        if ($null -ne $backend) {
            if (-not $backend.HasExited) { $backend.Kill(); $backend.WaitForExit() }
            $backend.Dispose()
        }
        Pop-Location
    }
}

# Dot-sourcing permite testar as guardas sem arrancar serviços.
if ($MyInvocation.InvocationName -ne '.') {
    Invoke-DevLaunch -Seed:$Seed -Port $Port -TimeoutSeconds $TimeoutSeconds
}

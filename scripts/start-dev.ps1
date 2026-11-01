param([ValidateSet('all','backend','admin','miniapp')][string]$Component = 'all', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$localDirectory = Join-Path $projectRoot '.local'
New-Item -ItemType Directory -Force -Path $localDirectory | Out-Null

function Assert-Free([int]$Port) {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "Port $Port is occupied. Stop this project's service or change its configured port." }
}
function Launch([string]$Name,[string]$Executable,[string[]]$Arguments,[string]$Directory) {
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $Directory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $localDirectory "$Name.stdout.log") -RedirectStandardError (Join-Path $localDirectory "$Name.stderr.log") -PassThru
    @{pid=$process.Id;startedAt=$process.StartTime.ToUniversalTime().ToString('o');directory=$Directory} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $localDirectory "$Name.process.json") -Encoding utf8
    Write-Output "$Name launched (PID $($process.Id)). Logs: .local/$Name.stdout.log"
}
if ($Component -in @('all','backend')) {
    Assert-Free 18080
    $directory = Join-Path $projectRoot 'backend'
    if (-not (Test-Path -LiteralPath (Join-Path $directory 'application-local.yml'))) { throw 'Copy backend/application-local.example.yml to application-local.yml and configure credentials.' }
    if (-not $SkipBuild -or -not (Test-Path -LiteralPath (Join-Path $directory 'target/travel-backend-0.1.0.jar'))) {
        Push-Location $directory
        try { & mvn.cmd -q package; if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed' } } finally { Pop-Location }
    }
    Launch 'backend' (Get-Command java.exe).Source @('-Dfile.encoding=UTF-8','-Duser.timezone=Asia/Shanghai','-jar','target/travel-backend-0.1.0.jar') $directory
    Write-Output 'Backend URL: http://localhost:18080'
}
foreach ($name in @('admin','miniapp')) {
    if ($Component -notin @('all',$name)) { continue }
    $port = if ($name -eq 'admin') { 5173 } else { 5174 }
    Assert-Free $port
    $directory = Join-Path $projectRoot $(if ($name -eq 'admin') { 'admin-web' } else { 'miniapp' })
    if (-not (Test-Path -LiteralPath (Join-Path $directory 'node_modules'))) {
        Push-Location $directory
        try { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' } } finally { Pop-Location }
    }
    $arguments = if ($name -eq 'admin') { @('node_modules/vite/bin/vite.js','--host','0.0.0.0','--port',"$port",'--strictPort') } else { @('node_modules/@dcloudio/vite-plugin-uni/bin/uni.js','-p','h5','--host','0.0.0.0','--port',"$port",'--strictPort') }
    Launch $name (Get-Command node.exe).Source $arguments $directory
    Write-Output "$name URL: http://localhost:$port"
}

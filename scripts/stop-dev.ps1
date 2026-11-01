param([ValidateSet('all','backend','admin','miniapp')][string]$Component = 'all')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$localDirectory = Join-Path $projectRoot '.local'
$names = if ($Component -eq 'all') { @('miniapp','admin','backend') } else { @($Component) }
foreach ($name in $names) {
    $recordPath = Join-Path $localDirectory "$name.process.json"
    if (-not (Test-Path -LiteralPath $recordPath)) { continue }
    $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
    $process = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
    if ($process) {
        $expected = ([DateTime]$record.startedAt).ToUniversalTime()
        if ([Math]::Abs(($process.StartTime.ToUniversalTime() - $expected).TotalSeconds) -gt 1) {
            Write-Warning "Skipping $name because its PID belongs to a different process."
            continue
        }
        Stop-Process -Id $record.pid
        $process.WaitForExit(10000) | Out-Null
        Write-Output "$name stopped (PID $($record.pid))"
    }
    Remove-Item -LiteralPath $recordPath
}

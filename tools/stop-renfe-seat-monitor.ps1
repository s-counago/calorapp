$ErrorActionPreference = 'Stop'

$toolsDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidFile = Join-Path $toolsDirectory 'renfe-seat-monitor.pid'

if (!(Test-Path -LiteralPath $pidFile)) {
    Write-Output 'No hay un monitor registrado.'
    exit 0
}

$monitorId = [int](Get-Content -LiteralPath $pidFile -Raw)
$monitorProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $monitorId"
if ($monitorProcess -and $monitorProcess.CommandLine -like '*renfe-seat-monitor.js*') {
    Stop-Process -Id $monitorId
    Write-Output "Monitor detenido (PID $monitorId)."
} else {
    Write-Output 'El proceso registrado ya no está en marcha.'
}

Remove-Item -LiteralPath $pidFile

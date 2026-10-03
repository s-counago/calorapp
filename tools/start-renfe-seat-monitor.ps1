$ErrorActionPreference = 'Stop'

$toolsDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$workspaceDirectory = Split-Path -Parent $toolsDirectory
$pidFile = Join-Path $toolsDirectory 'renfe-seat-monitor.pid'
$logFile = Join-Path $toolsDirectory 'renfe-seat-monitor.log'
$errorFile = Join-Path $toolsDirectory 'renfe-seat-monitor.error.log'

if (Test-Path -LiteralPath $pidFile) {
    $existingId = [int](Get-Content -LiteralPath $pidFile -Raw)
    $existingProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $existingId"
    if ($existingProcess -and $existingProcess.CommandLine -like '*renfe-seat-monitor.js*') {
        Write-Output "El monitor ya está en marcha (PID $existingId)."
        exit 0
    }
}

$nodePath = (Get-Command node -ErrorAction Stop).Source
$monitorProcess = Start-Process -FilePath $nodePath -ArgumentList @('tools/renfe-seat-monitor.js') -WorkingDirectory $workspaceDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput $logFile -RedirectStandardError $errorFile
Set-Content -LiteralPath $pidFile -Value $monitorProcess.Id
Start-Sleep -Seconds 2
$monitorProcess.Refresh()

if ($monitorProcess.HasExited) {
    Remove-Item -LiteralPath $pidFile
    Get-Content -LiteralPath $errorFile -Tail 10
    throw 'El monitor terminó al iniciarse.'
}

Write-Output "Monitor en marcha (PID $($monitorProcess.Id)). Registro: $logFile"

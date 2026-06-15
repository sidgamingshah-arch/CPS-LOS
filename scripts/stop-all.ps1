# Stop all Helix services started by run-all.ps1 (Windows / PowerShell port of
# stop-all.sh).
$services = "config-service","counterparty-service","origination-service","risk-service",
            "decision-service","portfolio-service","copilot-service","limit-service","gateway-service"

foreach ($svc in $services) {
  $pidFile = "$env:TEMP\helix-$svc.pid"
  if (Test-Path $pidFile) {
    $procId = Get-Content $pidFile
    if (Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue -PassThru) {
      Write-Host "stopped $svc ($procId)"
    }
    Remove-Item $pidFile -ErrorAction SilentlyContinue
  }
}

# Belt and suspenders: kill any lingering java process for a Helix jar.
foreach ($svc in $services) {
  Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*$svc.jar*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
Write-Host "stop-all complete"

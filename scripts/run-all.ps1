# Launch all Helix services locally against SQLite (Windows / PowerShell port of
# run-all.sh). Logs to %TEMP%\helix-*.log; PIDs to %TEMP%\helix-<svc>.pid so
# stop-all.ps1 can clean up. Build the jars first: mvn -DskipTests package
$ErrorActionPreference = "Stop"
$root = (Resolve-Path "$PSScriptRoot\..").Path
if (-not $env:HELIX_DATA_DIR) { $env:HELIX_DATA_DIR = "$root\data" }
New-Item -ItemType Directory -Force -Path $env:HELIX_DATA_DIR | Out-Null

# config first; the others fall back gracefully if it lags
$order = "config-service","counterparty-service","origination-service","risk-service",
         "decision-service","portfolio-service","copilot-service","limit-service","gateway-service"
$ports = @{ "config-service"=8081; "counterparty-service"=8082; "origination-service"=8083;
            "risk-service"=8084; "decision-service"=8085; "portfolio-service"=8086;
            "copilot-service"=8087; "limit-service"=8088; "gateway-service"=8080 }

foreach ($svc in $order) {
  $port = $ports[$svc]
  $jar  = "$root\$svc\target\$svc.jar"
  if (-not (Test-Path $jar)) {
    Write-Host "  !! $jar not found — run 'mvn -DskipTests package' first" -ForegroundColor Yellow
    continue
  }
  Write-Host "starting $svc on $port"
  # Pass the port as a Spring arg (race-free vs. mutating an env var in a loop).
  $p = Start-Process -PassThru -NoNewWindow -FilePath "java" `
        -ArgumentList "-jar","$jar","--server.port=$port" `
        -RedirectStandardOutput "$env:TEMP\helix-$svc.log" `
        -RedirectStandardError  "$env:TEMP\helix-$svc.err.log"
  $p.Id | Set-Content "$env:TEMP\helix-$svc.pid"
}

Write-Host "All services launching. Waiting for health..."
foreach ($port in 8081,8082,8083,8084,8085,8086,8087,8088,8080) {
  $up = $false
  for ($i = 0; $i -lt 60; $i++) {
    try {
      Invoke-WebRequest -UseBasicParsing "http://localhost:$port/actuator/health" -TimeoutSec 2 | Out-Null
      $up = $true; break
    } catch { Start-Sleep -Seconds 1 }
  }
  if ($up) { Write-Host "  :$port UP" }
  else     { Write-Host "  :$port DID NOT COME UP — see $env:TEMP\helix-*.log" -ForegroundColor Red }
}
Write-Host "Helix is up. Gateway at http://localhost:8080"

# Fetch the 9 prebuilt service jars from the rolling `prebuilt-latest` release
# into their target/ slots. Lets machines without Maven (or with the wrong JDK
# major) run scripts\run-all.ps1 without a local build.
$ErrorActionPreference = "Stop"

$repo = if ($env:HELIX_REPO) { $env:HELIX_REPO } else { "sidgamingshah-arch/cps-los" }
$tag  = if ($env:HELIX_PREBUILT_TAG) { $env:HELIX_PREBUILT_TAG } else { "prebuilt-latest" }
$root = (Resolve-Path "$PSScriptRoot\..").Path

$services = "config","counterparty","origination","risk","decision","portfolio","copilot","limit","gateway"

foreach ($s in $services) {
  $jar    = "$s-service.jar"
  $url    = "https://github.com/$repo/releases/download/$tag/$jar"
  $target = "$root\$s-service\target\$jar"
  New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
  Write-Host "fetching $jar"
  Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $target
}

Write-Host "All 9 jars fetched to */target/. Now run: .\scripts\run-all.ps1"

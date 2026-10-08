$ErrorActionPreference = "Stop"
$launcherPath = Join-Path $PSScriptRoot "start.mjs"
$nodeExecutable = Get-Command node -ErrorAction SilentlyContinue
if (-not $nodeExecutable) {
    Write-Error "Node.js 20+ and JDK 17+ are required. Install Node.js and add it to PATH."
    exit 1
}
& $nodeExecutable.Source $launcherPath @args
exit $LASTEXITCODE

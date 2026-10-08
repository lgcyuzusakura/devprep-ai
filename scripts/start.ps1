$ErrorActionPreference = "Stop"
$launcherPath = Join-Path $PSScriptRoot "start.py"
$pythonLauncher = Get-Command py -ErrorAction SilentlyContinue

if ($pythonLauncher) {
    & $pythonLauncher.Source -3 $launcherPath @args
} else {
    $pythonExecutable = Get-Command python -ErrorAction SilentlyContinue
    if (-not $pythonExecutable) {
        Write-Error "Python 3.11+ is required. Install Python and add it to PATH."
        exit 1
    }
    & $pythonExecutable.Source $launcherPath @args
}

exit $LASTEXITCODE

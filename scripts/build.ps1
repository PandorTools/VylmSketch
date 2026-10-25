$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not $env:JAVA_HOME) {
    $taskJdk = Get-ChildItem '.tools/java' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($taskJdk) { $env:JAVA_HOME = $taskJdk.FullName }
}
& './gradlew.bat' assembleDebug lintDebug --console plain
if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }

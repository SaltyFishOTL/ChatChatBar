param(
    [switch]$SkipAssemble
)

$ErrorActionPreference = "Stop"

if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME is not set. Install JDK 17 and restart the terminal."
}

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$javaVersion = & "$env:JAVA_HOME\bin\java.exe" -version 2>&1
$ErrorActionPreference = $previousErrorActionPreference
if ($LASTEXITCODE -ne 0) {
    throw "Java failed to run from JAVA_HOME: $env:JAVA_HOME"
}

Write-Host "Using JAVA_HOME=$env:JAVA_HOME"
Write-Host ($javaVersion -join "`n")

$tasks = @(
    "test",
    "compileDebugAndroidTestKotlin"
)

if (-not $SkipAssemble) {
    $tasks += "assembleDebug"
}

& "$PSScriptRoot\gradlew.bat" @tasks --stacktrace
exit $LASTEXITCODE

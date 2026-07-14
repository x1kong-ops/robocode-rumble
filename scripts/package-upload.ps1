# Build a RoboRumble-ready JAR (with .properties) into dist/, deploy to Robocode robots/.
# Usage:
#   .\scripts\package-upload.ps1 -Version 1.0
#   .\scripts\package-upload.ps1 -Version 1.0 -Package pc.Wavelet -RobocodeHome C:\robocode-1.9.4.2
param(
    [string]$Version = "1.0",
    [string]$Package = "rcr.Wavelet",
    [string]$RobocodeHome = $(if ($env:ROBOCODE_HOME) { $env:ROBOCODE_HOME } else { "C:\robocode-1.9.4.2" }),
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot

$parts = $Package.Split(".")
if ($parts.Length -lt 2) { Write-Error "Package must look like author.BotName" }
$className = $parts[-1]
$relDir = ($parts[0..($parts.Length - 2)] -join "\")
$javaPackage = ($parts[0..($parts.Length - 2)] -join ".")
$entry = ($parts[0..($parts.Length - 2)] -join "/")

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "build.ps1") -RobocodeHome $RobocodeHome
}

$classFile = Join-Path $root "out\classes\$relDir\$className.class"
if (-not (Test-Path $classFile)) {
    Write-Error "Missing $classFile - rename package and rebuild first"
}

$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$propsPath = Join-Path $root "out\classes\$relDir\$className.properties"
$props = @(
    "#Robocode Robot",
    "robot.classname=$Package",
    "robot.version=$Version",
    "robot.description=True surfing + KNN gun/surf + score-max power + active shadows + flattener",
    "robot.author.name=$javaPackage",
    "robot.webpage=",
    "robot.java.source.included=false",
    "robocode.version=1.9.4.2"
) -join "`r`n"
Set-Content -Path $propsPath -Value $props -Encoding ASCII

$jarName = $Package + "_" + $Version + ".jar"
$jarPath = Join-Path $dist $jarName
if (Test-Path $jarPath) { Remove-Item $jarPath }

Push-Location (Join-Path $root "out\classes")
try {
    jar cf $jarPath $entry
    if ($LASTEXITCODE -ne 0) { Write-Error "jar packaging failed" }
} finally {
    Pop-Location
}

$robotsJar = Join-Path $RobocodeHome ("robots\" + $jarName)
Copy-Item $jarPath $robotsJar -Force

Write-Host ""
Write-Host ("Upload JAR: {0} ({1} bytes)" -f $jarPath, (Get-Item $jarPath).Length)
Write-Host ("Deployed:   {0}" -f $robotsJar)
Write-Host ("Selector:   {0} {1}" -f $Package, $Version)
Write-Host ""
Write-Host "Next (manual):"
Write-Host "  1. Host JAR with a DIRECT download URL (GitHub Release / Dropbox ?dl=1)"
Write-Host ("  2. RoboWiki Participants line: {0} {1},https://YOUR_DIRECT_URL.jar" -f $Package, $Version)
Write-Host "     (no space after comma; delete older version row when updating)"

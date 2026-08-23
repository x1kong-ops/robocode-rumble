# Build, deploy loose classes into Robocode's robots dir, run a headless battle, print results.
# Usage: .\scripts\run-battle.ps1 -Enemy sample.Tracker [-Rounds 35] [-SkipBuild]
param(
    [string]$Enemy = "sample.Tracker",
    [int]$Rounds = 35,
    [string]$Bot = "pc.Wavelet dev",
    [string]$RobocodeHome = $(if ($env:ROBOCODE_HOME) { $env:ROBOCODE_HOME } else { "C:\robocode" }),
    [switch]$SkipBuild,
    [string[]]$ExtraJvmArgs = @()   # 例: -ExtraJvmArgs "-DNOSECURITY=true","-Drcr.datalog=..."
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "build.ps1") -RobocodeHome $RobocodeHome | Out-Null
}

# Package as a jar (this Robocode install does not pick up loose classes in robots/)
$props = Join-Path $root "out\classes\pc\Wavelet.properties"
@"
robot.classname=pc.Wavelet
robot.version=dev
robot.name=pc.Wavelet
robot.description=KNN surfing + KNN dual gun + score-max power + active shadows + flattener
robocode.version=1.10.3
robot.java.source.included=false
"@ | Set-Content -Path $props -Encoding ASCII
$sparProps = Join-Path $root "out\classes\pc\Spar.properties"
@"
robot.classname=pc.Spar
robot.version=dev
robot.name=pc.Spar
robot.description=Wavelet clone
robocode.version=1.10.3
robot.java.source.included=false
"@ | Set-Content -Path $sparProps -Encoding ASCII
# 先打到 out/ 再复制：roborumble 客户端常锁住 robots\*.jar，直接 jar cf 会失败
$tmpJar = Join-Path $root "out\pc.Wavelet_dev.jar"
$destJar = Join-Path $RobocodeHome "robots\pc.Wavelet_dev.jar"
jar cf $tmpJar -C (Join-Path $root "out\classes") pc
if ($LASTEXITCODE -ne 0) { Write-Error "jar packaging failed" }
Copy-Item $tmpJar $destJar -Force
if (-not (Test-Path $destJar)) { Write-Error "failed to deploy $destJar" }

# Generate battle file
$battleDir = Join-Path $root "battles"
New-Item -ItemType Directory -Force -Path $battleDir | Out-Null
$battleFile = Join-Path $battleDir "current.battle"
@"
#Battle Properties
robocode.battleField.width=800
robocode.battleField.height=600
robocode.battle.numRounds=$Rounds
robocode.battle.gunCoolingRate=0.1
robocode.battle.rules.inactivityTime=450
robocode.battle.selectedRobots=$Bot,$Enemy
"@ | Set-Content -Path $battleFile -Encoding ASCII

$safeName = $Enemy -replace '[^A-Za-z0-9.]', '_'
$results = Join-Path $root "out\results-$safeName.txt"

Push-Location $RobocodeHome
try {
    java -cp "libs/*" -Xmx512M -XX:+IgnoreUnrecognizedVMOptions `
        "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED" `
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED" `
        "--add-opens=java.desktop/javax.swing.text=ALL-UNNAMED" `
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED" `
        @ExtraJvmArgs `
        robocode.Robocode -battle $battleFile -nodisplay -nosound -results $results
} finally {
    Pop-Location
}

Write-Host "`n===== RESULTS ($Bot vs $Enemy, $Rounds rounds) ====="
Get-Content $results

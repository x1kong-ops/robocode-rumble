# 离线训练数据采集：枪波 + 冲浪命中样本
# 用法: .\scripts\datagen.ps1 [-Battles 2] [-Rounds 35] [-Gun] [-Surf]  （默认两者都采）
# 注意: 需要 -DNOSECURITY=true，仅本地 datagen 使用。
param(
    [int]$Battles = 2,
    [int]$Rounds = 35,
    [string]$RobocodeHome = $(if ($env:ROBOCODE_HOME) { $env:ROBOCODE_HOME } else { "C:\robocode" }),
    [switch]$Gun,
    [switch]$Surf
)

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
$dataDir = Join-Path $root "ml\data"
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

# 未指定开关时两者都采
$doGun = $Gun -or (-not $Gun -and -not $Surf)
$doSurf = $Surf -or (-not $Gun -and -not $Surf)

$enemies = @(
    "sample.Tracker",
    "sample.Walls",
    "sample.Crazy",
    "sample.SpinBot",
    "sample.RamFire",
    "sample.MyFirstJuniorRobot",
    "wiki.BasicGFSurfer 1.0",
    "voidious.mini.Komarious 1.88",
    "cx.mini.Cigaret 1.31",
    "wiki.mini.GouldingiHT 1.0",
    "davidalves.net.DuelistMini 1.1"
)

& (Join-Path $PSScriptRoot "build.ps1") -RobocodeHome $RobocodeHome | Out-Null

$gunTotal = 0
$surfTotal = 0
foreach ($enemy in $enemies) {
    $safe = $enemy -replace '[^A-Za-z0-9.]', '_'
    for ($i = 1; $i -le $Battles; $i++) {
        $jvm = @("-DNOSECURITY=true")
        $gunCsv = Join-Path $dataDir "gun-$safe-$i.csv"
        $surfCsv = Join-Path $dataDir "surf-$safe-$i.csv"
        if ($doGun) {
            if (Test-Path $gunCsv) { Remove-Item $gunCsv }
            $jvm += "-Dpc.datalog=$gunCsv"
        }
        if ($doSurf) {
            if (Test-Path $surfCsv) { Remove-Item $surfCsv }
            $jvm += "-Dpc.surfdata=$surfCsv"
        }
        & (Join-Path $PSScriptRoot "run-battle.ps1") -Enemy $enemy -Rounds $Rounds -SkipBuild `
            -ExtraJvmArgs $jvm 2>$null | Out-Null
        if ($doGun) {
            $g = if (Test-Path $gunCsv) { (Get-Content $gunCsv | Measure-Object -Line).Lines } else { 0 }
            $gunTotal += $g
        } else { $g = "-" }
        if ($doSurf) {
            $s = if (Test-Path $surfCsv) { (Get-Content $surfCsv | Measure-Object -Line).Lines } else { 0 }
            $surfTotal += $s
        } else { $s = "-" }
        Write-Host ("{0,-34} battle {1}: gun={2,6}  surf={3,6}" -f $enemy, $i, $g, $s)
    }
}
Write-Host "----------------------------------------------"
Write-Host "TOTAL gun=$gunTotal  surf=$surfTotal  ->  $dataDir"

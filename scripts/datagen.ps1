# 离线训练数据采集：对一组对手批量跑无头对战，把枪波数据 (features, gf, width, real) 写进 ml/data/*.csv
# 用法: .\scripts\datagen.ps1 [-Battles 2] [-Rounds 35]
# 注意: 需要 -DNOSECURITY=true 让机器人写沙箱外文件，仅本地 datagen 使用。
param(
    [int]$Battles = 2,
    [int]$Rounds = 35,
    [string]$RobocodeHome = $(if ($env:ROBOCODE_HOME) { $env:ROBOCODE_HOME } else { "C:\robocode" })
)

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
$dataDir = Join-Path $root "ml\data"
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

# 覆盖走位类型：追身 / 直线 / 随机 / 环绕 / 冲浪 / 老式躲避
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

$total = 0
foreach ($enemy in $enemies) {
    $safe = $enemy -replace '[^A-Za-z0-9.]', '_'
    for ($i = 1; $i -le $Battles; $i++) {
        $csv = Join-Path $dataDir "gun-$safe-$i.csv"
        if (Test-Path $csv) { Remove-Item $csv }
        & (Join-Path $PSScriptRoot "run-battle.ps1") -Enemy $enemy -Rounds $Rounds -SkipBuild `
            -ExtraJvmArgs "-DNOSECURITY=true", "-Drcr.datalog=$csv" 2>$null | Out-Null
        $lines = if (Test-Path $csv) { (Get-Content $csv | Measure-Object -Line).Lines } else { 0 }
        $total += $lines
        Write-Host ("{0,-34} battle {1}: {2,6} waves" -f $enemy, $i, $lines)
    }
}
Write-Host "----------------------------------------------"
Write-Host "TOTAL waves: $total  ->  $dataDir"

# 离线训练数据采集：枪波 + 冲浪命中样本
# 用法:
#   .\scripts\datagen.ps1 [-Battles 2] [-Rounds 35] [-Pool mid|rumble|all] [-Gun] [-Surf]
#   -Pool mid     中游风格（默认，与早期 datagen 一致）
#   -Pool rumble  本机强/次强 rumble bot + 少量中游（阶段 3.5）
#   -Pool all     mid + rumble
# 注意: 需要 -DNOSECURITY=true，仅本地 datagen 使用。
param(
    [int]$Battles = 2,
    [int]$Rounds = 35,
    [ValidateSet("mid", "rumble", "all")]
    [string]$Pool = "mid",
    [string]$RobocodeHome = $(if ($env:ROBOCODE_HOME) { $env:ROBOCODE_HOME } else { "C:\robocode" }),
    [switch]$Gun,
    [switch]$Surf,
    [switch]$Fresh   # 清空 ml/data 后重采
)

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
$dataDir = Join-Path $root "ml\data"
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

if ($Fresh) {
    Get-ChildItem $dataDir -Filter "*.csv" -ErrorAction SilentlyContinue | Remove-Item -Force
    Write-Host "cleared $dataDir"
}

# 未指定开关时两者都采
$doGun = $Gun -or (-not $Gun -and -not $Surf)
$doSurf = $Surf -or (-not $Gun -and -not $Surf)

$mid = @(
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

# 本机 hardbed 对手：冲浪样本主要靠他们（弱敌几乎打不中我们）
$rumble = @(
    "wiki.BasicGFSurfer 1.0",
    "voidious.mini.Komarious 1.88",
    "cx.mini.Cigaret 1.31",
    "davidalves.net.DuelistMini 1.1",
    "kc.mega.BeepBoop 2.0",
    "jk.mega.DrussGT 3.1.7",
    "voidious.Diamond 1.8.28",
    "oog.mega.saguaro.Saguaro 0.1",
    "mue.Ascendant 1.2.27",
    "kc.serpent.WaveSerpent 2.11",
    "ags.rougedc.RougeDC willow",
    "aw.Gilgalad 1.99.5c",
    "abc.Shadow 3.84i",
    "voidious.Dookious 1.573c",
    "darkcanuck.Gaff 1.50",
    "emp.Yngwie 1.11",
    "nova.Snow 1.0"
)

$enemies = switch ($Pool) {
    "mid" { $mid }
    "rumble" { $rumble }
    default {
        $seen = @{}
        $all = @()
        foreach ($e in ($mid + $rumble)) {
            if (-not $seen.ContainsKey($e)) { $seen[$e] = $true; $all += $e }
        }
        $all
    }
}

Write-Host ("datagen pool={0}  enemies={1}  battles={2}  rounds={3}" -f $Pool, $enemies.Count, $Battles, $Rounds)

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
        Write-Host ("{0,-38} battle {1}: gun={2,6}  surf={3,6}" -f $enemy, $i, $g, $s)
    }
}
Write-Host "----------------------------------------------"
Write-Host "TOTAL gun=$gunTotal  surf=$surfTotal  ->  $dataDir"

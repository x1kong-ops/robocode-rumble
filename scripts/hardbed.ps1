# 强对手验证组：本地有的顶级 / 次顶级 rumble bot。
# 用法:
#   .\scripts\hardbed.ps1 [-Rounds 100] [-Runs 3] [-SkipBuild]
#   .\scripts\hardbed.ps1 -Tier top          # 只跑前排 (BeepBoop/DrussGT/Diamond/...)
#   .\scripts\hardbed.ps1 -Tier strong       # 次顶级
#   .\scripts\hardbed.ps1 -Tier all          # 默认全跑
param(
    [int]$Rounds = 100,
    [int]$Runs = 3,
    [ValidateSet("top", "strong", "all")]
    [string]$Tier = "all",
    [string]$RobocodeHome = $(if ($env:ROBOCODE_HOME) { $env:ROBOCODE_HOME } else { "C:\robocode" }),
    [switch]$SkipBuild
)

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 本机 robots/ 已安装的强对手（classname + version）。Tier 按 LiteRumble 大致档位。
$top = @(
    "kc.mega.BeepBoop 2.0",           # ~95 APS 榜首
    "jk.mega.DrussGT 3.1.7",          # ~92
    "voidious.Diamond 1.8.28",        # ~90
    "oog.mega.saguaro.Saguaro 0.1",   # ~92 档（本机为 0.1）
    "mue.Ascendant 1.2.27"            # 次顶尖 GoTo/冲浪
)
$strong = @(
    "kc.serpent.WaveSerpent 2.11",
    "ags.rougedc.RougeDC willow",
    "aw.Gilgalad 1.99.5c",
    "abc.Shadow 3.84i",
    "voidious.Dookious 1.573c",
    "darkcanuck.Gaff 1.50",
    "emp.Yngwie 1.11",
    "nova.Snow 1.0"
)

$enemies = switch ($Tier) {
    "top" { $top }
    "strong" { $strong }
    default { $top + $strong }
}

if (-not $SkipBuild) {
    powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "build.ps1") `
        -RobocodeHome $RobocodeHome | Out-Null
}

$runBattle = Join-Path $PSScriptRoot "run-battle.ps1"
$summary = @()

Write-Host ("Hardbed tier={0}  rounds={1}  runs={2}  bots={3}" -f $Tier, $Rounds, $Runs, $enemies.Count)
Write-Host ("-" * 72)

foreach ($enemy in $enemies) {
    $scores = @()
    for ($i = 1; $i -le $Runs; $i++) {
        $out = powershell -ExecutionPolicy Bypass -File $runBattle `
            -Enemy $enemy -Rounds $Rounds -RobocodeHome $RobocodeHome -SkipBuild 2>$null | Out-String
        $pct = -1
        if ($out -match 'rcr\.Wavelet[^\r\n]*?\((\d+)%\)') { $pct = [int]$Matches[1] }
        if ($pct -ge 0) { $scores += $pct }
        Write-Host ("  [{0}/{1}] {2,-36} {3,3}%" -f $i, $Runs, $enemy, $pct)
    }
    if ($scores.Count -eq 0) {
        $summary += [pscustomobject]@{
            Enemy = $enemy; Avg = -1; Min = -1; Max = -1; Runs = ""
        }
        Write-Host ("{0,-38} NO RESULT" -f $enemy)
        continue
    }
    $avg = [math]::Round(($scores | Measure-Object -Average).Average, 1)
    $mn = ($scores | Measure-Object -Minimum).Minimum
    $mx = ($scores | Measure-Object -Maximum).Maximum
    $summary += [pscustomobject]@{
        Enemy = $enemy; Avg = $avg; Min = $mn; Max = $mx; Runs = ($scores -join "/")
    }
    Write-Host ("{0,-38} avg {1,5:N1}%  ({2})  min/max {3}/{4}" -f $enemy, $avg, ($scores -join "/"), $mn, $mx)
}

Write-Host ("=" * 72)
$valid = $summary | Where-Object { $_.Avg -ge 0 }
if ($valid.Count -eq 0) {
    Write-Host "WARNING: no battles parsed"
    exit 1
}

$overall = [math]::Round(($valid | Measure-Object -Property Avg -Average).Average, 1)
Write-Host ("OVERALL AVERAGE ({0} bots): {1:N1}%" -f $valid.Count, $overall)

# 阶段 2 报告门槛：对顶尖配对 40%+；全体无 <70%（本地强组用 40/70 作诊断线）
$vsTop = $valid | Where-Object { $top -contains $_.Enemy }
if ($vsTop) {
    $topAvg = [math]::Round(($vsTop | Measure-Object -Property Avg -Average).Average, 1)
    Write-Host ("vs TOP-TIER AVERAGE ({0} bots): {1:N1}%  (roadmap gate: 40%+)" -f $vsTop.Count, $topAvg)
    $below40 = @($vsTop | Where-Object { $_.Avg -lt 40 })
    if ($below40.Count -gt 0) {
        Write-Host ("  below 40%: " + (($below40 | ForEach-Object { "{0} {1}%" -f $_.Enemy, $_.Avg }) -join "; "))
    } else {
        Write-Host "  all top-tier matchups >= 40%"
    }
}

$below70 = @($valid | Where-Object { $_.Avg -lt 70 })
if ($below70.Count -gt 0) {
    $list = ($below70 | ForEach-Object { "{0} {1}%" -f $_.Enemy, $_.Avg }) -join "; "
    Write-Host (("below 70% ({0}): {1}" -f $below70.Count, $list))
} else {
    Write-Host "all matchups >= 70%"
}

# 写一份便于对照的结果文件
$outDir = Join-Path (Split-Path -Parent $PSScriptRoot) "out"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$report = Join-Path $outDir ("hardbed-{0}-{1}.txt" -f $Tier, $stamp)
$lines = @(
    "Hardbed tier=$Tier rounds=$Rounds runs=$Runs",
    ("OVERALL {0:N1}%" -f $overall)
) + ($valid | ForEach-Object { "{0,-38} avg={1,5:N1}  runs={2}" -f $_.Enemy, $_.Avg, $_.Runs })
$lines | Set-Content -Path $report -Encoding UTF8
Write-Host "report -> $report"

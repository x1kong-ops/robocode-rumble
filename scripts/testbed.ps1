# Run the benchmark test bed (a spread of targeting/movement styles) and print a summary.
# Usage: .\scripts\testbed.ps1 [-Rounds 35] [-SkipBuild]
param(
    [int]$Rounds = 35,
    [string]$RobocodeHome = $(if ($env:ROBOCODE_HOME) { $env:ROBOCODE_HOME } else { "C:\robocode" }),
    [switch]$SkipBuild,
    [string[]]$Enemies = @(
        "sample.Tracker",                  # head-on / tracking
        "sample.Walls",                    # wall movement, linear-ish gun
        "sample.Crazy",                    # random movement
        "wiki.BasicGFSurfer 1.0",          # GF gun + basic wave surfing (the pass line)
        "voidious.mini.Komarious 1.88",    # mini surfer + GF gun
        "cx.mini.Cigaret 1.31",            # pattern matching gun + oscillator
        "wiki.mini.GouldingiHT 1.0",       # head-on tracker mini
        "davidalves.net.DuelistMini 1.1"   # dodging + linear/circular guns
    )
)

# Java writes warnings to stderr; PS 5.1 turns redirected native stderr into error
# records, so keep EAP at Continue and discard stderr from child runs.
$ErrorActionPreference = "Continue"

if (-not $SkipBuild) {
    powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "build.ps1") -RobocodeHome $RobocodeHome | Out-Null
}

$runBattle = Join-Path $PSScriptRoot "run-battle.ps1"
$summary = @()
foreach ($enemy in $Enemies) {
    $out = powershell -ExecutionPolicy Bypass -File $runBattle `
        -Enemy $enemy -Rounds $Rounds -RobocodeHome $RobocodeHome -SkipBuild 2>$null | Out-String
    $pct = -1
    if ($out -match 'rcr\.Wavelet[^\r\n]*?\((\d+)%\)') { $pct = [int]$Matches[1] }
    $summary += [pscustomobject]@{ Enemy = $enemy; ScorePct = $pct }
    Write-Host ("{0,-38} {1,3}%" -f $enemy, $pct)
}

$valid = $summary | Where-Object { $_.ScorePct -ge 0 }
if ($valid.Count -gt 0) {
    $avg = ($valid | Measure-Object -Property ScorePct -Average).Average
    Write-Host ("-" * 45)
    Write-Host ("{0,-38} {1,5:N1}%" -f "AVERAGE ($($valid.Count) bots)", $avg)
}
$failed = $summary | Where-Object { $_.ScorePct -lt 0 }
if ($failed.Count -gt 0) {
    Write-Host "WARNING: no result parsed for: $($failed.Enemy -join ', ')"
}

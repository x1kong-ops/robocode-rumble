# Wavelet(θ) vs Spar(θ'). Defaults = current live constants.
# Usage:
#   .\scripts\run-mirror.ps1 [-Rounds 35]
#   .\scripts\run-mirror.ps1 -WaveletParams path.txt -SparParams path.txt
param(
    [int]$Rounds = 35,
    [string]$WaveletParams,
    [string]$SparParams,
    [string]$RobocodeHome = $(if ($env:ROBOCODE_HOME) { $env:ROBOCODE_HOME } else { "C:\robocode" })
)

$ErrorActionPreference = "Stop"
$jvm = @("-DNOSECURITY=true")
if ($WaveletParams) { $jvm += "-Dpc.params.Wavelet=$WaveletParams" }
if ($SparParams) { $jvm += "-Dpc.params.Spar=$SparParams" }

& (Join-Path $PSScriptRoot "run-battle.ps1") -Bot "pc.Wavelet dev" -Enemy "pc.Spar dev" `
    -Rounds $Rounds -RobocodeHome $RobocodeHome -ExtraJvmArgs $jvm

# 编译机器人到 out\classes（保持 Java 8 字节码兼容，需 JDK 9+，推荐 JDK 17）
param(
    [string]$RobocodeHome = $(if ($env:ROBOCODE_HOME) { $env:ROBOCODE_HOME } else { "C:\robocode" })
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot

$robocodeJar = Join-Path $RobocodeHome "libs\robocode.jar"
if (-not (Test-Path $robocodeJar)) {
    $msg = "找不到 $robocodeJar`n" +
        "请先安装 Robocode 1.9.4.2（https://sourceforge.net/projects/robocode/files/robocode/1.9.4.2/），" +
        "或通过 -RobocodeHome 参数 / ROBOCODE_HOME 环境变量指定安装目录。"
    Write-Error $msg
}

$outDir = Join-Path $root "out\classes"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$sources = Get-ChildItem -Path (Join-Path $root "src") -Recurse -Filter *.java |
    Select-Object -ExpandProperty FullName
if (-not $sources) {
    Write-Error "src 目录下没有找到 .java 源文件。"
}

javac -encoding UTF-8 --release 8 -cp $robocodeJar -d $outDir @($sources)
if ($LASTEXITCODE -ne 0) {
    Write-Error "编译失败（javac 退出码 $LASTEXITCODE）。"
}

Write-Host "编译完成 -> $outDir"
Write-Host "用 scripts\run-battle.ps1 可打包部署并跑无头对战（机器人名 pc.Wavelet dev）"
